package com.dirk.kalshiodds.signal.lastminute

/**
 * Last-fold walk-forward params (`research/last_minute/params.json`)
 * plus the $10 stake Dirk asked for in 0.3.16.
 *
 * Single constants file — [LastMinuteMath] / [LastMinuteStrategy] read only here.
 */
object LastMinuteConstants {
    const val K = 1.1
    const val ETA = 0.0001
    const val MARGIN_EV_PER_DOLLAR = 0.35
    const val MAX_STAKE_USD = 10.0
    const val TAKER_RATE = 0.07

    /** Final-minute length used by fair_p and Kalshi's 60 s BRTI average. */
    const val FINAL_MINUTE_SEC = 60

    /** Rolling 1-minute log-return window for sig_s (completed minutes only). */
    const val VOL_MINUTES = 60
    const val VOL_MIN_MINUTES = 30

    const val SERIES = "KXBTC15M"

    const val STRATEGY_SOURCE = "Last-minute strategy"
    const val AI_MODEL_BACKTEST_LABEL = "AI model (backtest: loses after fees)"
}
