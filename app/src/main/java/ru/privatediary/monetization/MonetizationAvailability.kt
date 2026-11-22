package ru.privatediary.monetization

/** Keep incomplete offers out of a first release while preserving existing purchases. */
object MonetizationAvailability {
    fun showPro(state: ProScreenState, nowMillis: Long): Boolean =
        state.hasAccount || state.hasPendingCheckout ||
            (state.activeUntilMillis?.let { it > nowMillis } == true) ||
            (state.paymentsConfigured && CheckoutDisclosurePolicy.isConfigured(
                state.sellerLegalName, state.termsUrl, state.refundUrl))
}
