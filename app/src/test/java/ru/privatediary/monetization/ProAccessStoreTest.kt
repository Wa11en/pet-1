package ru.privatediary.monetization

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.monetization.SignedEntitlementVerifier

class ProAccessStoreTest {
    private val storage = MemoryStorage()
    private val verifier = SignedEntitlementVerifier(Base64.getEncoder().encodeToString(keys.public.encoded), "ru.privatediary")
    private val store = ProAccessStore(storage, verifier)

    @Test fun readingMissingAccessNeverCreatesSecrets() = runBlocking {
        assertEquals(ProAccessSnapshot(), store.readAccess(NOW))
        assertNull(store.recoveryCode())
        assertEquals(0, storage.writes)
    }

    @Test fun retryKeepsInstallationRecoverySecretAndRequestIdUntilExplicitCancel() = runBlocking {
        val first = store.checkoutIdentity()
        assertEquals(first, store.checkoutIdentity())
        assertEquals(first, ProAccessStore(storage, verifier).checkoutIdentity())
        assertTrue(isRecoveryCode(first.accountToken))
        assertFalse(first.toString().contains(first.accountToken))
        val session = CheckoutSession("683661dd-9c72-417f-9642-7b6af753b0bd", "https://yoomoney.ru/checkout/1")
        store.saveCheckout(session)
        assertEquals(session, store.readAccess(NOW).pendingCheckout)
        store.clearPendingCheckout()
        val afterCancel = store.checkoutIdentity()
        assertEquals(first.installationId, afterCancel.installationId)
        assertEquals(first.accountToken, afterCancel.accountToken)
        assertNotEquals(first.requestId, afterCancel.requestId)
    }

    @Test fun onlySignedActiveGrantActivatesAccessAndExpiryIsRecheckedOffline() = runBlocking {
        val identity = store.checkoutIdentity()
        assertFalse(store.acceptEntitlement("paid", NOW))
        assertNull(store.readAccess(NOW).activeUntilMillis)
        val grant = token(identity.installationId)
        assertTrue(store.acceptEntitlement(grant, NOW))
        assertEquals(EXPIRY, store.readAccess(NOW).activeUntilMillis)
        assertNull(store.readAccess(EXPIRY).activeUntilMillis)
        assertNull(ProAccessStore(storage, SignedEntitlementVerifier("", "ru.privatediary")).readAccess(NOW).activeUntilMillis)
    }

    @Test fun incorrectRecoveryNeverReplacesExistingAccount() = runBlocking {
        val original = store.checkoutIdentity()
        val newCode = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
        val restore = store.restoreIdentity(newCode)
        assertEquals(original.accountToken, store.recoveryCode())
        assertFalse(store.acceptRestoredEntitlement(restore, "forged", NOW))
        assertEquals(original.accountToken, store.recoveryCode())
        assertTrue(store.acceptRestoredEntitlement(restore, token(restore.installationId), NOW))
        assertEquals(newCode, store.recoveryCode())
        assertEquals(EXPIRY, store.readAccess(NOW).activeUntilMillis)
    }

    @Test fun damagedRecordFailsWithoutSilentReset() = runBlocking {
        storage.bytes = "bad-json".toByteArray()
        try { store.checkoutIdentity(); fail("Expected corrupt storage failure") } catch (_: org.json.JSONException) { }
        assertEquals(0, storage.writes)
        assertEquals("bad-json", storage.bytes?.toString(Charsets.UTF_8))
    }

    @Test fun restoreOnNewDeviceSavesNoUnverifiedAccountSecret() = runBlocking {
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 11 })
        val identity = store.restoreIdentity(code)
        assertNull(store.recoveryCode())
        assertFalse(store.readAccess(NOW).hasAccount)
        assertTrue(store.acceptRestoredEntitlement(identity, token(identity.installationId), NOW))
        assertEquals(code, store.recoveryCode())
    }

    @Test fun backendPublicFixturePassesThroughPaymentClientAndEncryptedCacheBoundary() = runBlocking {
        val fixture = Properties().apply {
            requireNotNull(ProAccessStoreTest::class.java.getResourceAsStream("/monetization/backend-license.properties")).use { load(it) }
        }
        val fixtureVerifier = SignedEntitlementVerifier(fixture.getProperty("publicKeyBase64"), "ru.privatediary")
        val identity = store.checkoutIdentity()
        val installation = fixture.getProperty("installationId")
        storage.bytes = JSONObject(requireNotNull(storage.bytes).toString(Charsets.UTF_8))
            .put("installationId", installation).toString().toByteArray()
        val boundIdentity = identity.copy(installationId = installation)
        val grant = fixture.getProperty("token")
        val response = PaymentResponse(200, JSONObject().put("status", "paid").put("entitlement", grant).toString())
        val client = SbpCheckoutClient("https://payments.example.org", true, object : PaymentTransport {
            override suspend fun execute(request: PaymentRequest): PaymentResponse {
                assertEquals(boundIdentity.accountToken, request.bearerToken)
                assertTrue(request.url.endsWith("?installationId=$installation"))
                return response
            }
        })
        val result = client.checkPayment(boundIdentity, "683661dd-9c72-417f-9642-7b6af753b0bd")
        val fixtureStore = ProAccessStore(storage, fixtureVerifier)
        val issuedAt = fixture.getProperty("issuedAt").toLong()
        val expiresAt = fixture.getProperty("expiresAt").toLong()
        assertTrue(fixtureStore.acceptEntitlement(requireNotNull(result.entitlement), issuedAt))
        assertEquals(expiresAt, ProAccessStore(storage, fixtureVerifier).readAccess(issuedAt).activeUntilMillis)
        assertNull(fixtureStore.readAccess(expiresAt).activeUntilMillis)
    }

    private class MemoryStorage : ProSecretStorage {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf(); writes++ }
    }

    private fun token(installationId: String): String {
        val json = JSONObject().put("version", 1).put("aud", "ru.privatediary").put("sub", installationId)
            .put("plan", "pro_monthly").put("issuedAt", NOW - 1_000).put("expiresAt", EXPIRY)
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
        val signingInput = "v1.$payload"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private); update(signingInput.toByteArray(Charsets.US_ASCII))
        }.sign()
        return "$signingInput.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
    }

    companion object {
        private const val NOW = 1_790_000_000_000L
        private const val EXPIRY = NOW + 30L * 24 * 60 * 60 * 1000
        private val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair() }
    }
}
