package ru.privatediary.domain.monetization

/** A currently valid Pro grant authenticated with the publisher's signing key. */
class VerifiedEntitlement internal constructor(
    val audience: String,
    val installationId: String,
    val planId: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
) {
    /** Recheck the expiry whenever displaying a feature, including while the app stays open. */
    fun isActiveAt(nowMillis: Long): Boolean =
        nowMillis >= 0 && nowMillis < expiresAtMillis &&
            (issuedAtMillis <= nowMillis || issuedAtMillis - nowMillis <= ALLOWED_CLOCK_SKEW_MILLIS)
}

internal const val ALLOWED_CLOCK_SKEW_MILLIS = 5L * 60 * 1000
