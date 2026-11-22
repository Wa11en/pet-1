package ru.privatediary.monetization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetizationAvailabilityTest {
    @Test fun aFreshUnconfiguredInstallationHasNoDeadPurchaseOffer() {
        assertFalse(MonetizationAvailability.showPro(ProScreenState(), 100))
        assertFalse(MonetizationAvailability.showPro(ProScreenState(paymentsConfigured = true), 100))
    }

    @Test fun aConfiguredPaymentFlowNeedsTheSellerAndPublishedDocuments() {
        val state = ProScreenState(paymentsConfigured = true, sellerLegalName = "Publisher",
            termsUrl = "https://diary-owner.ru/terms", refundUrl = "https://diary-owner.ru/refunds")
        assertTrue(MonetizationAvailability.showPro(state, 100))
        assertFalse(MonetizationAvailability.showPro(state.copy(refundUrl = ""), 100))
        assertFalse(MonetizationAvailability.showPro(state.copy(paymentsConfigured = false), 100))
    }

    @Test fun existingPaymentHistoryRemainsAccessibleAfterConfigurationChanges() {
        assertTrue(MonetizationAvailability.showPro(ProScreenState(hasAccount = true), 100))
        assertTrue(MonetizationAvailability.showPro(ProScreenState(hasPendingCheckout = true), 100))
    }

    @Test fun accessIsVisibleThroughItsPaidExpiry() {
        val state = ProScreenState(activeUntilMillis = 101)
        assertTrue(MonetizationAvailability.showPro(state, 100))
        assertFalse(MonetizationAvailability.showPro(state, 101))
    }
}
