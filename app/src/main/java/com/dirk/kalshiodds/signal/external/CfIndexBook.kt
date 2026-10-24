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

    /** Per asset, the index once a second (the last value of each second), newest last, [HISTORY_S] kept. */
    private val history = HashMap<String, ArrayDeque<Pair<Long, Double>>>()

    fun onUpdate(v: KalshiWsMessages.Parsed.IndexValue, nowMs: Long) {
        val asset = assetOf(v.indexId) ?: return
        synchronized(lock) {
            if (v.value.isFinite() && v.value > 0.0) {
                val ring = history.getOrPut(asset) { ArrayDeque() }
                val sec = nowMs / 1000L
                if (ring.isNotEmpty() && ring.last().first == sec) ring.removeLast()
                ring.addLast(sec to v.value)
                while (ring.isNotEmpty() && ring.first().first < sec - HISTORY_S) ring.removeFirst()
            }
            val prev = latest[asset]
            latest[asset] = CfIndex(
                asset = asset,
                indexId = v.indexId,
                value = v.value,
                updatedMs = nowMs,
                sourceTsMs = v.sourceTsMs,
                // The 5 Hz frames may not carry the averages: keep the last one.
                avg60 = v.avg60 ?: prev?.avg60,
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

    /**
     * Std of 1-minute log returns of the index, from its 1-second changes over
     * the last [HISTORY_S] seconds (Kashi's estimator: RMS of 1 s returns,
     * scaled to a minute). Needs [MIN_RETURNS] one-second returns; seconds
     * the feed skipped are left out, not filled.
     */
    fun std1m(asset: String, nowMs: Long): Double? = synchronized(lock) {
        val ring = history[asset.uppercase()] ?: return null
        val from = nowMs / 1000L - STD_WINDOW_S
        var n = 0
        var ss = 0.0
        var prev: Pair<Long, Double>? = null
        for (p in ring) {
            if (p.first < from) continue
            val q = prev
            if (q != null && p.first - q.first == 1L) {
                val r = kotlin.math.ln(p.second / q.second)
                ss += r * r
                n++
            }
            prev = p
        }
        if (n < MIN_RETURNS) null else kotlin.math.sqrt(ss / n * 60.0)
    }

    /**
     * One-minute closes of the index, oldest first: the last value of every
     * finished minute that had one (a silent minute has no bar, like an
     * exchange candle), then the latest value as the bar still forming.
     */
    fun minuteCloses(asset: String, nowMs: Long): List<Double> = synchronized(lock) {
        val ring = history[asset.uppercase()] ?: return emptyList()
        val curMin = nowMs / 60_000L
        val out = ArrayList<Double>(64)
        var lastMin = Long.MIN_VALUE
        var lastVal = Double.NaN
        for (p in ring) {
            val m = p.first / 60L
            if (m >= curMin) break
            if (m != lastMin && lastMin != Long.MIN_VALUE) out += lastVal
            lastMin = m
            lastVal = p.second
        }
        if (lastMin != Long.MIN_VALUE) out += lastVal
        ring.lastOrNull()?.let { out += it.second }
        out
    }

    /** Mean ln(index) over [fromMs, toMs] from the 1-second history, or null with no sample. */
    fun meanLog(asset: String, fromMs: Long, toMs: Long): Double? = synchronized(lock) {
        val ring = history[asset.uppercase()] ?: return null
        val a = fromMs / 1000L
        val b = toMs / 1000L
        var n = 0
        var sum = 0.0
        for (p in ring) if (p.first in a..b) {
            sum += kotlin.math.ln(p.second)
            n++
        }
        if (n == 0) null else sum / n
    }

    /** Plain mean of the index over the seconds [fromMs, toMs] and how many seconds had a value; null with none. */
    fun mean(asset: String, fromMs: Long, toMs: Long): Pair<Double, Int>? = synchronized(lock) {
        val ring = history[asset.uppercase()] ?: return null
        val a = fromMs / 1000L
        val b = toMs / 1000L
        var n = 0
        var sum = 0.0
        for (p in ring) if (p.first in a..b) {
            sum += p.second
            n++
        }
        if (n == 0) null else (sum / n) to n
    }

    /**
     * One value a second for [seconds] seconds ending at the second of [toMs] (oldest first): the last value of each
     * second, NaN for a second with none.
     */
    fun series(asset: String, toMs: Long, seconds: Int): DoubleArray = synchronized(lock) {
        val out = DoubleArray(seconds) { Double.NaN }
        val ring = history[asset.uppercase()] ?: return out
        val last = toMs / 1000L
        val first = last - seconds + 1
        for (p in ring) if (p.first in first..last) out[(p.first - first).toInt()] = p.second
        out
    }

    /** The index's last value at or before the second of [atMs], or null. */
    fun valueAt(asset: String, atMs: Long): Double? = synchronized(lock) {
        val ring = history[asset.uppercase()] ?: return null
        val sec = atMs / 1000L
        ring.lastOrNull { it.first <= sec }?.takeIf { sec - it.first <= 5L }?.second
    }

    /** The one-second history of [asset], oldest first (epoch second, value): what is saved across app starts. */
    fun export(asset: String): List<Pair<Long, Double>> = synchronized(lock) {
        history[asset.uppercase()]?.toList().orEmpty()
    }

    /**
     * Put back a saved one-second history (the app was closed or restarted): only seconds within [HISTORY_S] of
     * [nowMs] and older than anything already live are kept, so a live print always wins. Returns the seconds restored.
     */
    fun restore(asset: String, points: List<Pair<Long, Double>>, nowMs: Long): Int = synchronized(lock) {
        val ring = history.getOrPut(asset.uppercase()) { ArrayDeque() }
        val nowSec = nowMs / 1000L
        val firstLive = ring.firstOrNull()?.first ?: Long.MAX_VALUE
        val keep = points.asSequence()
            .filter { (sec, v) -> v.isFinite() && v > 0.0 && sec >= nowSec - HISTORY_S && sec <= nowSec && sec < firstLive }
            .sortedBy { it.first }
            .distinctBy { it.first }
            .toList()
        for (i in keep.indices.reversed()) ring.addFirst(keep[i])
        keep.size
    }

    fun clear() = synchronized(lock) {
        latest.clear()
        history.clear()
    }

    companion object {
        const val MAX_AGE_MS = 5_000L

        /** One-second index history kept per asset: the 60 one-minute bars [KalshiSpot] needs, and a margin. */
        const val HISTORY_S = 3_780L

        /** Seconds of 1-second returns [std1m] reads (Kashi's 900). */
        const val STD_WINDOW_S = 900L

        /** One-second returns [std1m] needs before it answers (Kashi's 120). */
        const val MIN_RETURNS = 120
        const val FINAL_MINUTE_MS = 60_000L

        /** Settlement indices for the BTC / ETH / SOL 15-minute series. */
        val INDEX_IDS = listOf("BRTI", "ETHUSD_RTI", "SOLUSD_RTI")

        /** The other coins' 15-minute settlement indices (the fleet's cross-asset scalper), subscribed on their own. */
        val ALT_INDEX_IDS = listOf("XRPUSD_RTI", "DOGEUSD_RTI", "BNBUSD_RTI", "HYPEUSD_RTI")

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
            "XRPUSD_RTI", "XRPUSDRTI" -> "XRP"
            "DOGEUSD_RTI", "DOGEUSDRTI" -> "DOGE"
            "BNBUSD_RTI", "BNBUSDRTI" -> "BNB"
            "HYPEUSD_RTI", "HYPEUSDRTI" -> "HYPE"
            else -> null
        }

        /** The index id [assetOf] reads back as [asset]; null for a coin with no index. */
        fun indexIdOf(asset: String): String? = when (asset.uppercase()) {
            "BTC" -> "BRTI"
            "ETH" -> "ETHUSD_RTI"
            "SOL" -> "SOLUSD_RTI"
            else -> null
        }
    }
}
