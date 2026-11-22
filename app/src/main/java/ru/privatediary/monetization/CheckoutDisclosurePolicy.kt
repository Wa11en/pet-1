package ru.privatediary.monetization

import java.net.URI
import java.util.Locale

/** Checks public checkout disclosures locally; it never resolves a host or makes a request. */
object CheckoutDisclosurePolicy {
    fun isSellerNameConfigured(value: String): Boolean =
        value.trim().length in 1..300 && value.none { it.isISOControl() }

    fun isConfigured(sellerLegalName: String, termsUrl: String, refundUrl: String): Boolean =
        isSellerNameConfigured(sellerLegalName) && isAllowedLegalUrl(termsUrl) && isAllowedLegalUrl(refundUrl)

    fun isAllowedLegalUrl(value: String): Boolean = runCatching {
        if (value.length !in 1..8192 || value.any { it.isISOControl() || it.isWhitespace() }) return false
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null || uri.path.orEmpty().any { it.isISOControl() }) return false

        // Publisher documents need a public DNS name, not a loopback/IP or an example placeholder.
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { !it.matches(HOST_LABEL) } || labels.last().all(Char::isDigit)) return false
        if (RESERVED_SUFFIXES.any { host == it || host.endsWith(".$it") }) return false
        if (EXAMPLE_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        true
    }.getOrDefault(false)

    private val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val RESERVED_SUFFIXES = setOf("localhost", "local", "internal", "test", "invalid", "example")
    private val EXAMPLE_HOSTS = setOf("example.com", "example.net", "example.org")
}
