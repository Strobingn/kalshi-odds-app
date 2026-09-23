package com.dirk.kalshiodds.signal.config

/**
 * Shared defaults for the v0.2.0 predictability stack.
 * Analysis / alerts only — never used to place orders.
 */
object SignalConstants {
    /** Last ~3 minutes before settlement uses the late-window blend. */
    const val LATE_TTE_MS = 180_000L

    /** Short BTC→ETH/SOL (and reverse) lead window. */
    const val LEAD_WINDOW_MS = 3_000L

    /** Follower lookback; leader move is measured just before this. */
    const val LAG_OFFSET_MS = 400L

    const val MIN_CALIBRATION_SAMPLES = 20
    const val RELIABILITY_BINS = 10

    const val DEFAULT_MIN_CONFIDENCE = 0.45
    const val DEFAULT_MIN_LIQUIDITY = 500.0
    const val DEFAULT_MAX_SPREAD_CENTS = 8.0
    const val DEFAULT_HIDE_WEAK = true

    const val SCORECARD_ROLLING_DAYS = 7

    /** Size drop (contracts) treated as a cancel spike. */
    const val CANCEL_SPIKE_SIZE = 8.0

    const val DEPTH_NEAR_CENTS = 3.0
    const val DEPTH_FAR_CENTS = 15.0
}
