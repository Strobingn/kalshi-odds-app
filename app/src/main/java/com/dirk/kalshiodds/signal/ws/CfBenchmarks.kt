package com.dirk.kalshiodds.signal.ws

import org.json.JSONObject

/**
 * Kalshi authenticated WebSocket channel `cfbenchmarks_value`.
 *
 * Docs: https://docs.kalshi.com/websockets/cfbenchmarks-value
 * Auth is the same API-key handshake as the trade socket. Index ids are
 * BRTI (BTC), ETHUSD_RTI, SOLUSD_RTI. `market_tickers` are not valid here.
 *
 * `msg.data` is a raw CF JSON string. The documented example is
 * {"type":"value","id":"BRTI","time":1710000000123,"value":"68000.12"}.
 * `avg_60s_data` is always present. `last_60s_windowed_average_15min`
 * is present only in the final minute before :00/:15/:30/:45.
 */
object CfBenchmarks {
    const val CHANNEL = "cfbenchmarks_value"
    const val INDEX_LIST_TYPE = "cfbenchmarks_value_indexlist"
    const val BTC = "BRTI"
    const val ETH = "ETHUSD_RTI"
    const val SOL = "SOLUSD_RTI"
    val INDEX_IDS: List<String> = listOf(BTC, ETH, SOL)
    const val FRESH_MS = 15_000L

    /**
     * 0.3.43 Live settlement explainer. In the final minute the channel adds `last_60s_windowed_average_15min`
     * (docs.kalshi.com/websockets/cfbenchmarks-value, checked 2026-10-09): Kalshi settles on that 60 s average, so
     * the app shows it next to spot. Null outside the final minute.
     */
    fun settlementExplainer(tick: Tick): String? {
        val fm = tick.finalMinuteAverage ?: return null
        return String.format(
            java.util.Locale.US,
            "Settles on the 60 s average: %,.2f over %d prints (spot %,.2f) — one late print moves it only ~1/60",
            fm.value, fm.windowSize, tick.value
        )
    }

    data class Average(
        val value: Double,
        val windowSize: Int,
        val windowStartMs: Long,
        val windowEndExclusiveMs: Long
    )

    data class Tick(
        val indexId: String,
        val receivedAtMs: Long,
        val sourceTimeMs: Long?,
        val value: Double,
        val avg60s: Average,
        val finalMinuteAverage: Average?,
        val localReceivedAtMs: Long
    ) {
        fun ageMs(nowMs: Long): Long = (nowMs - localReceivedAtMs).coerceAtLeast(0L)
        fun fresh(nowMs: Long): Boolean = ageMs(nowMs) <= FRESH_MS
    }

    enum class Status { LIVE, STALE, FALLBACK, OFF }

    data class FeedStatus(val status: Status, val ageMs: Long?, val detail: String)

    fun subscribeJson(id: Int, indexIds: List<String> = INDEX_IDS): String {
        val ids = indexIds.joinToString(",") { "\"$it\"" }
        return """{"id":$id,"cmd":"subscribe","params":{"channels":["$CHANNEL"],"index_ids":[$ids]}}"""
    }

    fun parse(raw: String, localReceivedAtMs: Long): Tick? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optString("type") != CHANNEL) return null
        val msg = root.optJSONObject("msg") ?: return null
        val indexId = msg.optString("index_id")
        if (indexId.isBlank()) return null
        val receivedAt = msg.optLong("received_at", 0L)
        val data = parseData(msg.optString("data")) ?: return null
        val avg = parseAverage(msg.optJSONObject("avg_60s_data")) ?: return null
        val finalMinute = parseAverage(msg.optJSONObject("last_60s_windowed_average_15min"))
        return Tick(
            indexId = indexId,
            receivedAtMs = receivedAt,
            sourceTimeMs = data.second,
            value = data.first,
            avg60s = avg,
            finalMinuteAverage = finalMinute,
            localReceivedAtMs = localReceivedAtMs
        )
    }

    fun indexForSeries(series: String): String? = when (series.uppercase()) {
        "KXBTC15M", "KXBTCD" -> BTC
        "KXETH15M", "KXETHD" -> ETH
        "KXSOL15M", "KXSOLD" -> SOL
        else -> null
    }

    fun coinOf(indexId: String): String = when (indexId) {
        BTC -> "BTC"
        ETH -> "ETH"
        SOL -> "SOL"
        else -> indexId
    }

    private fun parseData(raw: String): Pair<Double, Long?>? {
        if (raw.isBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val value = o.optString("value").toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val time = if (o.has("time")) o.optLong("time") else null
        return value to time
    }

    private fun parseAverage(o: JSONObject?): Average? {
        if (o == null) return null
        val value = o.optString("value").toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        return Average(
            value = value,
            windowSize = o.optInt("window_size"),
            windowStartMs = o.optLong("window_start_ts_ms"),
            windowEndExclusiveMs = o.optLong("window_end_ts_exclusive")
        )
    }
}

/**
 * Latest CF ticks. Coinbase is the fallback only when this feed is stale.
 * Never stores an API key.
 */
class CfBenchmarkStore {
    private val ticks = HashMap<String, CfBenchmarks.Tick>()
    /** 0.3.49: CF tick → event-driven eval for that coin's markets. Called outside the lock. */
    @Volatile var onTick: ((String) -> Unit)? = null

    @Synchronized
    fun accept(tick: CfBenchmarks.Tick) {
        val prev = ticks[tick.indexId]
        if (prev != null && tick.sourceTimeMs != null && prev.sourceTimeMs != null &&
            tick.sourceTimeMs < prev.sourceTimeMs
        ) {
            return
        }
        ticks[tick.indexId] = tick
    }

    fun acceptAndNotify(tick: CfBenchmarks.Tick) {
        accept(tick)
        runCatching { onTick?.invoke(tick.indexId) }
    }

    @Synchronized
    fun latest(indexId: String): CfBenchmarks.Tick? = ticks[indexId]

    fun status(indexId: String, nowMs: Long, coinbaseAvailable: Boolean): CfBenchmarks.FeedStatus {
        val tick = latest(indexId)
        if (tick == null) {
            return if (coinbaseAvailable) {
                CfBenchmarks.FeedStatus(CfBenchmarks.Status.FALLBACK, null, "CF off — Coinbase fallback")
            } else {
                CfBenchmarks.FeedStatus(CfBenchmarks.Status.OFF, null, "CF off")
            }
        }
        val age = tick.ageMs(nowMs)
        return if (tick.fresh(nowMs)) {
            CfBenchmarks.FeedStatus(CfBenchmarks.Status.LIVE, age, "CF ${tick.indexId} live · ${age / 1000}s")
        } else if (coinbaseAvailable) {
            CfBenchmarks.FeedStatus(CfBenchmarks.Status.FALLBACK, age, "CF stale ${age / 1000}s — Coinbase fallback")
        } else {
            CfBenchmarks.FeedStatus(CfBenchmarks.Status.STALE, age, "CF stale ${age / 1000}s")
        }
    }

    /** Spot used for z and distance. Final-minute average when the docs include it. */
    fun settlementPrice(indexId: String, nowMs: Long, secondsRemaining: Double?): Double? {
        val tick = latest(indexId) ?: return null
        if (!tick.fresh(nowMs)) return null
        val finalMinute = secondsRemaining != null && secondsRemaining <= 60.0
        if (finalMinute) tick.finalMinuteAverage?.value?.let { return it }
        return tick.value
    }
}
