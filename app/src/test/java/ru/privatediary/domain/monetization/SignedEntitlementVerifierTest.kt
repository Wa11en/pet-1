package ru.privatediary.domain.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.Properties

class SignedEntitlementVerifierTest {
    private val verifier = SignedEntitlementVerifier(publicKey, AUDIENCE)

    @Test fun independentBackendFixtureVerifiesWithItsPublicKey() {
        // Synthetic public-only fixture, produced independently by the Python payment service.
        val fixture = Properties().apply {
            requireNotNull(SignedEntitlementVerifierTest::class.java.getResourceAsStream("/monetization/backend-license.properties"))
                .use { load(it) }
        }
        val backendVerifier = SignedEntitlementVerifier(fixture.getProperty("publicKeyBase64"), AUDIENCE)
        val token = fixture.getProperty("token")
        val installation = fixture.getProperty("installationId")
        val issuedAt = fixture.getProperty("issuedAt").toLong()
        val expiresAt = fixture.getProperty("expiresAt").toLong()
        val grant = requireNotNull(backendVerifier.verify(token, installation, issuedAt))
        assertEquals(issuedAt, grant.issuedAtMillis)
        assertEquals(expiresAt, grant.expiresAtMillis)
        assertNull(backendVerifier.verify(token, installation, expiresAt))
        assertNull(verifier.verify(token, installation, issuedAt))
    }

    @Test fun validGrantWorksOfflineAndContainsAuthenticatedClaims() {
        assertTrue(verifier.isConfigured)
        val grant = requireNotNull(verifier.verify(sign(payload()), INSTALLATION, NOW))
        assertEquals(AUDIENCE, grant.audience)
        assertEquals(INSTALLATION, grant.installationId)
        assertEquals("pro_monthly", grant.planId)
        assertEquals(NOW, grant.issuedAtMillis)
        assertEquals(EXPIRES, grant.expiresAtMillis)
        assertTrue(grant.isActiveAt(NOW))
        assertTrue(grant.isActiveAt(EXPIRES - 1))
        assertFalse(grant.isActiveAt(EXPIRES))
        assertFalse(grant.isActiveAt(-1))
    }

    @Test fun expirationIsExclusiveAndIsNotExtendedByClockSkew() {
        val token = sign(payload())
        assertNotNull(verifier.verify(token, INSTALLATION, EXPIRES - 1))
        assertNull(verifier.verify(token, INSTALLATION, EXPIRES))
        assertNull(verifier.verify(token, INSTALLATION, EXPIRES + 1))
    }

    @Test fun onlySmallFutureClockSkewIsAccepted() {
        val withinSkew = sign(payload(issued = NOW + 300_000))
        val grant = requireNotNull(verifier.verify(withinSkew, INSTALLATION, NOW))
        assertTrue(grant.isActiveAt(NOW))
        assertFalse(grant.isActiveAt(NOW - 1))
        assertNull(verifier.verify(sign(payload(issued = NOW + 300_001)), INSTALLATION, NOW))
        assertNull(verifier.verify(sign(payload()), INSTALLATION, -1))
    }

    @Test fun changedPayloadAndChangedSignatureDoNotGrantAccess() {
        val token = sign(payload())
        val parts = token.split('.')
        val alteredPayload = base64Url(payload(expires = EXPIRES + 1).toByteArray())
        assertNull(verifier.verify("v1.$alteredPayload.${parts[2]}", INSTALLATION, NOW))
        val signature = Base64.getUrlDecoder().decode(parts[2]).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertNull(verifier.verify("v1.${parts[1]}.${base64Url(signature)}", INSTALLATION, NOW))
        assertNull(verifier.verify(sign(payload(), unrelatedKeys), INSTALLATION, NOW))
    }

    @Test fun foreignInstallationAudienceAndPlanAreRejectedEvenWhenSigned() {
        listOf(
            payload().replace(INSTALLATION, OTHER_INSTALLATION),
            payload().replace(AUDIENCE, "ru.other.app"),
            payload().replace("pro_monthly", "pro_lifetime"),
            payload().replace("\"version\":1", "\"version\":2"),
        ).forEach { assertNull(verifier.verify(sign(it), INSTALLATION, NOW)) }
        assertNull(verifier.verify(sign(payload()), OTHER_INSTALLATION, NOW))
        assertNull(SignedEntitlementVerifier(publicKey, "ru.other.app").verify(sign(payload()), INSTALLATION, NOW))
    }

    @Test fun installationMustBeACanonicalUuid() {
        listOf("", "not-a-uuid", "1-1-1-1-1", INSTALLATION.uppercase()).forEach { installation ->
            val signed = sign(payload().replace(INSTALLATION, installation))
            assertNull(verifier.verify(signed, installation, NOW))
        }
    }

    @Test fun missingInvalidWeakAndNonRsaConfigurationAlwaysFailsClosed() {
        val weakKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val ecKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val candidates = listOf("", " ", "not base64", publicKey.dropLast(1), "A".repeat(2049),
            Base64.getEncoder().encodeToString(weakKey.public.encoded),
            Base64.getEncoder().encodeToString(ecKey.public.encoded))
        candidates.forEach { key ->
            val unavailable = SignedEntitlementVerifier(key, AUDIENCE)
            assertFalse(unavailable.isConfigured)
            assertNull(unavailable.verify(sign(payload()), INSTALLATION, NOW))
        }
        assertFalse(SignedEntitlementVerifier(publicKey, "").isConfigured)
    }

    @Test fun wireFormatRejectsPaddingWhitespaceOversizedAndExtraSegments() {
        val token = sign(payload())
        val parts = token.split('.')
        listOf("", "success", token + ".extra", token + ".", "v2.${parts[1]}.${parts[2]}",
            "v1.${parts[1]}=.${parts[2]}", "v1.${parts[1]}.${parts[2]}=", " $token", "$token\n",
            "v1..${parts[2]}", "v1.${parts[1]}.", "v1.!.!", "x".repeat(8193),
            "v1.${parts[1]}.${base64Url(byteArrayOf(1, 2))}").forEach {
            assertNull(verifier.verify(it, INSTALLATION, NOW))
        }
        assertNull(verifier.verify(sign(" ".repeat(4097)), INSTALLATION, NOW))
    }

    @Test fun schemaRejectsUnknownMissingAndDuplicateFields() {
        val json = payload()
        listOf(
            json.dropLast(1) + ",\"extra\":1}",
            json.replace("\"version\":1,", ""),
            json.replace("\"version\":1", "\"version\":1,\"version\":1"),
            json.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            json + "{}",
            "[$json]",
            json.replace("\"sub\":", "\"unknown\":"),
            json.replace("\"version\":1,", "version:1,"),
            json.dropLast(1) + ",}",
        ).forEach { assertNull(verifier.verify(sign(it), INSTALLATION, NOW)) }
    }

    @Test fun numericClaimsMustBeExactJsonIntegersWithNoCoercion() {
        val json = payload()
        listOf("1.0", "1e0", "\"1\"", "true", "null", "01", "+1", "{}", "[]").forEach { value ->
            assertNull(verifier.verify(sign(json.replace("\"version\":1", "\"version\":$value")), INSTALLATION, NOW))
        }
        listOf("\"$NOW\"", "$NOW.0", "1e12", "9223372036854775808", "-9223372036854775809").forEach { value ->
            assertNull(verifier.verify(sign(json.replace("\"issuedAt\":$NOW", "\"issuedAt\":$value")), INSTALLATION, NOW))
        }
        assertNull(verifier.verify(sign(json.replace("\"aud\":\"$AUDIENCE\"", "\"aud\":1")), INSTALLATION, NOW))
    }

    @Test fun invalidDurationsAndNegativeOrOverflowTimestampsAreRejected() {
        listOf(
            payload(issued = -1),
            payload(expires = NOW),
            payload(expires = NOW - 1),
            payload(expires = -1),
            payload(expires = NOW + 400L * 24 * 60 * 60 * 1000 + 1),
            payload(issued = 0, expires = Long.MAX_VALUE),
            payload(issued = Long.MIN_VALUE, expires = Long.MAX_VALUE),
        ).forEach { assertNull(verifier.verify(sign(it), INSTALLATION, NOW)) }
        assertNotNull(verifier.verify(sign(payload(expires = NOW + 400L * 24 * 60 * 60 * 1000)), INSTALLATION, NOW))
        val nearMax = sign(payload(issued = Long.MAX_VALUE - 100, expires = Long.MAX_VALUE))
        assertNotNull(verifier.verify(nearMax, INSTALLATION, Long.MAX_VALUE - 100))
        assertNull(verifier.verify(nearMax, INSTALLATION, 0))
    }

    @Test fun authenticatedJsonStillRequiresStrictUtf8AndCorrectEscapes() {
        assertNull(verifier.verify(sign(byteArrayOf(0xC3.toByte(), 0x28)), INSTALLATION, NOW))
        listOf(
            payload().replace(AUDIENCE, "ru.\nprivatediary"),
            payload().replace(AUDIENCE, "ru.\\x70rivatediary"),
            payload().replace(AUDIENCE, "ru.\\u00xzprivatediary"),
            payload().replace(AUDIENCE, "ru.\\ud800privatediary"),
        ).forEach { assertNull(verifier.verify(sign(it), INSTALLATION, NOW)) }
        val escapedAudience = payload().replace(AUDIENCE, "ru.\\u0070rivatediary")
        assertNotNull(verifier.verify(sign(escapedAudience), INSTALLATION, NOW))
        assertNotNull(verifier.verify(sign(" \t\r\n${payload()}\n"), INSTALLATION, NOW))
    }

    private fun payload(issued: Long = NOW, expires: Long = EXPIRES): String =
        """{"version":1,"aud":"$AUDIENCE","sub":"$INSTALLATION","plan":"pro_monthly","issuedAt":$issued,"expiresAt":$expires}"""

    private fun sign(json: String, keys: KeyPair = signingKeys): String = sign(json.toByteArray(Charsets.UTF_8), keys)

    private fun sign(payload: ByteArray, keys: KeyPair = signingKeys): String {
        val encoded = base64Url(payload)
        val input = "v1.$encoded"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private)
        signer.update(input.toByteArray(Charsets.US_ASCII))
        return "$input.${base64Url(signer.sign())}"
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private const val AUDIENCE = "ru.privatediary"
        private const val INSTALLATION = "b688ac13-88dc-4934-9da7-e409e2f7c4c5"
        private const val OTHER_INSTALLATION = "1f17348d-c5ee-432c-9369-c05f318be182"
        private const val NOW = 1_795_348_800_000L
        private const val EXPIRES = NOW + 30L * 24 * 60 * 60 * 1000
        private val signingKeys: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair() }
        private val unrelatedKeys: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair() }
        private val publicKey: String by lazy { Base64.getEncoder().encodeToString(signingKeys.public.encoded) }
    }
}
