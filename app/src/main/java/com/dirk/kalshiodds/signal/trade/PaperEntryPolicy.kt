package com.dirk.kalshiodds.signal.trade

/**
 * Execution-quality guard for automatic paper experiments.
 *
 * This is deliberately a price rule, not a bankroll or position-size cap:
 * automatic tracks may still use all visible depth with synthetic credit.
 * At 90 cents or more, however, the most a winning binary can return before
 * fees is under 10 cents per contract, so the app does not call that a
 * scalp/AI entry.
 */
object PaperEntryPolicy {
    /** Automatic entries below 10¢ are lottery-ticket exposure, not a scalp. */
    const val MIN_AUTOMATIC_ENTRY_PRICE_INCLUSIVE = 0.10
    const val MAX_AUTOMATIC_ENTRY_PRICE_EXCLUSIVE = 0.90

    fun allowsAutomaticEntry(price: Double): Boolean =
        price.isFinite() &&
            price >= MIN_AUTOMATIC_ENTRY_PRICE_INCLUSIVE &&
            price < MAX_AUTOMATIC_ENTRY_PRICE_EXCLUSIVE

    fun skipMessage(price: Double): String {
        val cents = String.format(java.util.Locale.US, "%.1f", price * 100.0)
        return when {
            !price.isFinite() -> "Auto-paper skip — price is not usable"
            price < MIN_AUTOMATIC_ENTRY_PRICE_INCLUSIVE ->
                "Auto-paper skip ${cents}¢ — below the 10¢ minimum is lottery-ticket exposure"
            else -> "Auto-paper skip ${cents}¢ — 90¢+ leaves under 10¢ gross upside before fees"
        }
    }
}
