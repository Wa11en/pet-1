from pathlib import Path
import unittest
import uuid

from fastapi.testclient import TestClient

from diary_payments.app import MAX_BODY_BYTES, create_app
from diary_payments.settings import Settings
from tests import test_core as fixtures


class ApiTests(fixtures.PaymentFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.client = TestClient(create_app(service=self.service), base_url="https://payments.example.org")
        self.addCleanup(self.client.close)

    def body(self, **changes):
        return {"installationId": self.installation, "accountToken": self.token,
                "requestId": str(uuid.uuid4()), "planId": "pro_monthly", **changes}

    def test_missing_merchant_configuration_returns_503(self):
        with TestClient(create_app(settings=Settings(database_path=Path(self.folder.name) / "unused.sqlite3")),
                        base_url="https://payments.example.org") as client:
            self.assertFalse(client.get("/health").json()["paymentsConfigured"])
            self.assertEqual(503, client.post("/v1/checkout", json=self.body()).status_code)
            self.assertFalse((Path(self.folder.name) / "unused.sqlite3").exists())

    def test_extra_diary_or_price_fields_are_rejected_without_echoing_secrets(self):
        for field in ("text", "email", "price", "amount", "currency", "save_payment_method"):
            with self.subTest(field=field):
                with TestClient(create_app(service=self.service), base_url="https://payments.example.org") as client:
                    response = client.post("/v1/checkout", json=self.body(**{field: "PRIVATE_DIARY_TEXT"}))
                self.assertEqual(422, response.status_code)
                self.assertNotIn("PRIVATE_DIARY_TEXT", response.text)
                self.assertNotIn(self.token, response.text)
        self.assertEqual([], self.provider.created)

    def test_http_is_rejected_before_reading_credentials(self):
        with TestClient(create_app(service=self.service), base_url="http://payments.example.org") as client:
            self.assertEqual(426, client.post("/v1/checkout", json=self.body()).status_code)

    def test_request_body_is_bounded(self):
        response = self.client.post("/v1/checkout", content=b"x" * (MAX_BODY_BYTES + 1))
        self.assertEqual(413, response.status_code)
        self.assertEqual([], self.provider.created)

    def test_invalid_recovery_code_does_not_get_reflected(self):
        secret = "sensitive-secret-value"
        response = self.client.post("/v1/restore", json={"installationId": self.installation, "accountToken": secret})
        self.assertEqual(422, response.status_code)
        self.assertNotIn(secret, response.text)

    def test_missing_bearer_cannot_read_status(self):
        checkout = self.checkout()
        response = self.client.get("/v1/payments/" + checkout["checkoutId"], params={"installationId": self.installation})
        self.assertEqual(401, response.status_code)
        self.assertEqual([], self.provider.fetched)

    def test_unknown_query_fields_and_duplicate_installation_are_rejected(self):
        checkout = self.checkout()
        url = "/v1/payments/" + checkout["checkoutId"]
        for query in ({"installationId": self.installation, "text": "private"},
                      [("installationId", self.installation), ("installationId", self.installation)]):
            response = self.client.get(url, params=query, headers={"Authorization": "Bearer " + self.token})
            self.assertEqual(422, response.status_code)

    def test_checkout_rate_limit_does_not_create_additional_payment(self):
        body = self.body()
        for _ in range(5):
            self.assertEqual(200, self.client.post("/v1/checkout", json=body).status_code)
        self.assertEqual(429, self.client.post("/v1/checkout", json=body).status_code)
        self.assertEqual(1, len(self.provider.created))

    def test_provider_errors_are_generic_and_do_not_grant_access(self):
        self.provider.timeout_once = True
        response = self.client.post("/v1/checkout", json=self.body())
        self.assertEqual(503, response.status_code)
        self.assertNotIn(self.token, response.text)
        self.assertEqual("no-store", response.headers["cache-control"])

    def test_end_to_end_checkout_confirm_restore(self):
        body = self.body()
        response = self.client.post("/v1/checkout", json=body)
        self.assertEqual(200, response.status_code)
        checkout = response.json()
        self.assertEqual("99.00", checkout["amount"])
        self.assertEqual("RUB", checkout["currency"])
        self.assertEqual("month", checkout["period"])
        payment = self.provider.paid(checkout)
        callback = self.client.post("/v1/webhooks/yookassa", json={"type": "notification", "event": "payment.succeeded",
            "object": {"id": payment["id"], "paid": False}})
        self.assertEqual(204, callback.status_code)
        result = self.client.get("/v1/payments/" + checkout["checkoutId"], params={"installationId": self.installation},
                                 headers={"Authorization": "Bearer " + self.token})
        self.assertEqual("paid", result.json()["status"])
        self.claims(result.json()["entitlement"])
        new_device = str(uuid.uuid4())
        restored = self.client.post("/v1/restore", json={"installationId": new_device, "accountToken": self.token})
        self.assertEqual(new_device, self.claims(restored.json()["entitlement"])["sub"])

    def test_no_admin_or_fake_activation_route_exists(self):
        for route in ("/v1/activate", "/v1/admin/pro", "/v1/fake/pay"):
            self.assertEqual(404, self.client.post(route, json={}).status_code)
