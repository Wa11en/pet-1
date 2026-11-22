package ru.privatediary.domain.monetization

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Verifies the publisher's offline Pro grant. The only accepted wire format is
 * v1.base64url(JSON UTF-8).base64url(RSA-SHA256 PKCS#1 v1.5 signature).
 *
 * A checkout redirect, a locally stored flag, or a provider's client response is not a grant.
 * The payment service issues a token only after independently confirming payment. This class
 * needs neither Android nor a network connection; only the public verification key is bundled.
 */
class SignedEntitlementVerifier(
    private val publicKeyBase64: String,
    private val expectedAudience: String,
) {
    private val publicKey: RSAPublicKey? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        parsePublicKey()
    }

    val isConfigured: Boolean
        get() = expectedAudience.isNotBlank() && expectedAudience.length <= MAX_AUDIENCE_LENGTH && publicKey != null

    /** Returns only a valid, unexpired grant for this installation; all other input fails closed. */
    fun verify(token: String, installationId: String, nowMillis: Long): VerifiedEntitlement? {
        if (!isConfigured || nowMillis < 0 || !isCanonicalUuid(installationId)) return null
        if (token.length !in 1..MAX_TOKEN_LENGTH) return null
        return try {
            verifySignedToken(token, installationId, nowMillis)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun verifySignedToken(token: String, installationId: String, nowMillis: Long): VerifiedEntitlement? {
        val parts = token.split('.', limit = 4)
        if (parts.size != 3 || parts[0] != "v1") return null
        val payloadBytes = decodeCanonicalBase64Url(parts[1]) ?: return null
        if (payloadBytes.size !in 1..MAX_PAYLOAD_BYTES) return null
        val signatureBytes = decodeCanonicalBase64Url(parts[2]) ?: return null
        val key = publicKey ?: return null
        if (signatureBytes.size != (key.modulus.bitLength() + 7) / 8) return null
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update("v1.${parts[1]}".toByteArray(Charsets.US_ASCII))
        if (!verifier.verify(signatureBytes)) return null

        // Untrusted JSON is interpreted only after verifying its original encoded bytes.
        val payload = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payloadBytes)).toString()
        val claims = StrictGrantJson.parse(payload)
        if (claims.keys != CLAIM_NAMES) return null
        if (claims["version"] != 1L || claims["aud"] != expectedAudience ||
            claims["sub"] != installationId || claims["plan"] != PLAN_ID
        ) return null
        val issuedAt = claims["issuedAt"] as? Long ?: return null
        val expiresAt = claims["expiresAt"] as? Long ?: return null
        if (issuedAt < 0 || expiresAt <= issuedAt || expiresAt - issuedAt > MAX_GRANT_DURATION_MILLIS) return null
        if (nowMillis >= expiresAt) return null
        if (issuedAt > nowMillis && issuedAt - nowMillis > ALLOWED_CLOCK_SKEW_MILLIS) return null
        return VerifiedEntitlement(expectedAudience, installationId, PLAN_ID, issuedAt, expiresAt)
    }

    private fun parsePublicKey(): RSAPublicKey? {
        val encoded = publicKeyBase64.trim()
        if (encoded.isEmpty() || encoded.length > MAX_PUBLIC_KEY_LENGTH) return null
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            if (Base64.getEncoder().encodeToString(bytes) != encoded) return null
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)) as? RSAPublicKey
                ?: return null
            key.takeIf { it.modulus.bitLength() in 3072..8192 }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeCanonicalBase64Url(value: String): ByteArray? {
        if (value.isEmpty() || value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) {
            return null
        }
        val decoded = Base64.getUrlDecoder().decode(value)
        return decoded.takeIf { Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value }
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        value.length == 36 && UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        const val PLAN_ID = "pro_monthly"
        private const val MAX_TOKEN_LENGTH = 8192
        private const val MAX_PAYLOAD_BYTES = 4096
        private const val MAX_PUBLIC_KEY_LENGTH = 2048
        private const val MAX_AUDIENCE_LENGTH = 256
        private const val MAX_GRANT_DURATION_MILLIS = 400L * 24 * 60 * 60 * 1000
        private val CLAIM_NAMES = setOf("version", "aud", "sub", "plan", "issuedAt", "expiresAt")
    }
}
