package com.dirk.kalshiodds.decision

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.3.40 scalp parameters (PAPER ONLY). One set per coin; a small fixed grid runs as shadow paper
 * variants so the walk-forward tuner can compare them on the app's own realistic paper fills.
 */
data class ScalpParams(
    /** Entry: ask must be at least this far below fair after the entry fee (dollars). */
    val minGap: Double,
    /** Exit when bid − entry ≥ target (dollars per contract). */
    val target: Double,
    /** Exit when bid ≤ entry − stop. */
    val stop: Double,
    /** Exit when spot-implied fair falls this far below entry. */
    val turnDown: Double,
    /** Entry window: seconds left to close. */
    val tauMinS: Double,
    val tauMaxS: Double = 840.0
) {
    val id: String
        get() = String.format(
            Locale.US, "g%02d-t%02d-s%02d-d%02d-w%03d",
            Math.round(minGap * 100), Math.round(target * 100), Math.round(stop * 100),
            Math.round(turnDown * 100), Math.round(tauMinS)
        )

    fun label(): String = String.format(
        Locale.US, "gap ≥ %.0f¢ · target +%.0f¢ · stop −%.0f¢ · turn-down %.0f¢ · enter %d–%d min left",
        minGap * 100, target * 100, stop * 100, turnDown * 100, (tauMinS / 60).toInt(), (tauMaxS / 60).toInt()
    )

    companion object {
        const val LEGACY_ID = "legacy-v1"
        val COINS = listOf("BTC", "ETH", "SOL")

        /** Seed / fallback params (box research, see scalp-research/REPORT.md). */
        val DEFAULT = ScalpParams(minGap = 0.10, target = 0.08, stop = 0.06, turnDown = 0.10, tauMinS = 300.0)

        /** Small grid: 2 gaps × 2 exit profiles × 2 entry windows = 8 variants per coin. */
        val GRID: List<ScalpParams> = buildList {
            for (g in listOf(0.06, 0.10)) for (exit in listOf(Triple(0.04, 0.04, 0.06), Triple(0.08, 0.06, 0.10))) {
                for (w in listOf(180.0, 300.0)) add(ScalpParams(g, exit.first, exit.second, exit.third, w))
            }
        }

        fun byId(id: String): ScalpParams? = GRID.firstOrNull { it.id == id } ?: DEFAULT.takeIf { it.id == id }

        fun coinOf(ticker: String): String {
            val u = ticker.uppercase()
            return when {
                u.startsWith("KXSOL") || u.contains("SOL") -> "SOL"
                u.startsWith("KXETH") || u.contains("ETH") -> "ETH"
                else -> "BTC"
            }
        }
    }
}

/** Persisted tuner state: version, per-coin params, last run summary. */
data class ScalpTuneState(
    val version: Int = 0,
    val paramsByCoin: Map<String, String> = emptyMap(),
    val lastRunAtMs: Long = 0L,
    val closedAtLastRun: Int = 0,
    val trials: Int = 0,
    /** Per coin: current params' out-of-sample mean net ¢/contract and n (later block), or null. */
    val oosCentsByCoin: Map<String, Double> = emptyMap(),
    val oosNByCoin: Map<String, Int> = emptyMap(),
    val notes: List<String> = emptyList()
) {
    fun paramsFor(coin: String): ScalpParams = paramsByCoin[coin]?.let { ScalpParams.byId(it) } ?: ScalpParams.DEFAULT
    val versionLabel: String get() = "scalp-params v$version"
}

interface ScalpTuneStore {
    fun load(): ScalpTuneState
    fun save(state: ScalpTuneState)
}

class InMemoryScalpTuneStore(private var state: ScalpTuneState = ScalpTuneState()) : ScalpTuneStore {
    override fun load() = state
    override fun save(state: ScalpTuneState) { this.state = state }
}

class SharedPrefsScalpTuneStore(context: android.content.Context) : ScalpTuneStore {
    private val prefs = context.applicationContext.getSharedPreferences("kashi_scalp_tuner", android.content.Context.MODE_PRIVATE)

    override fun load(): ScalpTuneState = runCatching {
        fun map(key: String): Map<String, String> = prefs.getString(key, "").orEmpty().split(';').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        ScalpTuneState(
            version = prefs.getInt("version", 0),
            paramsByCoin = map("params"),
            lastRunAtMs = prefs.getLong("last_run", 0L),
            closedAtLastRun = prefs.getInt("closed_at_last", 0),
            trials = prefs.getInt("trials", 0),
            oosCentsByCoin = map("oos").mapNotNull { (k, v) -> v.toDoubleOrNull()?.let { k to it } }.toMap(),
            oosNByCoin = map("oos_n").mapNotNull { (k, v) -> v.toIntOrNull()?.let { k to it } }.toMap(),
            notes = prefs.getString("notes", "").orEmpty().split('\n').filter { it.isNotBlank() }
        )
    }.getOrDefault(ScalpTuneState())

    override fun save(state: ScalpTuneState) {
        fun join(m: Map<String, Any>) = m.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit()
            .putInt("version", state.version)
            .putString("params", join(state.paramsByCoin))
            .putLong("last_run", state.lastRunAtMs)
            .putInt("closed_at_last", state.closedAtLastRun)
            .putInt("trials", state.trials)
            .putString("oos", join(state.oosCentsByCoin))
            .putString("oos_n", join(state.oosNByCoin))
            .putString("notes", state.notes.joinToString("\n"))
            .apply()
    }
}

/**
 * Deterministic walk-forward tuner. Per coin: order windows by close time; fit on the earlier 60%,
 * verify on the later 40% (unseen by the fit). A new param set is adopted only when it beats the
 * current set on the later block, after both fees, with enough round trips on each side.
 */
object ScalpTuner {
    const val RETUNE_EVERY = 40
    const val FIT_FRACTION = 0.6
    const val MIN_FIT_N = 30
    const val MIN_OOS_N = 20

    private fun perContract(t: ScalpTrade) = t.netUsd!! / t.contracts.coerceAtLeast(1)

    fun tune(all: List<ScalpTrade>, cur: ScalpTuneState, nowMs: Long): ScalpTuneState {
        val closed = all.filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.variantId != ScalpParams.LEGACY_ID }
        var adopted = false
        val params = cur.paramsByCoin.toMutableMap()
        val oos = HashMap<String, Double>()
        val oosN = HashMap<String, Int>()
        val notes = ArrayList<String>()
        var trials = 0
        for (coin in ScalpParams.COINS) {
            val mine = closed.filter { it.coin == coin }
            val windows = mine.map { ScalpTicker.closeMs(it.ticker) ?: it.signalAtMs }.distinct().sorted()
            val current = cur.paramsFor(coin)
            if (windows.size < 5) {
                notes += "$coin: ${mine.size} round trips — not enough windows to tune; keeping ${current.id}"
                continue
            }
            val cut = windows[(windows.size * FIT_FRACTION).toInt().coerceIn(1, windows.size - 1)]
            fun block(early: Boolean) = mine.filter { ((ScalpTicker.closeMs(it.ticker) ?: it.signalAtMs) < cut) == early }
            val fit = block(true).groupBy { it.variantId }
            val later = block(false).groupBy { it.variantId }
            trials += fit.size
            val best = fit.filter { it.value.size >= MIN_FIT_N }
                .mapValues { (_, v) -> v.map(::perContract).average() }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .firstOrNull()
            fun oosOf(id: String) = later[id]?.takeIf { it.size >= MIN_OOS_N }?.map(::perContract)?.average()
            val curOos = oosOf(current.id)
            val candidate = best?.key?.let { ScalpParams.byId(it) }
            val candOos = candidate?.let { oosOf(it.id) }
            if (candidate != null && candidate.id != current.id && candOos != null && (curOos == null || candOos > curOos + 1e-12)) {
                params[coin] = candidate.id
                adopted = true
                oos[coin] = candOos * 100
                oosN[coin] = later[candidate.id]!!.size
                notes += String.format(Locale.US, "%s: adopted %s — later block %.2f¢/ct (n=%d) vs current %s %s",
                    coin, candidate.id, candOos * 100, later[candidate.id]!!.size, current.id,
                    curOos?.let { String.format(Locale.US, "%.2f¢", it * 100) } ?: "n/a")
            } else {
                curOos?.let { oos[coin] = it * 100; oosN[coin] = later[current.id]!!.size }
                notes += String.format(Locale.US, "%s: kept %s — later block %s; fit best %s",
                    coin, current.id, curOos?.let { String.format(Locale.US, "%.2f¢/ct", it * 100) } ?: "n<$MIN_OOS_N",
                    best?.key ?: "none (n<$MIN_FIT_N)")
            }
        }
        return cur.copy(
            version = if (adopted) cur.version + 1 else cur.version,
            paramsByCoin = params,
            lastRunAtMs = nowMs,
            trials = cur.trials + trials,
            oosCentsByCoin = oos,
            oosNByCoin = oosN,
            notes = notes
        )
    }
}

/** Close time from a Kalshi 15m ticker like KXBTC15M-25SEP181700-50 (yyMMMddHHmm, US/Eastern). */
object ScalpTicker {
    private val ET = ZoneId.of("America/New_York")
    private val FMT = DateTimeFormatter.ofPattern("yyMMMddHHmm", Locale.US)

    fun closeMs(ticker: String): Long? = runCatching {
        val seg = ticker.uppercase().split('-').getOrNull(1) ?: return null
        if (seg.length != 11) return null
        val norm = seg.substring(0, 2) + seg[2] + seg.substring(3, 5).lowercase() + seg.substring(5)
        LocalDateTime.parse(norm, FMT).atZone(ET).toInstant().toEpochMilli()
    }.getOrNull()

    fun hourEt(ms: Long): Int = java.time.Instant.ofEpochMilli(ms).atZone(ET).hour
}

/** Scorecard breakdowns: net after BOTH fees; a win only counts when net after both fees > 0. */
object ScalpBreakdown {
    data class Row(val key: String, val n: Int, val wins: Int, val netUsd: Double, val avgCentsPerContract: Double)

    private fun rows(closed: List<ScalpTrade>, keyOf: (ScalpTrade) -> String?): List<Row> =
        closed.mapNotNull { t -> keyOf(t)?.let { it to t } }.groupBy({ it.first }, { it.second }).map { (k, v) ->
            Row(k, v.size, v.count { it.netUsd!! > 0.0 }, v.sumOf { it.netUsd!! },
                v.sumOf { it.netUsd!! / it.contracts.coerceAtLeast(1) } / v.size * 100.0)
        }.sortedBy { it.key }

    private fun closed(trades: List<ScalpTrade>) = trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null }

    fun byCoin(trades: List<ScalpTrade>) = rows(closed(trades)) { it.coin }
    fun byHourEt(trades: List<ScalpTrade>) = rows(closed(trades)) { t ->
        (t.entryAtMs ?: t.signalAtMs).let { String.format(Locale.US, "%02d ET", ScalpTicker.hourEt(it)) }
    }
    fun byTimeLeft(trades: List<ScalpTrade>) = rows(closed(trades)) { t ->
        val close = ScalpTicker.closeMs(t.ticker) ?: return@rows null
        val left = (close - (t.entryAtMs ?: t.signalAtMs)) / 1000.0
        when {
            left >= 600 -> "10–14 min left"
            left >= 300 -> "5–10 min left"
            left >= 180 -> "3–5 min left"
            else -> "< 3 min left"
        }
    }

    data class PerWindow(val windows: Int, val avg: Double, val max: Int)

    /** Round trips per (market) window. */
    fun roundTripsPerWindow(trades: List<ScalpTrade>): PerWindow {
        val g = closed(trades).groupBy { it.ticker }
        if (g.isEmpty()) return PerWindow(0, 0.0, 0)
        return PerWindow(g.size, g.values.sumOf { it.size }.toDouble() / g.size, g.values.maxOf { it.size })
    }

    fun lines(trades: List<ScalpTrade>): List<String> {
        fun fmt(r: Row) = String.format(Locale.US, "%s · %d trips · %d%% wins · net $%.2f · %+.2f¢/ct",
            r.key, r.n, if (r.n == 0) 0 else Math.round(100.0 * r.wins / r.n), r.netUsd, r.avgCentsPerContract)
        val pw = roundTripsPerWindow(trades)
        return listOf("By coin (net after both fees)") + byCoin(trades).map(::fmt) +
            listOf("By hour (ET, entry)") + byHourEt(trades).map(::fmt) +
            listOf("By time left at entry") + byTimeLeft(trades).map(::fmt) +
            listOf(String.format(Locale.US, "Round trips per window: %.2f avg, %d max over %d windows", pw.avg, pw.max, pw.windows))
    }
}
