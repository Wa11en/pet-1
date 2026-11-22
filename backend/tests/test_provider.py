import base64
import io
import json
import unittest
from urllib.error import HTTPError, URLError
from urllib.request import Request

from diary_payments.provider import (
    API_BASE,
    MAX_RESPONSE_BYTES,
    ProviderUnavailable,
    YooKassaProvider,
    _NoRedirect,
    validated_payment_url,
)


CHECKOUT_ID = "3f23bd2d-7071-4b19-ab05-20e90d6bec0b"
PAYMENT_ID = "2f23bd2d-000f-5000-8000-20e90d6bec0b"
PAYMENT_URL = "https://yoomoney.ru/checkout/payments/v2/contract?orderId=test"
RETURN_URL = "https://merchant.example/payment/return"


class FakeResponse:
    def __init__(self, body, status=200, url=None):
        self.body = body
        self.status = status
        self.url = url
        self.read_limit = None
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.closed = True

    def getcode(self):
        return self.status

    def geturl(self):
        return self.url

    def read(self, size):
        self.read_limit = size
        return self.body[:size]


class FakeOpener:
    def __init__(self, body=None, *, status=200, response_url=None, error=None):
        if body is None:
            body = json.dumps({
                "id": PAYMENT_ID,
                "status": "pending",
                "confirmation": {"type": "redirect", "confirmation_url": PAYMENT_URL},
            }).encode()
        self.response = FakeResponse(body, status, response_url)
        self.error = error
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request, timeout))
        if self.error:
            raise self.error
        if self.response.url is None:
            self.response.url = request.full_url
        return self.response


def provider(opener):
    return YooKassaProvider("123456", "test_merchant_secret", RETURN_URL, opener=opener)


class ProviderTest(unittest.TestCase):
    def test_create_owns_price_sbp_and_manual_renewal_policy(self):
        opener = FakeOpener()
        result = provider(opener).create(CHECKOUT_ID)
        self.assertEqual(result["id"], PAYMENT_ID)
        request, timeout = opener.calls[0]
        self.assertEqual(timeout, 10)
        self.assertEqual(request.full_url, API_BASE)
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(json.loads(request.data), {
            "amount": {"value": "99.00", "currency": "RUB"},
            "capture": True,
            "payment_method_data": {"type": "sbp"},
            "confirmation": {"type": "redirect", "return_url": RETURN_URL},
            "save_payment_method": False,
            "description": "Private Diary Pro — 1 month",
            "metadata": {"checkout_id": CHECKOUT_ID},
        })
        self.assertEqual(request.get_header("Idempotence-key"), CHECKOUT_ID)
        expected_auth = "Basic " + base64.b64encode(b"123456:test_merchant_secret").decode()
        self.assertEqual(request.get_header("Authorization"), expected_auth)
        self.assertEqual(request.get_header("Content-type"), "application/json")
        self.assertTrue(opener.response.closed)

    def test_get_uses_pinned_endpoint_without_a_request_body(self):
        opener = FakeOpener(body=b'{"status":"succeeded"}')
        self.assertEqual(provider(opener).get(PAYMENT_ID), {"status": "succeeded"})
        request, timeout = opener.calls[0]
        self.assertEqual(3, timeout)
        self.assertEqual(request.full_url, API_BASE + "/" + PAYMENT_ID)
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.data)
        self.assertIsNone(request.get_header("Idempotence-key"))

    def test_bad_identifiers_never_reach_transport(self):
        for identifier in ("", "../payments", PAYMENT_ID + "/refunds", "https://attacker.example", None):
            for operation in ("get", "create"):
                with self.subTest(identifier=identifier, operation=operation):
                    opener = FakeOpener()
                    with self.assertRaises(ProviderUnavailable):
                        getattr(provider(opener), operation)(identifier)
                    self.assertFalse(opener.calls)

    def test_create_rejects_untrusted_or_missing_confirmation_url(self):
        for body in (
            {"confirmation": {"confirmation_url": "https://attacker.example/pay"}},
            {"confirmation": {"confirmation_url": "http://yoomoney.ru/pay"}},
            {"confirmation": {"confirmation_url": None}},
            {"confirmation": "https://yoomoney.ru/pay"},
            {},
        ):
            with self.subTest(body=body):
                with self.assertRaises(ProviderUnavailable):
                    provider(FakeOpener(body=json.dumps(body).encode())).create(CHECKOUT_ID)

    def test_response_is_bounded_and_closed(self):
        opener = FakeOpener(body=b" " * (MAX_RESPONSE_BYTES + 2))
        with self.assertRaises(ProviderUnavailable):
            provider(opener).get(PAYMENT_ID)
        self.assertEqual(opener.response.read_limit, MAX_RESPONSE_BYTES + 1)
        self.assertTrue(opener.response.closed)

    def test_invalid_responses_are_generic_failures(self):
        for body in (b"not json", b"\xff", b"[]", b"null", b"42", b'"secret"'):
            with self.subTest(body=body):
                with self.assertRaisesRegex(ProviderUnavailable, "^Payment provider is unavailable$"):
                    provider(FakeOpener(body=body)).get(PAYMENT_ID)

    def test_non_success_status_is_rejected_before_body_read(self):
        for status in (301, 302, 400, 401, 429, 500, 199, None, True):
            with self.subTest(status=status):
                opener = FakeOpener(status=status)
                with self.assertRaises(ProviderUnavailable):
                    provider(opener).get(PAYMENT_ID)
                self.assertIsNone(opener.response.read_limit)

    def test_provider_exceptions_never_echo_credentials_or_remote_body(self):
        for error in (
            HTTPError(API_BASE, 401, "test_merchant_secret", {}, io.BytesIO(b"sensitive body")),
            URLError("Authorization: Basic secret; sensitive body"),
            TimeoutError("test_merchant_secret"),
        ):
            with self.subTest(error=type(error).__name__):
                with self.assertRaises(ProviderUnavailable) as caught:
                    provider(FakeOpener(error=error)).get(PAYMENT_ID)
                self.assertEqual(str(caught.exception), "Payment provider is unavailable")
                self.assertTrue(caught.exception.__suppress_context__)
                self.assertIsNone(caught.exception.__cause__)

    def test_redirect_handler_refuses_all_redirects(self):
        handler = _NoRedirect()
        request = Request(API_BASE, headers={"Authorization": "Basic secret"})
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status):
                self.assertIsNone(handler.redirect_request(
                    request, None, status, "Found", {}, "https://attacker.example/steal"
                ))

    def test_production_opener_installs_redirect_block(self):
        client = YooKassaProvider("123456", "secret", RETURN_URL)
        self.assertTrue(any(isinstance(handler, _NoRedirect) for handler in client._opener.handlers))

    def test_changed_response_url_is_rejected_before_body_read(self):
        for url in ("https://attacker.example/steal", API_BASE + "/", "http://api.yookassa.ru/v3/payments"):
            with self.subTest(url=url):
                opener = FakeOpener(response_url=url)
                with self.assertRaises(ProviderUnavailable):
                    provider(opener).create(CHECKOUT_ID)
                self.assertIsNone(opener.response.read_limit)

    def test_payment_url_allowlist_accepts_dns_boundaries(self):
        for url in (
            PAYMENT_URL,
            "https://yookassa.ru/pay",
            "https://checkout.yookassa.ru:443/payment?id=one",
            "https://pay.nspk.ru/sbp/test",
            "https://sbp.pay.nspk.ru/test",
            "https://YOOMONEY.RU/pay",
        ):
            with self.subTest(url=url):
                self.assertEqual(validated_payment_url(url), url)

    def test_payment_url_allowlist_rejects_url_attacks(self):
        for url in (
            "http://yoomoney.ru/pay",
            "https://yoomoney.ru.attacker.example/pay",
            "https://attacker-yoomoney.ru/pay",
            "https://yoomoney.ru@attacker.example/pay",
            "https://attacker@yoomoney.ru/pay",
            "https://attacker:secret@yoomoney.ru/pay",
            "https://yoomoney.ru:444/pay",
            "https://yoomoney.ru./pay",
            "https://yoomoney.ru\\@attacker.example/pay",
            "https://yoomoney.ru/pay#javascript:alert(1)",
            "https://yoomoney.ru/pay\nInjected: header",
            " https://yoomoney.ru/pay",
            "https://%79oomoney.ru/pay",
            "https://yоomoney.ru/pay",  # Cyrillic о.
            "https://127.0.0.1/pay",
            "https://[::1]/pay",
            "https://qr.nspk.ru/pay",  # Not part of the configured allowlist.
            "https://.yoomoney.ru/pay",
            "https://a..yoomoney.ru/pay",
            "https://yoomoney.ru:bad/pay",
            "//yoomoney.ru/pay",
            "", None,
        ):
            with self.subTest(url=url):
                with self.assertRaises(ProviderUnavailable):
                    validated_payment_url(url)

    def test_return_url_requires_public_https_dns_name(self):
        for url in (
            "http://merchant.example/return",
            "https://localhost/return",
            "https://checkout.localhost/return",
            "https://checkout.local/return",
            "https://127.0.0.1/return",
            "https://192.168.1.1/return",
            "https://10.0.0.1/return",
            "https://[::1]/return",
            "https://user:password@merchant.example/return",
            "https://merchant.example:8080/return",
            "private-diary://payment/return",
        ):
            with self.subTest(url=url):
                with self.assertRaises(ProviderUnavailable):
                    YooKassaProvider("123456", "secret", url, opener=FakeOpener())

    def test_missing_credentials_and_invalid_timeouts_fail_closed(self):
        for shop_id, secret, timeout in (
            ("", "secret", 10),
            ("123456", "", 10),
            ("123456\nHeader", "secret", 10),
            ("123456", "secret\r\nHeader", 10),
            ("123456", "secret", 0),
            ("123456", "secret", 31),
            ("123456", "secret", float("inf")),
            ("123456", "secret", float("nan")),
            ("123456", "secret", True),
        ):
            with self.subTest(shop_id=shop_id, timeout=timeout):
                with self.assertRaises(ProviderUnavailable):
                    YooKassaProvider(shop_id, secret, RETURN_URL, opener=FakeOpener(), timeout=timeout)


if __name__ == "__main__":
    unittest.main()
