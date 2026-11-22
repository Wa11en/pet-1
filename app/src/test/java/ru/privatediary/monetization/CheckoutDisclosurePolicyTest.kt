package ru.privatediary.monetization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutDisclosurePolicyTest {
    private val terms = "https://publisher.ru/legal/pro"
    private val refunds = "https://publisher.ru/legal/refunds"
    private val seller = "ИП Петров Пётр Петрович"

    @Test fun requiresSellerAndBothPublicDocuments() {
        assertTrue(CheckoutDisclosurePolicy.isConfigured(seller, terms, refunds))
        assertFalse(CheckoutDisclosurePolicy.isConfigured("", terms, refunds))
        assertFalse(CheckoutDisclosurePolicy.isConfigured("  ", terms, refunds))
        assertFalse(CheckoutDisclosurePolicy.isConfigured(seller, "", refunds))
        assertFalse(CheckoutDisclosurePolicy.isConfigured(seller, terms, ""))
        assertFalse(CheckoutDisclosurePolicy.isConfigured("$seller\nOther seller", terms, refunds))
        assertFalse(CheckoutDisclosurePolicy.isConfigured("x".repeat(301), terms, refunds))
    }

    @Test fun acceptsPublicHttpsDocumentsWithoutNetworkAccess() {
        listOf(terms, refunds, "https://legal.publisher.ru/", "HTTPS://PUBLISHER.RU/terms", "https://publisher.ru/legal/%D1%83%D1%81%D0%BB%D0%BE%D0%B2%D0%B8%D1%8F")
            .forEach { assertTrue(it, CheckoutDisclosurePolicy.isAllowedLegalUrl(it)) }
    }

    @Test fun rejectsCredentialsPortsQueriesFragmentsAndNonHttpsSchemes() {
        listOf(
            "http://publisher.ru/pro", "file:///terms", "javascript:alert(1)", "//publisher.ru/pro",
            "https://name:secret@publisher.ru/pro", "https://publisher.ru:443/pro", "https://publisher.ru:8443/pro",
            "https://publisher.ru/pro?", "https://publisher.ru/pro?tracking=1", "https://publisher.ru/pro#", "https://publisher.ru/pro#terms",
            "https://publisher.ru/pro\n", "https://publisher.ru/pro%0A", "https://publisher.ru/pro%00", " https://publisher.ru/pro",
            "https://publisher.ru/pro path", "https://publisher.ru\\@another.ru/pro", "https://publisher.ru/%not-escaped",
        ).forEach { assertFalse(it, CheckoutDisclosurePolicy.isAllowedLegalUrl(it)) }
    }

    @Test fun rejectsExampleDomainsLoopbackAndNonPublicHosts() {
        listOf(
            "https://localhost/terms", "https://legal.localhost/terms", "https://127.0.0.1/terms", "https://[::1]/terms",
            "https://192.168.1.2/terms", "https://legal/terms", "https://publisher.local/terms", "https://publisher.internal/terms",
            "https://publisher.test/terms", "https://publisher.invalid/terms", "https://publisher.example/terms",
            "https://example.com/terms", "https://legal.example.com/terms", "https://example.net/terms", "https://example.org/terms",
            "https://publisher.ru./terms", "https://-publisher.ru/terms", "https://publisher-.ru/terms",
        ).forEach { assertFalse(it, CheckoutDisclosurePolicy.isAllowedLegalUrl(it)) }
    }
}
