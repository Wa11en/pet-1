"""Payment state machine. Only a server-to-server YooKassa result can grant access."""
from __future__ import annotations

import base64
import calendar
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import hmac
import os
from pathlib import Path
import re
import sqlite3
import time
from typing import Protocol
import uuid

from .signatures import LicenseSigner

PRICE = "99.00"
CURRENCY = "RUB"
PLAN = "pro_monthly"
MAX_ACCOUNT_ORDERS = 240


class Unauthorized(ValueError):
    pass


class ProviderMismatch(ValueError):
    pass


class PaymentsUnavailable(RuntimeError):
    pass


class CheckoutConflict(ValueError):
    pass


class Provider(Protocol):
    def create(self, checkout_id: str) -> dict: ...
    def get(self, payment_id: str) -> dict: ...


def account_hash(token: str) -> str:
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        raise Unauthorized("Invalid recovery code")
    decoded = base64.b64decode(token + "=", altchars=b"-_", validate=True)
    if len(decoded) != 32 or base64.urlsafe_b64encode(decoded).rstrip(b"=").decode("ascii") != token:
        raise Unauthorized("Invalid recovery code")
    return hashlib.sha256(token.encode("ascii")).hexdigest()


def month_after(timestamp: int) -> int:
    current = datetime.fromtimestamp(timestamp / 1000, timezone.utc)
    year = current.year + (current.month == 12)
    month = current.month % 12 + 1
    return int(current.replace(year=year, month=month,
        day=min(current.day, calendar.monthrange(year, month)[1])).timestamp() * 1000)


class PaymentService:
    def __init__(self, database_path: Path, provider: Provider | None, signer: LicenseSigner | None,
                 shop_id: str | None, clock=None):
        self.database_path = Path(database_path)
        self.provider, self.signer, self.shop_id = provider, signer, shop_id
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.database_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        descriptor = os.open(self.database_path, os.O_RDWR | os.O_CREAT, 0o600)
        os.close(descriptor)
        self.database_path.chmod(0o600)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS accounts (
                  account_hash TEXT PRIMARY KEY,
                  expires_at INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS payments (
                  checkout_id TEXT PRIMARY KEY,
                  account_hash TEXT NOT NULL REFERENCES accounts(account_hash),
                  request_id TEXT NOT NULL,
                  installation_id TEXT NOT NULL,
                  payment_id TEXT UNIQUE,
                  payment_url TEXT,
                  status TEXT NOT NULL DEFAULT 'pending',
                  verified_at INTEGER,
                  grant_expires_at INTEGER NOT NULL DEFAULT 0,
                  refund_confirmed INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  UNIQUE(account_hash, request_id)
                );
                CREATE INDEX IF NOT EXISTS payment_accounts ON payments(account_hash, created_at);
            """)
        self.database_path.chmod(0o600)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.database_path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=15000")
        try:
            with db:
                yield db
        finally:
            db.close()

    def ready(self):
        if self.provider is None or self.signer is None or not self.shop_id:
            raise PaymentsUnavailable("Payments are not configured")

    def checkout(self, installation_id: str, token: str, request_id: str, plan_id: str) -> dict:
        self.ready()
        installation_id, request_id = str(uuid.UUID(installation_id)), str(uuid.UUID(request_id))
        if plan_id != PLAN:
            raise ValueError("Unknown plan")
        owner = account_hash(token)
        now = self.clock()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("INSERT OR IGNORE INTO accounts(account_hash,created_at) VALUES (?,?)", (owner, now))
            row = db.execute("SELECT * FROM payments WHERE account_hash=? AND request_id=?", (owner, request_id)).fetchone()
            if row is None:
                account_expiry = db.execute("SELECT expires_at FROM accounts WHERE account_hash=?", (owner,)).fetchone()[0]
                max_prepaid = now
                for _ in range(11):
                    max_prepaid = month_after(max_prepaid)
                if account_expiry > max_prepaid:
                    raise CheckoutConflict("At most twelve months can be prepaid")
                if db.execute("SELECT 1 FROM payments WHERE account_hash=? AND status='pending'", (owner,)).fetchone():
                    raise CheckoutConflict("An unfinished checkout already exists; retry its original request")
                count = db.execute("SELECT COUNT(*) FROM payments WHERE account_hash=?", (owner,)).fetchone()[0]
                if count >= MAX_ACCOUNT_ORDERS:
                    raise CheckoutConflict("Too many payment attempts; contact support")
                checkout_id = str(uuid.uuid4())
                db.execute("INSERT INTO payments(checkout_id,account_hash,request_id,installation_id,created_at) VALUES (?,?,?,?,?)",
                    (checkout_id, owner, request_id, installation_id, now))
                row = db.execute("SELECT * FROM payments WHERE checkout_id=?", (checkout_id,)).fetchone()
            elif row["installation_id"] != installation_id:
                raise CheckoutConflict("The idempotency request belongs to another installation")
        if row["payment_id"] is None:
            # YooKassa retains idempotency keys for 24 hours. After that an
            # ambiguous timeout needs merchant reconciliation, never a new charge.
            if now - row["created_at"] >= 23 * 60 * 60 * 1000:
                raise CheckoutConflict("Ambiguous old checkout; contact support before retrying")
            # Every retry uses the SAME provider idempotency key, including network timeouts.
            payment = self.provider.create(row["checkout_id"])
            self.validate(payment, row["checkout_id"])
            from .provider import validated_payment_url
            payment_url = validated_payment_url(payment.get("confirmation", {}).get("confirmation_url"))
            payment_id = str(payment["id"])
            with self.connect() as db:
                db.execute("BEGIN IMMEDIATE")
                current = db.execute("SELECT * FROM payments WHERE checkout_id=?", (row["checkout_id"],)).fetchone()
                if current["payment_id"] not in (None, payment_id):
                    raise ProviderMismatch("Provider returned a different idempotent payment")
                db.execute("UPDATE payments SET payment_id=?,payment_url=? WHERE checkout_id=?",
                    (payment_id, payment_url, row["checkout_id"]))
                row = db.execute("SELECT * FROM payments WHERE checkout_id=?", (row["checkout_id"],)).fetchone()
        return {"checkoutId": row["checkout_id"], "paymentUrl": row["payment_url"],
                "amount": PRICE, "currency": CURRENCY, "period": "month"}

    def validate(self, payment: dict, checkout_id: str) -> None:
        try:
            amount = Decimal(payment["amount"]["value"])
            valid = (isinstance(payment.get("id"), str) and 1 <= len(payment["id"]) <= 128 and
                amount.is_finite() and amount == Decimal(PRICE) and payment["amount"]["currency"] == CURRENCY and
                payment["recipient"]["account_id"] == self.shop_id and
                payment["metadata"]["checkout_id"] == checkout_id and
                payment["payment_method"]["type"] == "sbp" and
                payment["status"] in ("pending", "waiting_for_capture", "succeeded", "canceled"))
        except (KeyError, TypeError, InvalidOperation, ValueError):
            valid = False
        if not valid:
            raise ProviderMismatch("The provider payment does not match this order")

    def authenticate(self, db, token: str, checkout_id: str | None = None):
        owner = account_hash(token)
        if db.execute("SELECT account_hash FROM accounts WHERE account_hash=?", (owner,)).fetchone() is None:
            raise Unauthorized("Unknown recovery code")
        if checkout_id:
            row = db.execute("SELECT * FROM payments WHERE checkout_id=?", (checkout_id,)).fetchone()
            if row is None or not hmac.compare_digest(row["account_hash"], owner):
                # Do not expose another account's purchase existence.
                raise Unauthorized("Payment not found for this recovery code")
            return owner, row
        return owner, None

    def refresh(self, checkout_id: str) -> None:
        self.ready()
        with self.connect() as db:
            row = db.execute("SELECT * FROM payments WHERE checkout_id=?", (checkout_id,)).fetchone()
        if row is None or row["payment_id"] is None:
            return
        payment = self.provider.get(row["payment_id"])
        self.validate(payment, checkout_id)
        if payment["id"] != row["payment_id"]:
            raise ProviderMismatch("Payment id mismatch")
        try:
            refund = payment.get("refunded_amount", {})
            refunded = Decimal(str(refund.get("value", "0.00")))
            refund_valid = (refunded.is_finite() and Decimal(0) <= refunded <= Decimal(PRICE) and
                            (not refund or refund.get("currency") == CURRENCY))
        except (InvalidOperation, ValueError, TypeError, AttributeError):
            refund_valid = False
        if not refund_valid:
            raise ProviderMismatch("Invalid refund state")
        succeeded = payment["status"] == "succeeded" and payment.get("paid") is True and refunded == 0
        canceled = payment["status"] == "canceled" or refunded > 0
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            current = db.execute("SELECT * FROM payments WHERE checkout_id=?", (checkout_id,)).fetchone()
            if current["refund_confirmed"]:
                return
            status = "paid" if succeeded else "canceled" if canceled else "pending"
            # Never erase a confirmed grant because of a stale pending/canceled provider response.
            # Only an authenticated provider refund can revoke a previously confirmed grant.
            if current["status"] == "paid" and status != "paid" and refunded == 0:
                return
            if current["status"] == status:
                return
            verified_at = current["verified_at"]
            if status == "paid" and verified_at is None:
                verified_at = self.clock()
            db.execute("UPDATE payments SET status=?,verified_at=?,refund_confirmed=? WHERE checkout_id=?",
                       (status, verified_at, int(refunded > 0), checkout_id))
            grants = db.execute("SELECT checkout_id,verified_at FROM payments WHERE account_hash=? AND status='paid' ORDER BY verified_at,checkout_id",
                               (current["account_hash"],)).fetchall()
            expiry = 0
            db.execute("UPDATE payments SET grant_expires_at=0 WHERE account_hash=?", (current["account_hash"],))
            for grant in grants:
                expiry = month_after(max(expiry, grant["verified_at"]))
                db.execute("UPDATE payments SET grant_expires_at=? WHERE checkout_id=?", (expiry, grant["checkout_id"]))
            db.execute("UPDATE accounts SET expires_at=? WHERE account_hash=?", (expiry, current["account_hash"]))

    def status(self, checkout_id: str, token: str, installation_id: str) -> dict:
        self.ready()
        installation_id = str(uuid.UUID(installation_id))
        with self.connect() as db:
            owner, row = self.authenticate(db, token, checkout_id)
        self.refresh(row["checkout_id"])
        with self.connect() as db:
            _, row = self.authenticate(db, token, checkout_id)
            expiry = db.execute("SELECT expires_at FROM accounts WHERE account_hash=?", (owner,)).fetchone()[0]
        now = self.clock()
        return {"status": row["status"], "entitlement": self.signer.issue(installation_id, now, expiry)
                if row["status"] == "paid" and expiry > now else None}

    def restore(self, token: str, installation_id: str) -> dict:
        self.ready()
        installation_id = str(uuid.UUID(installation_id))
        with self.connect() as db:
            owner, _ = self.authenticate(db, token)
            payments = db.execute("""SELECT checkout_id FROM payments WHERE account_hash=? AND payment_id IS NOT NULL
                AND (status='pending' OR (status='paid' AND grant_expires_at>?)) ORDER BY created_at DESC""",
                (owner, self.clock())).fetchall()
        # The checkout limit permits twelve active months and one pending
        # order at most. Never turn years of payment history into N API calls.
        if len(payments) > 13:
            raise PaymentsUnavailable("Inconsistent active-payment state")
        with ThreadPoolExecutor(max_workers=4) as workers:
            list(workers.map(lambda row: self.refresh(row["checkout_id"]), payments))
        with self.connect() as db:
            expiry = db.execute("SELECT expires_at FROM accounts WHERE account_hash=?", (owner,)).fetchone()[0]
        now = self.clock()
        return {"entitlement": self.signer.issue(installation_id, now, expiry) if expiry > now else None}

    def webhook(self, event: str, obj: dict) -> None:
        # The incoming webhook is UNTRUSTED. Only lookup its known id, then fetch
        # the complete payment from YooKassa using the server's credentials.
        if event in ("payment.succeeded", "payment.canceled", "payment.waiting_for_capture"):
            payment_id = obj.get("id")
        elif event == "refund.succeeded":
            payment_id = obj.get("payment_id")
        else:
            return
        if not isinstance(payment_id, str) or not 1 <= len(payment_id) <= 128:
            return
        with self.connect() as db:
            row = db.execute("SELECT checkout_id FROM payments WHERE payment_id=?", (payment_id,)).fetchone()
        if row:
            self.refresh(row["checkout_id"])
