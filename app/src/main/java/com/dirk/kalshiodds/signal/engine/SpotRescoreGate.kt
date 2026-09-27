package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.external.ExternalSnapshot
import kotlin.math.abs

/**
 * Streamed spot prints arrive many times a second. A watched market is
 * re-scored on a print only when [minIntervalMs] passed since its last
 * spot-triggered re-score **and** spot moved ≥ [minMoveBp] since that
 * market's last score of any kind (Kalshi tick, book, or spot).
 *
 * At ~50% annual vol BTC moves 1 bp in roughly a second, so this is about
 * 1–2 re-scores a second per live market — cheap next to the book path, and
 * still well inside the few seconds Kalshi takes to reprice.
 */
object SpotRescoreGate {

    /** Watched tickers whose underlying is [asset] ("BTC" / "ETH" / "SOL"). */
    fun tickersFor(asset: String, watched: Collection<String>): List<String> {
        val key = asset.uppercase()
        return watched.filter { ExternalSnapshot.assetOf(it) == key }
    }

    /**
     * True (and stamps [lastByTicker]) when [ticker] should be re-scored for
     * a print at [spot]. [lastScoredSpot] is only read once the time gate
     * passes; null (never scored with spot) counts as moved.
     */
    fun shouldRescore(
        ticker: String,
        spot: Double,
        nowMs: Long,
        lastByTicker: MutableMap<String, Long>,
        minIntervalMs: Long = SignalConstants.SPOT_RESCORE_MIN_INTERVAL_MS,
        minMoveBp: Double = SignalConstants.SPOT_RESCORE_MIN_MOVE_BP,
        lastScoredSpot: () -> Double?
    ): Boolean {
        if (!spot.isFinite() || spot <= 0.0) return false
        val last = lastByTicker[ticker]
        if (last != null && nowMs - last < minIntervalMs) return false
        val ref = lastScoredSpot()?.takeIf { it.isFinite() && it > 0.0 }
        if (ref != null && abs(spot / ref - 1.0) * 10_000.0 < minMoveBp) return false
        lastByTicker[ticker] = nowMs
        return true
    }
}
