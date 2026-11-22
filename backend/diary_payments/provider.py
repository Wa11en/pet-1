"""Small, server-only YooKassa adapter for a single manually renewed SBP plan.

The adapter deliberately has no recurring-payment or saved-payment-method API.
It does not accept prices, customer data or URLs from an Android request.
"""

from __future__ import annotations

import base64
import ipaddress
import json
import re
import uuid
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


API_BASE = "https://api.yookassa.ru/v3/payments"
MAX_RESPONSE_BYTES = 1024 * 1024
_UNAVAILABLE = "Payment provider is unavailable"
_PAYMENT_DOMAINS = ("yoomoney.ru", "yookassa.ru", "pay.nspk.ru")
_HOST_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")


class ProviderUnavailable(Exception):
    """A provider/configuration failure, intentionally free of remote details."""


def _https_host(value: str) -> str:
    """Parse a conservative HTTPS URL without normalizing away unsafe input."""
    try:
        if (
            not isinstance(value, str)
            or not value
            or len(value) > 8192
            or any(ord(char) <= 32 or ord(char) == 127 for char in value)
            or "\\" in value
        ):
            raise ValueError
        parsed = urlsplit(value)
        host = parsed.hostname
        if (
            parsed.scheme != "https"
            or not host
            or parsed.username is not None
            or parsed.password is not None
            or parsed.port not in (None, 443)
            or parsed.fragment
            or host.endswith(".")
            or len(host) > 253
            or not host.isascii()
            or not all(_HOST_LABEL.fullmatch(label) for label in host.split("."))
        ):
            raise ValueError
        return host.lower()
    except (ValueError, TypeError, UnicodeError):
        raise ProviderUnavailable(_UNAVAILABLE) from None


def validated_payment_url(value: str) -> str:
    """Allow only the HTTPS checkout domains accepted by the mobile client.

    Matching uses a DNS-label boundary: ``yoomoney.ru.attacker.example`` and
    ``fake-yoomoney.ru`` are not accepted. Credentials, fragments, backslashes,
    control characters, IP literals and nonstandard ports are rejected.
    """
    host = _https_host(value)
    if not any(host == domain or host.endswith("." + domain) for domain in _PAYMENT_DOMAINS):
        raise ProviderUnavailable(_UNAVAILABLE)
    return value


def _validated_return_url(value: str) -> str:
    host = _https_host(value)
    # A merchant-owned public HTTPS page should explain how to return to the
    # app. Refuse local/literal hosts; never fetch or resolve this URL here.
    if "." not in host or host.endswith((".localhost", ".local", ".internal", ".lan", ".home")):
        raise ProviderUnavailable(_UNAVAILABLE)
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return value
    raise ProviderUnavailable(_UNAVAILABLE)


def _canonical_uuid(value: str) -> str:
    try:
        if not isinstance(value, str) or str(uuid.UUID(value)) != value.lower():
            raise ValueError
        return value.lower()
    except (ValueError, TypeError, AttributeError):
        raise ProviderUnavailable(_UNAVAILABLE) from None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # urllib turns this into HTTPError; Authorization can never be sent to
        # a location supplied by a provider response.
        return None


class YooKassaProvider:
    """Direct HTTPS API client; ``opener`` is an in-process test seam only."""

    def __init__(
        self,
        shop_id: str,
        secret: str,
        return_url: str,
        *,
        opener=None,
        timeout: float = 10,
    ) -> None:
        if (
            not isinstance(shop_id, str)
            or not shop_id.isascii()
            or not shop_id.isdecimal()
            or not isinstance(secret, str)
            or not secret
            or any(ord(char) <= 32 or ord(char) == 127 for char in secret)
            or not isinstance(timeout, (int, float))
            or isinstance(timeout, bool)
            or not 0 < timeout <= 30
        ):
            raise ProviderUnavailable(_UNAVAILABLE)
        self._return_url = _validated_return_url(return_url)
        self._authorization = "Basic " + base64.b64encode(
            (shop_id + ":" + secret).encode("utf-8")
        ).decode("ascii")
        self._opener = opener if opener is not None else build_opener(_NoRedirect())
        self._timeout = timeout

    def create(self, checkout_id: str) -> dict[str, Any]:
        checkout_id = _canonical_uuid(checkout_id)
        payload = {
            "amount": {"value": "99.00", "currency": "RUB"},
            "capture": True,
            "payment_method_data": {"type": "sbp"},
            "confirmation": {"type": "redirect", "return_url": self._return_url},
            "save_payment_method": False,
            "description": "Private Diary Pro — 1 month",
            "metadata": {"checkout_id": checkout_id},
        }
        result = self._request("POST", API_BASE, payload, checkout_id)
        confirmation = result.get("confirmation")
        if not isinstance(confirmation, dict):
            raise ProviderUnavailable(_UNAVAILABLE)
        validated_payment_url(confirmation.get("confirmation_url"))
        return result

    def get(self, payment_id: str) -> dict[str, Any]:
        return self._request("GET", API_BASE + "/" + _canonical_uuid(payment_id))

    def _request(
        self,
        method: str,
        url: str,
        payload: dict[str, Any] | None = None,
        idempotence_key: str | None = None,
    ) -> dict[str, Any]:
        headers = {"Authorization": self._authorization, "Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if idempotence_key is not None:
            headers["Idempotence-Key"] = idempotence_key
        request = Request(url, data=data, headers=headers, method=method)
        try:
            # Reads are short, retryable verification calls. A restore checks
            # at most 13 active payments in batches of four, so use a smaller
            # socket timeout than the checkout-creation timeout.
            timeout = min(self._timeout, 3) if method == "GET" else self._timeout
            with self._opener.open(request, timeout=timeout) as response:
                status = response.getcode()
                if (
                    not isinstance(status, int)
                    or isinstance(status, bool)
                    or not 200 <= status < 300
                    or response.geturl() != url
                ):
                    raise ProviderUnavailable(_UNAVAILABLE)
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                if not isinstance(raw, bytes) or len(raw) > MAX_RESPONSE_BYTES:
                    raise ProviderUnavailable(_UNAVAILABLE)
                result = json.loads(raw.decode("utf-8"))
                if not isinstance(result, dict):
                    raise ProviderUnavailable(_UNAVAILABLE)
                return result
        except HTTPError as error:
            error.close()
            raise ProviderUnavailable(_UNAVAILABLE) from None
        except Exception:
            # Never echo API bodies, exception text, Authorization or a URL
            # from an error response into logs or the public API.
            raise ProviderUnavailable(_UNAVAILABLE) from None
