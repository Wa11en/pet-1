package ru.privatediary.monetization

import java.net.URI
import java.util.Base64
import java.util.UUID

/** Payment-only data. Diary content is deliberately absent from this boundary. */
data class CheckoutIdentity(val installationId: String, val accountToken: String, val requestId: String) {
    init {
        require(isCanonicalUuid(installationId))
        require(isRecoveryCode(accountToken))
        require(isCanonicalUuid(requestId))
    }

    override fun toString() = "CheckoutIdentity(installationId=$installationId, accountToken=<redacted>, requestId=$requestId)"
}

data class CheckoutSession(val checkoutId: String, val paymentUrl: String) {
    init {
        require(isCanonicalUuid(checkoutId))
        require(PaymentUrlPolicy.isAllowed(paymentUrl))
    }
}

enum class PaymentStatus { PENDING, PAID, CANCELED }

data class PaymentStatusResult(val status: PaymentStatus, val entitlement: String?) {
    override fun toString() = "PaymentStatusResult(status=$status, entitlement=<redacted>)"
}

data class ProAccessSnapshot(
    val activeUntilMillis: Long? = null,
    val hasAccount: Boolean = false,
    val pendingCheckout: CheckoutSession? = null,
)

data class ProScreenState(
    val paymentsConfigured: Boolean = false,
    val busy: Boolean = false,
    val activeUntilMillis: Long? = null,
    val hasAccount: Boolean = false,
    val hasPendingCheckout: Boolean = false,
    val recoveryCode: String? = null,
    val message: String? = null,
    val sellerLegalName: String = "",
    val termsUrl: String = "",
    val refundUrl: String = "",
) {
    override fun toString() = "ProScreenState(paymentsConfigured=$paymentsConfigured, busy=$busy, activeUntilMillis=$activeUntilMillis, hasAccount=$hasAccount, hasPendingCheckout=$hasPendingCheckout, recoveryCode=<redacted>, message=<redacted>)"
}

/** The browser may only open an HTTPS checkout at a known payment provider. */
object PaymentUrlPolicy {
    fun isAllowed(value: String): Boolean = runCatching {
        if (value.length !in 1..8192 || value.any { it.isISOControl() }) return false
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
            uri.fragment == null && (uri.port == -1 || uri.port == 443) &&
            (host == "yookassa.ru" || host.endsWith(".yookassa.ru") ||
                host == "yoomoney.ru" || host.endsWith(".yoomoney.ru") ||
                host == "pay.nspk.ru" || host.endsWith(".pay.nspk.ru"))
    }.getOrDefault(false)
}

internal fun isCanonicalUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value
}.getOrDefault(false)

internal fun isRecoveryCode(value: String): Boolean = runCatching {
    value.matches(Regex("[A-Za-z0-9_-]{43}")) &&
        Base64.getUrlDecoder().decode(value).size == 32 &&
        Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value
}.getOrDefault(false)
