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
    const val MAX_AUTOMATIC_ENTRY_PRICE_EXCLUSIVE = 0.90

    fun allowsAutomaticEntry(price: Double): Boolean =
        price.isFinite() && price > 0.0 && price < MAX_AUTOMATIC_ENTRY_PRICE_EXCLUSIVE

    fun skipMessage(price: Double): String =
        "Auto-paper skip ${String.format(java.util.Locale.US, \"%.1f\", price * 100.0)}¢ — 90¢+ leaves under 10¢ gross upside before fees"
}
