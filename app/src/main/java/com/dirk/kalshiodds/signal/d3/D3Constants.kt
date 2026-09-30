package com.dirk.kalshiodds.signal.d3

import java.time.ZoneId

/**
 * Research rule D3 — resting maker bid on Kalshi Bitcoin **daily 5 PM ET**
 * (`KXBTCD` event that closes at 17:00 America/New_York).
 *
 * Window: 14:00–16:00 ET. Favourite ask 85–97¢. Post-only limit bid.
 * Maker fee comes from the series fee schedule (not a hardcoded $0).
 */
object D3Constants {
    const val SERIES = "KXBTCD"
    const val STRATEGY_SOURCE = "D3 daily favourite"
    const val CLOSE_HOUR_ET = 17
    const val WINDOW_START_HOURS_BEFORE_CLOSE = 3
    const val WINDOW_END_HOURS_BEFORE_CLOSE = 1
    const val FAV_ASK_MIN = 0.85
    const val FAV_ASK_MAX = 0.97
    const val TICK = 0.01
    const val IMPROVE_SPREAD = 0.02
    const val LIVE_ALL_IN_CAP_USD = 10.0
    const val HALF_KELLY = 0.5

    /** Static research evidence shown in-app. */
    const val HIST_FILLS = 297
    const val HIST_WINS = 284
    const val HIST_LOSSES = 13
    const val HIST_NET_USD = 137.66
    const val BTC_WINS = 115
    const val BTC_LOSSES = 4
    const val BTC_NET_USD = 70.52
    const val FORWARD_TEST_STARTED = "2026-09-30"

    val HISTORICAL_WIN_RATE: Double = HIST_WINS.toDouble() / HIST_FILLS.toDouble()

    val ET: ZoneId = ZoneId.of("America/New_York")

    fun isTicker(ticker: String): Boolean =
        ticker.trim().uppercase().startsWith(SERIES)

    fun isEventTicker(eventTicker: String?): Boolean {
        val u = eventTicker?.trim()?.uppercase().orEmpty()
        return u.startsWith(SERIES)
    }
}
