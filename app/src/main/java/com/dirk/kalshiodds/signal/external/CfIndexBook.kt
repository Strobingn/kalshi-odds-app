package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.ws.KalshiWsMessages

/**
 * Latest CF Benchmarks real-time index per asset, from Kalshi's
 * authenticated `cfbenchmarks_value` WebSocket channel. This is the index
 * the 15-minute crypto markets settle on (60 s average before close), so it
 * replaces Coinbase as the settlement proxy whenever it is fresh.
 *
 * Thread-safe; fed by the Live signals WebSocket, read by scoring.
 */
class CfIndexBook {
    data class CfIndex(
        val asset: String,
        val indexId: String,
        val value: Double,
        /** Wall clock when the update arrived. */
        val updatedMs: Long,
        val sourceTsMs: Long?,
        val avg60: Double?,
        /** Running final-minute average; kept between updates. */
        val finalMinuteAvg: Double?,
        val finalMinuteTicks: Int?,
        /** Wall clock of the last update that carried [finalMinuteAvg]. */
        val finalMinuteUpdatedMs: Long?
    )

    private val lock = Any()
    private val latest = HashMap<String, CfIndex>()

    fun onUpdate(v: KalshiWsMessages.Parsed.IndexValue, nowMs: Long) {
        val asset = assetOf(v.indexId) ?: return
        synchronized(lock) {
            val prev = latest[asset]
            latest[asset] = CfIndex(
                asset = asset,
                indexId = v.indexId,
                value = v.value,
                updatedMs = nowMs,
                sourceTsMs = v.sourceTsMs,
                avg60 = v.avg60,
                finalMinuteAvg = v.finalMinuteAvg ?: prev?.finalMinuteAvg,
                finalMinuteTicks = if (v.finalMinuteAvg != null) v.finalMinuteTicks else prev?.finalMinuteTicks,
                finalMinuteUpdatedMs = if (v.finalMinuteAvg != null) nowMs else prev?.finalMinuteUpdatedMs
            )
        }
    }

    /** Latest value if it arrived within [maxAgeMs], else null (fall back to Coinbase). */
    fun fresh(asset: String, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): CfIndex? = synchronized(lock) {
        latest[asset.uppercase()]?.takeIf { nowMs - it.updatedMs in 0..maxAgeMs }
    }

    /**
     * The running settlement average for the window closing at [closeMs]:
     * only when we are inside its final minute and the last final-minute
     * update is fresh and belongs to that minute.
     */
    fun finalMinuteAverage(asset: String, closeMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Double? {
        if (nowMs <= closeMs - FINAL_MINUTE_MS || nowMs >= closeMs) return null
        val cur = fresh(asset, nowMs, maxAgeMs) ?: return null
        val at = cur.finalMinuteUpdatedMs ?: return null
        if (at <= closeMs - FINAL_MINUTE_MS || at > closeMs) return null
        if (nowMs - at > maxAgeMs) return null
        return cur.finalMinuteAvg
    }

    fun clear() = synchronized(lock) { latest.clear() }

    companion object {
        const val MAX_AGE_MS = 5_000L
        const val FINAL_MINUTE_MS = 60_000L

        /** Settlement indices for the BTC / ETH / SOL 15-minute series. */
        val INDEX_IDS = listOf("BRTI", "ETHUSD_RTI", "SOLUSD_RTI")

        /**
         * Residual noise (log units) between this feed and the settlement
         * value: it is the settlement index itself, so only rounding and
         * feed timing remain. Coinbase uses 0.5–1.1 bp
         * ([com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue.indexNoiseLog]).
         */
        const val INDEX_NOISE_LOG = 0.1e-4

        fun assetOf(indexId: String): String? = when (indexId.uppercase()) {
            "BRTI" -> "BTC"
            "ETHUSD_RTI" -> "ETH"
            "SOLUSD_RTI" -> "SOL"
            else -> null
        }
    }
}
