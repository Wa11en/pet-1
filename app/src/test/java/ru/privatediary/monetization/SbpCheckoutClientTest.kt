package ru.privatediary.monetization

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SbpCheckoutClientTest {
    private val identity = CheckoutIdentity(
        "c02c7bee-e64c-45ce-ad2d-120939fb5307",
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 4 }),
        "c71ac543-b96d-4b5e-b68b-b6768127314c",
    )
    private val checkoutId = "683661dd-9c72-417f-9642-7b6af753b0bd"

    @Test fun constructorsAndUnconfiguredCallsNeverAccessTransport() = runBlocking {
        val transport = FakeTransport()
        val invalid = listOf("", "http://pay.example", "https://person:secret@pay.example", "https://pay.example?key=secret", "https://pay.example/#fragment")
        invalid.forEach { url ->
            val client = SbpCheckoutClient(url, true, transport)
            assertFalse(client.isConfigured)
            expectFailure(PaymentFailure.NOT_CONFIGURED) { client.createCheckout(identity) }
        }
        val offline = SbpCheckoutClient("https://pay.example", false, transport)
        expectFailure(PaymentFailure.NOT_CONFIGURED) { offline.restore(identity) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun checkoutSendsOnlyExplicitPaymentFieldsAndFixedPlan() = runBlocking {
        val transport = FakeTransport(checkoutResponse())
        val client = SbpCheckoutClient("https://pay.example/", true, transport)
        assertTrue(transport.requests.isEmpty())
        val session = client.createCheckout(identity)
        assertEquals(checkoutId, session.checkoutId)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://pay.example/v1/checkout", request.url)
        assertNull(request.bearerToken)
        val body = JSONObject(requireNotNull(request.body))
        assertEquals(setOf("installationId", "accountToken", "requestId", "planId"), body.keys().asSequence().toSet())
        assertEquals(identity.installationId, body.getString("installationId"))
        assertEquals(identity.accountToken, body.getString("accountToken"))
        assertEquals(identity.requestId, body.getString("requestId"))
        assertEquals("pro_monthly", body.getString("planId"))
        assertFalse(request.toString().contains(identity.accountToken))
    }

    @Test fun statusDoesNotPutRecoverySecretInUrlAndDoesNotAutomaticallyRepeat() = runBlocking {
        val transport = FakeTransport(PaymentResponse(200, """{"status":"paid","entitlement":"signed-token"}"""))
        val client = SbpCheckoutClient("https://pay.example", true, transport)
        val result = client.checkPayment(identity, checkoutId)
        assertEquals(PaymentStatus.PAID, result.status)
        assertEquals("signed-token", result.entitlement)
        assertEquals(1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals(identity.accountToken, request.bearerToken)
        assertNull(request.body)
        assertFalse(request.url.contains(identity.accountToken))
        assertEquals("https://pay.example/v1/payments/$checkoutId?installationId=${identity.installationId}", request.url)
    }

    @Test fun restoreSendsOnlyIdentityAndCode() = runBlocking {
        val transport = FakeTransport(PaymentResponse(200, """{"entitlement":null}"""))
        assertNull(SbpCheckoutClient("https://pay.example", true, transport).restore(identity))
        val request = transport.requests.single()
        val body = JSONObject(requireNotNull(request.body))
        assertEquals(setOf("installationId", "accountToken"), body.keys().asSequence().toSet())
        assertNull(request.bearerToken)
    }

    @Test fun settledButExpiredPaymentIsNotAnEntitlement() = runBlocking {
        val transport = FakeTransport(PaymentResponse(200, """{"status":"paid","entitlement":null}"""))
        val result = SbpCheckoutClient("https://pay.example", true, transport).checkPayment(identity, checkoutId)
        assertEquals(PaymentStatus.PAID, result.status)
        assertNull(result.entitlement)
    }

    @Test fun rejectsUnexpectedPricePeriodCheckoutUrlStatusAndOversizedResponses() = runBlocking {
        val original = checkoutResponse().body
        listOf(
            original.replace("99.00", "199.00"),
            original.replace("month", "year"),
            original.replace("https://yoomoney.ru/checkout/1", "https://evil.example/steal"),
        ).forEach { body ->
            expectFailure(PaymentFailure.INVALID_RESPONSE) {
                SbpCheckoutClient("https://pay.example", true, FakeTransport(PaymentResponse(200, body))).createCheckout(identity)
            }
        }
        expectFailure(PaymentFailure.INVALID_RESPONSE) {
            SbpCheckoutClient("https://pay.example", true, FakeTransport(PaymentResponse(200, """{"status":"success","entitlement":null}""")))
                .checkPayment(identity, checkoutId)
        }
        expectFailure(PaymentFailure.INVALID_RESPONSE) {
            SbpCheckoutClient("https://pay.example", true, FakeTransport(PaymentResponse(200, "x".repeat(32 * 1024 + 1)))).restore(identity)
        }
        expectFailure(PaymentFailure.SERVER) {
            SbpCheckoutClient("https://pay.example", true, FakeTransport(PaymentResponse(302, original))).createCheckout(identity)
        }
    }

    @Test fun browserUrlAllowlistRejectsImpersonationCredentialsAndOtherSchemes() {
        listOf("https://yoomoney.ru/pay", "https://checkout.yookassa.ru/pay", "https://pay.nspk.ru/pay", "https://bank.pay.nspk.ru/pay")
            .forEach { assertTrue(it, PaymentUrlPolicy.isAllowed(it)) }
        listOf("http://yoomoney.ru/pay", "https://yoomoney.ru.evil.example/pay", "https://evil.yoomoney.ru@evil.example/pay",
            "https://user:secret@yoomoney.ru/pay", "https://yoomoney.ru:8443/pay", "javascript:alert(1)", "https://yoomoney.ru/pay#secret")
            .forEach { assertFalse(it, PaymentUrlPolicy.isAllowed(it)) }
    }

    @Test fun acceptsProviderUrlUpToTheSameLimitAsBackend() = runBlocking {
        val base = "https://yoomoney.ru/pay?token="
        val url = base + "a".repeat(8192 - base.length)
        assertTrue(PaymentUrlPolicy.isAllowed(url))
        assertFalse(PaymentUrlPolicy.isAllowed(url + "a"))
        val transport = FakeTransport(PaymentResponse(200, JSONObject(checkoutResponse().body).put("paymentUrl", url).toString()))
        assertEquals(url, SbpCheckoutClient("https://pay.example", true, transport).createCheckout(identity).paymentUrl)
    }

    private fun checkoutResponse() = PaymentResponse(200,
        """{"checkoutId":"$checkoutId","paymentUrl":"https://yoomoney.ru/checkout/1","amount":"99.00","currency":"RUB","period":"month"}""")

    private suspend fun expectFailure(reason: PaymentFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (expected: PaymentException) { assertEquals(reason, expected.reason) }
    }

    private class FakeTransport(private val response: PaymentResponse = PaymentResponse(500, "")) : PaymentTransport {
        val requests = mutableListOf<PaymentRequest>()
        override suspend fun execute(request: PaymentRequest): PaymentResponse { requests += request; return response }
    }
}
