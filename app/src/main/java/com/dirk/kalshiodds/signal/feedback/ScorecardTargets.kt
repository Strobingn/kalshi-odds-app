package com.dirk.kalshiodds.signal.feedback

/**
 * Scorecard sample floors for the Bitcoin-only (KXBTC15M) app.
 * Headline “enough data” and 4-hour-slot minimums live here so UI,
 * honest scorecard, and tests share one source of truth.
 */
object ScorecardTargets {
    /** Settled BTC signals before the honest scorecard treats numbers as real. */
    const val MIN_SETTLED_BTC_SIGNALS = 50

    /** Minimum settled picks in a 4-hour ET slot before that bucket is trusted. */
    const val MIN_PER_SLOT_SAMPLES = 5

    fun settledTarget(): Int = MIN_SETTLED_BTC_SIGNALS
    fun perSlotMinimum(): Int = MIN_PER_SLOT_SAMPLES
}
