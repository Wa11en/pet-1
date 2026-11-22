"""HTTPS payment API. This process has no diary-reading or advertising API."""
from __future__ import annotations

from collections import defaultdict, deque
import hashlib
import secrets
import threading
import time
from typing import Annotated, Literal
from uuid import UUID

from cryptography.exceptions import UnsupportedAlgorithm
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field

from .core import CheckoutConflict, PaymentService, PaymentsUnavailable, ProviderMismatch, Unauthorized
from .provider import ProviderUnavailable, YooKassaProvider
from .settings import Settings
from .signatures import LicenseSigner

MAX_BODY_BYTES = 16 * 1024
RecoveryCode = Annotated[str, Field(min_length=43, max_length=43, pattern=r"^[A-Za-z0-9_-]{43}$")]


class StrictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")


class CheckoutRequest(StrictRequest):
    installationId: UUID
    accountToken: RecoveryCode
    requestId: UUID
    planId: Literal["pro_monthly"]


class RestoreRequest(StrictRequest):
    installationId: UUID
    accountToken: RecoveryCode


class Notification(StrictRequest):
    type: Literal["notification"]
    event: str = Field(max_length=80)
    object: dict


class RequestBoundary:
    """Bounded body, HTTPS, no caching, and a small per-source abuse limit.

    IP addresses are salted hashes in memory, never stored in the database.
    The reverse proxy must also limit connections and trust no client headers.
    """
    def __init__(self, app, *, clock=None):
        self.app = app
        self.clock = clock or time.monotonic
        self._salt = secrets.token_bytes(32)
        self._requests = defaultdict(deque)
        self._lock = threading.Lock()

    def allowed(self, source: str, checkout: bool) -> bool:
        now = self.clock()
        key = hashlib.sha256(self._salt + source.encode("utf-8")).digest()
        with self._lock:
            # Bound memory even under a distributed flood. The edge proxy has
            # the authoritative global limit; this is defense in depth.
            if key not in self._requests and len(self._requests) >= 4096:
                expired = [item for item, rows in self._requests.items() if not rows or rows[-1][0] <= now - 60]
                for item in expired:
                    del self._requests[item]
                if len(self._requests) >= 4096:
                    return False
            rows = self._requests[key]
            while rows and rows[0][0] <= now - 60:
                rows.popleft()
            if len(rows) >= 60 or (checkout and sum(row[1] for row in rows) >= 5):
                return False
            rows.append((now, checkout))
            return True

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        async def secured_send(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers", []))
                headers.extend([(b"cache-control", b"no-store"), (b"x-content-type-options", b"nosniff")])
                message = {**message, "headers": headers}
            await send(message)

        async def reject(code, detail):
            await JSONResponse(status_code=code, content={"detail": detail})(scope, receive, secured_send)

        if scope["path"] != "/health" and scope["scheme"] != "https":
            return await reject(426, "HTTPS required")
        source = (scope.get("client") or ("unknown", 0))[0]
        if not self.allowed(str(source), scope["path"] == "/v1/checkout"):
            return await reject(429, "Try again later")
        chunks, size = [], 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > MAX_BODY_BYTES:
                return await reject(413, "Request too large")
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        delivered = False

        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
            return await receive()

        await self.app(scope, bounded_receive, secured_send)


def configured_service(settings: Settings) -> PaymentService | None:
    if not settings.configured():
        return None
    try:
        signer = LicenseSigner.from_file(settings.signing_key_path)
        provider = YooKassaProvider(settings.shop_id, settings.shop_secret, settings.return_url)
        return PaymentService(settings.database_path, provider, signer, settings.shop_id)
    except (OSError, ValueError, UnsupportedAlgorithm, ProviderUnavailable):
        # Configuration errors fail closed; never put a private-key path,
        # provider body, credential or recovery code in a response/log.
        return None


def create_app(*, settings: Settings | None = None, service: PaymentService | None = None) -> FastAPI:
    settings = settings or Settings.from_environment()
    application = FastAPI(title="Private Diary payments", version="1.0.0",
                          docs_url=None, redoc_url=None, openapi_url=None)
    application.state.payments = service if service is not None else configured_service(settings)
    application.add_middleware(RequestBoundary)

    def payments() -> PaymentService:
        current = application.state.payments
        if current is None:
            raise PaymentsUnavailable("Payments are not configured")
        current.ready()
        return current

    @application.exception_handler(RequestValidationError)
    async def invalid_request(_request, _error):
        # Pydantic's default error includes input values, including recovery
        # codes. Do not reflect those sensitive values into HTTP responses.
        return JSONResponse(status_code=422, content={"detail": "Invalid request fields"})

    @application.exception_handler(Unauthorized)
    async def unauthorized(_request, _error):
        return JSONResponse(status_code=401, content={"detail": "Invalid recovery code or payment"})

    @application.exception_handler(CheckoutConflict)
    async def conflict(_request, _error):
        return JSONResponse(status_code=409, content={"detail": "Checkout cannot be started; resume the previous checkout or contact support"})

    async def unavailable(_request, _error):
        return JSONResponse(status_code=503, content={"detail": "Payments are temporarily unavailable"}, headers={"Retry-After": "60"})

    for error in (PaymentsUnavailable, ProviderUnavailable, ProviderMismatch):
        application.add_exception_handler(error, unavailable)

    @application.get("/health")
    def health():
        return {"status": "ok", "paymentsConfigured": application.state.payments is not None}

    @application.post("/v1/checkout")
    def checkout(body: CheckoutRequest):
        return payments().checkout(str(body.installationId), body.accountToken, str(body.requestId), body.planId)

    @application.get("/v1/payments/{checkout_id}")
    def payment_status(checkout_id: UUID, installationId: UUID, request: Request,
                       authorization: Annotated[str | None, Header()] = None):
        if set(request.query_params.keys()) != {"installationId"} or len(request.query_params.getlist("installationId")) != 1:
            raise HTTPException(status_code=422, detail="Invalid request fields")
        if not authorization or not authorization.startswith("Bearer "):
            raise Unauthorized("Missing recovery code")
        return payments().status(str(checkout_id), authorization[7:], str(installationId))

    @application.post("/v1/restore")
    def restore(body: RestoreRequest):
        return payments().restore(body.accountToken, str(body.installationId))

    @application.post("/v1/webhooks/yookassa", status_code=204)
    def webhook(body: Notification):
        payments().webhook(body.event, body.object)
        return Response(status_code=204)

    return application


app = create_app()
