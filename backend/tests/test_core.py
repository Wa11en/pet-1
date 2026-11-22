from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from datetime import datetime, timezone
import base64
import json
from pathlib import Path
import secrets
import tempfile
import unittest
import uuid

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from diary_payments.core import (CheckoutConflict, PaymentService, ProviderMismatch,
                                 Unauthorized, account_hash, month_after)
from diary_payments.provider import ProviderUnavailable
from diary_payments.signatures import LicenseSigner, base64url


def epoch(year, month, day, hour=12):
    return int(datetime(year, month, day, hour, tzinfo=timezone.utc).timestamp() * 1000)


class FakeProvider:
    """Injected directly into tests. There is no fake-payment HTTP endpoint."""
    def __init__(self):
        self.payments = {}
        self.created = []
        self.fetched = []
        self.timeout_once = False

    def create(self, checkout_id):
        self.created.append(checkout_id)
        payment_id = str(uuid.uuid5(uuid.NAMESPACE_OID, checkout_id))
        if payment_id not in self.payments:
            self.payments[payment_id] = {
                "id": payment_id, "status": "pending", "paid": False,
                "amount": {"value": "99.00", "currency": "RUB"},
                "recipient": {"account_id": "123456"},
                "metadata": {"checkout_id": checkout_id},
                "payment_method": {"type": "sbp"},
                "confirmation": {"confirmation_url": "https://yoomoney.ru/checkout/payments/" + payment_id},
            }
        if self.timeout_once:
            self.timeout_once = False
            raise ProviderUnavailable("Unavailable")
        return deepcopy(self.payments[payment_id])

    def get(self, payment_id):
        self.fetched.append(payment_id)
        return deepcopy(self.payments[payment_id])

    def paid(self, checkout):
        payment = next(item for item in self.payments.values() if item["metadata"]["checkout_id"] == checkout["checkoutId"])
        payment.update(status="succeeded", paid=True)
        return payment


class PaymentFixture:
    @classmethod
    def setUpClass(cls):
        cls.signer = LicenseSigner(rsa.generate_private_key(public_exponent=65537, key_size=3072))

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.provider = FakeProvider()
        self.now = epoch(2027, 1, 31)
        self.token = base64url(secrets.token_bytes(32))
        self.installation = str(uuid.uuid4())
        self.service = PaymentService(Path(self.folder.name) / "payments.sqlite3", self.provider,
                                      self.signer, "123456", clock=lambda: self.now)

    def checkout(self, request_id=None, token=None):
        return self.service.checkout(self.installation, token or self.token, request_id or str(uuid.uuid4()), "pro_monthly")

    def status(self, checkout):
        return self.service.status(checkout["checkoutId"], self.token, self.installation)

    def claims(self, token):
        version, payload, signature = token.split(".")
        self.assertEqual("v1", version)
        self.signer._key.public_key().verify(base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4)),
            (version + "." + payload).encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
        return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


class PaymentTests(PaymentFixture, unittest.TestCase):

    def test_pending_payment_never_grants_access(self):
        checkout = self.checkout()
        self.assertEqual({"status": "pending", "entitlement": None}, self.status(checkout))
        self.assertEqual({"entitlement": None}, self.service.restore(self.token, self.installation))

    def test_forged_webhook_must_not_grant_access(self):
        checkout = self.checkout()
        payment_id = next(iter(self.provider.payments))
        self.service.webhook("payment.succeeded", {"id": payment_id, "status": "succeeded", "paid": True})
        self.assertEqual("pending", self.status(checkout)["status"])
        self.assertGreater(len(self.provider.fetched), 0)

    def test_unknown_webhook_does_not_trigger_provider_calls(self):
        self.service.webhook("payment.succeeded", {"id": str(uuid.uuid4()), "paid": True})
        self.assertEqual([], self.provider.fetched)

    def test_amount_currency_merchant_method_metadata_must_match(self):
        checkout = self.checkout()
        payment = self.provider.paid(checkout)
        original = deepcopy(payment)
        changes = [("amount", "value", "0.99"), ("amount", "currency", "USD"),
                   ("recipient", "account_id", "999999"), ("payment_method", "type", "bank_card"),
                   ("metadata", "checkout_id", str(uuid.uuid4()))]
        for section, key, value in changes:
            with self.subTest(section=section, key=key):
                payment.clear()
                payment.update(deepcopy(original))
                payment[section][key] = value
                with self.assertRaises(ProviderMismatch):
                    self.status(checkout)
                with self.service.connect() as db:
                    self.assertEqual(0, db.execute("SELECT expires_at FROM accounts").fetchone()[0])

    def test_status_succeeded_without_paid_flag_cannot_grant(self):
        checkout = self.checkout()
        self.provider.paid(checkout)["paid"] = False
        self.assertIsNone(self.status(checkout)["entitlement"])

    def test_canceled_payment_never_grants(self):
        checkout = self.checkout()
        self.provider.get(next(iter(self.provider.payments)))
        next(iter(self.provider.payments.values()))["status"] = "canceled"
        self.assertEqual({"status": "canceled", "entitlement": None}, self.status(checkout))

    def test_paid_replay_is_idempotent_and_signature_has_exact_claims(self):
        checkout = self.checkout()
        payment = self.provider.paid(checkout)
        first = self.claims(self.status(checkout)["entitlement"])
        self.now += 60_000
        for _ in range(5):
            self.service.webhook("payment.succeeded", {"id": payment["id"]})
        second = self.claims(self.status(checkout)["entitlement"])
        self.assertEqual(first["expiresAt"], second["expiresAt"])
        self.assertEqual(epoch(2027, 2, 28), first["expiresAt"])
        self.assertEqual({"version", "aud", "sub", "plan", "issuedAt", "expiresAt"}, set(first))
        self.assertEqual("ru.privatediary", first["aud"])
        self.assertEqual("pro_monthly", first["plan"])

    def test_parallel_confirmations_grant_one_month_once(self):
        checkout = self.checkout()
        self.provider.paid(checkout)
        with ThreadPoolExecutor(max_workers=8) as workers:
            responses = list(workers.map(lambda _: self.status(checkout), range(24)))
        self.assertEqual({epoch(2027, 2, 28)}, {self.claims(row["entitlement"])["expiresAt"] for row in responses})

    def test_calendar_month_clamps_end_of_month_including_leap_year(self):
        self.assertEqual(epoch(2027, 2, 28), month_after(epoch(2027, 1, 31)))
        self.assertEqual(epoch(2028, 2, 29), month_after(epoch(2028, 1, 31)))
        self.assertEqual(epoch(2028, 1, 31), month_after(epoch(2027, 12, 31)))

    def test_wrong_recovery_code_cannot_read_payment_or_call_provider(self):
        checkout = self.checkout()
        wrong = base64url(secrets.token_bytes(32))
        with self.assertRaises(Unauthorized):
            self.service.status(checkout["checkoutId"], wrong, self.installation)
        with self.assertRaises(Unauthorized):
            self.service.restore(wrong, self.installation)
        self.assertEqual([], self.provider.fetched)

    def test_only_canonical_256bit_recovery_codes_are_accepted(self):
        for token in ("", "x" * 42, "x" * 44, "A" * 42 + "B", self.token + "=", "а" * 43):
            with self.subTest(token_length=len(token)), self.assertRaises(Unauthorized):
                account_hash(token)

    def test_same_request_id_returns_same_checkout_without_second_charge(self):
        request = str(uuid.uuid4())
        first = self.checkout(request)
        self.assertEqual(first, self.checkout(request))
        self.assertEqual(1, len(self.provider.created))
        self.provider.paid(first)
        self.status(first)
        self.assertEqual(first, self.checkout(request))
        self.assertEqual(1, len(self.provider.created))

    def test_new_request_cannot_create_second_pending_payment(self):
        self.checkout()
        with self.assertRaises(CheckoutConflict):
            self.checkout()
        self.assertEqual(1, len(self.provider.created))

    def test_timeout_retries_same_provider_idempotency_key(self):
        request = str(uuid.uuid4())
        self.provider.timeout_once = True
        with self.assertRaises(ProviderUnavailable):
            self.checkout(request)
        checkout = self.checkout(request)
        self.assertEqual([checkout["checkoutId"], checkout["checkoutId"]], self.provider.created)
        self.assertEqual(1, len(self.provider.payments))

    def test_ambiguous_timeout_cannot_be_retried_after_provider_key_expiry(self):
        request = str(uuid.uuid4())
        self.provider.timeout_once = True
        with self.assertRaises(ProviderUnavailable):
            self.checkout(request)
        self.now += 24 * 60 * 60 * 1000
        with self.assertRaises(CheckoutConflict):
            self.checkout(request)
        self.assertEqual(1, len(self.provider.created))

    def test_restore_new_device_binds_certificate_to_requested_installation(self):
        checkout = self.checkout()
        self.provider.paid(checkout)
        new_installation = str(uuid.uuid4())
        result = self.service.restore(self.token, new_installation)
        claims = self.claims(result["entitlement"])
        self.assertEqual(new_installation, claims["sub"])
        self.assertNotEqual(self.installation, claims["sub"])

    def test_manual_renewal_extends_existing_expiry_without_autocharge(self):
        first = self.checkout()
        self.provider.paid(first)
        expiry = self.claims(self.status(first)["entitlement"])["expiresAt"]
        second = self.checkout()
        self.assertIsNone(self.status(second)["entitlement"])
        self.provider.paid(second)
        self.assertEqual(month_after(expiry), self.claims(self.status(second)["entitlement"])["expiresAt"])

    def test_renewal_after_expiration_starts_at_new_payment_confirmation(self):
        first = self.checkout()
        self.provider.paid(first)
        self.status(first)
        self.now = epoch(2027, 4, 2)
        second = self.checkout()
        self.provider.paid(second)
        self.assertEqual(epoch(2027, 5, 2), self.claims(self.status(second)["entitlement"])["expiresAt"])

    def test_restore_does_not_requery_expired_historical_payments(self):
        first = self.checkout()
        self.provider.paid(first)
        self.status(first)
        self.now = epoch(2027, 3, 2)
        second = self.checkout()
        second_payment = self.provider.paid(second)
        self.status(second)
        self.provider.fetched.clear()
        self.assertIsNotNone(self.service.restore(self.token, self.installation)["entitlement"])
        self.assertEqual([second_payment["id"]], self.provider.fetched)

    def test_partial_or_full_refund_revokes_and_cannot_be_replayed_into_grant(self):
        checkout = self.checkout()
        payment = self.provider.paid(checkout)
        self.assertIsNotNone(self.status(checkout)["entitlement"])
        payment["refunded_amount"] = {"value": "99.00", "currency": "RUB"}
        self.service.webhook("refund.succeeded", {"payment_id": payment["id"]})
        self.assertIsNone(self.service.restore(self.token, self.installation)["entitlement"])
        del payment["refunded_amount"]  # Stale API response must not recreate refunded access.
        self.assertEqual("canceled", self.status(checkout)["status"])

    def test_invalid_refund_response_fails_closed(self):
        checkout = self.checkout()
        payment = self.provider.paid(checkout)
        for value in ({"value": "NaN", "currency": "RUB"}, {"value": "-1", "currency": "RUB"},
                      {"value": "99.00", "currency": "USD"}, {"value": "bad", "currency": "RUB"}):
            with self.subTest(value=value):
                payment["refunded_amount"] = value
                with self.assertRaises(ProviderMismatch):
                    self.status(checkout)

    def test_no_more_than_twelve_months_can_be_prepaid(self):
        for _ in range(12):
            checkout = self.checkout()
            self.provider.paid(checkout)
            claims = self.claims(self.status(checkout)["entitlement"])
            self.assertLess(claims["expiresAt"] - claims["issuedAt"], 400 * 24 * 60 * 60 * 1000)
        with self.assertRaises(CheckoutConflict):
            self.checkout()

    def test_server_stores_hash_not_recovery_secret(self):
        self.checkout()
        with self.service.connect() as db:
            owner = db.execute("SELECT account_hash FROM accounts").fetchone()[0]
            self.assertEqual(account_hash(self.token), owner)
        for path in Path(self.folder.name).glob("payments.sqlite3*"):
            self.assertNotIn(self.token.encode("ascii"), path.read_bytes())
        self.assertEqual(0o600, self.service.database_path.stat().st_mode & 0o777)
