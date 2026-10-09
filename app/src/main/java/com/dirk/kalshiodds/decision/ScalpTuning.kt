package com.dirk.kalshiodds.decision

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.3.40 paper scalp strategies. They run side by side (one open per strategy per market side), each with its
 * own per-coin params, scorecard line and independent walk-forward tuning. PAPER ONLY — none can place an order.
 */
enum class ScalpStrategy(val code: String, val label: String, val blurb: String) {
    FAIR_GAP("fair", "Fair-gap", "ask below spot-implied fair by the gap threshold"),
    DIP_HUNTER("dip", "Dip-hunter", "mean reversion: buy after the ask drops fast while spot fair holds"),
    MOMENTUM("momo", "Momentum-sniper", "buy into a strong bid rise when spot fair confirms the move"),
    EXTREME_REVERSION("xrev", "Extreme-reversion", "buy the very cheap side (≤ 15¢) when it overshoots below spot fair"),
    /**
     * 0.3.43 delayed repricing: fair value from the CF Benchmarks index (60 s settlement-average aware, τeff);
     * NO TRADE when the CF tick is older than 2 s (never Coinbase).
     */
    CF_REPRICE("cfr", "CF-reprice", "Kalshi lags the CF Benchmarks index: buy when CF fair − ask − entry fee ≥ 6¢ and the predicted net exit ≥ 2¢");

    companion object {
        fun ofCode(code: String?): ScalpStrategy? = values().firstOrNull { it.code == code }
    }
}

/**
 * 0.3.40 scalp parameters (PAPER ONLY). One set per coin; a small fixed grid runs as shadow paper
 * variants so the walk-forward tuner can compare them on the app's own realistic paper fills.
 */
data class ScalpParams(
    /**
     * Entry trigger (dollars). Fair-gap: ask below fair after the entry fee. Dip-hunter: ask drop over the
     * last 60 s. Momentum-sniper: bid rise over the last 60 s. Extreme-reversion: fair − ask on a ≤ 15¢ side.
     */
    val minGap: Double,
    /** Exit when bid − entry ≥ target (dollars per contract). */
    val target: Double,
    /** Exit when bid ≤ entry − stop. */
    val stop: Double,
    /** Exit when spot-implied fair falls this far below entry. */
    val turnDown: Double,
    /** Entry window: seconds left to close. */
    val tauMinS: Double,
    val tauMaxS: Double = 840.0,
    val strategy: ScalpStrategy = ScalpStrategy.FAIR_GAP
) {
    /** Fair-gap ids keep the 0.3.40 format (g14-t12-…); other strategies are prefixed ("dip-g06-…"). */
    val id: String
        get() = (if (strategy == ScalpStrategy.FAIR_GAP) "" else strategy.code + "-") + String.format(
            Locale.US, "g%02d-t%02d-s%02d-d%02d-w%03d",
            Math.round(minGap * 100), Math.round(target * 100), Math.round(stop * 100),
            Math.round(turnDown * 100), Math.round(tauMinS)
        )

    fun label(): String {
        val trigger = when (strategy) {
            ScalpStrategy.FAIR_GAP -> "gap ≥ %.0f¢"
            ScalpStrategy.DIP_HUNTER -> "drop ≥ %.0f¢/60s"
            ScalpStrategy.MOMENTUM -> "rise ≥ %.0f¢/60s + spot"
            ScalpStrategy.EXTREME_REVERSION -> "ask ≤ 15¢ & fair gap ≥ %.0f¢"
            ScalpStrategy.CF_REPRICE -> "CF fair − ask − fee ≥ %.0f¢ (CF ≤ 2 s old)"
        }
        return String.format(
            Locale.US, "$trigger · target +%.0f¢ · stop −%.0f¢ · turn-down %.0f¢ · enter %.0f–%d min left",
            minGap * 100, target * 100, stop * 100, turnDown * 100, tauMinS / 60.0, (tauMaxS / 60).toInt()
        )
    }

    companion object {
        const val LEGACY_ID = "legacy-v1"
        val COINS = listOf("BTC", "ETH", "SOL")

        /** 0.3.38/0.3.39-style params (kept addressable for old rows and tests). */
        val CLASSIC = ScalpParams(minGap = 0.10, target = 0.08, stop = 0.06, turnDown = 0.10, tauMinS = 300.0)

        /**
         * 0.3.40 seeds = the rolling weekly walk-forward's pick per coin, fitted on Dec 2025 → Sep 20 2026 1-minute
         * history (scalp-research/long40.py, wf40.py). NOTE: the rolling out-of-sample test of that procedure was
         * negative (pooled −3.23¢/contract, 1 of 38 weeks positive) — these are the least-bad, not proven.
         */
        val SEEDS: Map<String, ScalpParams> = mapOf(
            "BTC" to ScalpParams(minGap = 0.14, target = 0.12, stop = 0.08, turnDown = 0.15, tauMinS = 300.0),
            "ETH" to ScalpParams(minGap = 0.10, target = 0.12, stop = 0.08, turnDown = 0.15, tauMinS = 300.0),
            "SOL" to ScalpParams(minGap = 0.14, target = 0.08, stop = 0.06, turnDown = 0.10, tauMinS = 480.0)
        )

        /** Fallback when the coin is unknown: the BTC seed. */
        val DEFAULT: ScalpParams = SEEDS.getValue("BTC")

        fun seedFor(coin: String): ScalpParams = SEEDS[coin] ?: DEFAULT

        /**
         * Seeds for the three 0.3.40 strategies = the rolling weekly walk-forward's final pick (fit on Dec 2025 → Sep 20 2026
         * 1-minute history, scalp-research/multi40.py + wfmulti.py, 54 variants × 3 coins = 162 trials). Rolling OOS after both
         * fees was NEGATIVE for all three: dip −4.44¢/ct [−4.58, −4.29] 0/38 weeks positive; momentum −4.77¢ [−4.93, −4.61]
         * 0/38; extreme-reversion −2.53¢ [−2.96, −2.10] 4/38. Least-bad, not proven. Paper only.
         */
        val STRATEGY_SEEDS: Map<ScalpStrategy, ScalpParams> = mapOf(
            ScalpStrategy.DIP_HUNTER to ScalpParams(0.05, 0.08, 0.06, 0.10, 180.0, strategy = ScalpStrategy.DIP_HUNTER),
            ScalpStrategy.MOMENTUM to ScalpParams(0.04, 0.08, 0.06, 0.10, 180.0, strategy = ScalpStrategy.MOMENTUM),
            ScalpStrategy.EXTREME_REVERSION to ScalpParams(0.12, 0.12, 0.08, 0.10, 120.0, strategy = ScalpStrategy.EXTREME_REVERSION),
            // 0.3.43 owner spec: entry gap 6¢, TP +8¢, stop −6¢, 3–13 min left. turnDown = early exit when the gap is gone.
            ScalpStrategy.CF_REPRICE to ScalpParams(0.06, 0.08, 0.06, 0.0, 180.0, tauMaxS = 780.0, strategy = ScalpStrategy.CF_REPRICE)
        )

        fun seedFor(coin: String, strategy: ScalpStrategy): ScalpParams =
            if (strategy == ScalpStrategy.FAIR_GAP) seedFor(coin) else STRATEGY_SEEDS.getValue(strategy)

        /** Tuner-state key: "BTC" for fair-gap (0.3.40 format), "BTC:dip" etc. for the others. */
        fun key(coin: String, strategy: ScalpStrategy): String =
            if (strategy == ScalpStrategy.FAIR_GAP) coin else "$coin:${strategy.code}"

        /** Small grid around the seeds: 2 gaps × 2 exit profiles × 2 entry windows = 8 variants per coin. */
        val GRID: List<ScalpParams> = buildList {
            for (g in listOf(0.10, 0.14)) for (exit in listOf(Triple(0.08, 0.06, 0.10), Triple(0.12, 0.08, 0.15))) {
                for (w in listOf(300.0, 480.0)) add(ScalpParams(g, exit.first, exit.second, exit.third, w))
            }
        }

        /** 4 variants per extra strategy: 2 triggers × 2 exit profiles (target, stop, turn-down). */
        val STRATEGY_GRID: List<ScalpParams> = buildList {
            fun add4(s: ScalpStrategy, triggers: List<Double>, exits: List<Triple<Double, Double, Double>>, w: Double) {
                for (g in triggers) for (e in exits) add(ScalpParams(g, e.first, e.second, e.third, w, strategy = s))
            }
            add4(ScalpStrategy.DIP_HUNTER, listOf(0.05, 0.08), listOf(Triple(0.06, 0.05, 0.10), Triple(0.08, 0.06, 0.10)), 180.0)
            add4(ScalpStrategy.MOMENTUM, listOf(0.04, 0.06), listOf(Triple(0.06, 0.04, 0.08), Triple(0.08, 0.06, 0.10)), 180.0)
            add4(ScalpStrategy.EXTREME_REVERSION, listOf(0.08, 0.12), listOf(Triple(0.08, 0.05, 0.06), Triple(0.12, 0.08, 0.10)), 120.0)
            // 0.3.43 CF-reprice: 2 entry gaps × 2 exit profiles, window 3–13 min (walk-forward tuned like the others).
            for (g in listOf(0.06, 0.08)) for (e in listOf(Triple(0.08, 0.06, 0.0), Triple(0.06, 0.04, 0.0))) {
                add(ScalpParams(g, e.first, e.second, e.third, 180.0, tauMaxS = 780.0, strategy = ScalpStrategy.CF_REPRICE))
            }
        }

        /** Everything the book runs as paper variants: 8 fair-gap + 12 strategy variants. */
        val ALL_VARIANTS: List<ScalpParams> get() = GRID + STRATEGY_GRID

        fun byId(id: String): ScalpParams? =
            (GRID + STRATEGY_GRID + SEEDS.values + STRATEGY_SEEDS.values + CLASSIC).firstOrNull { it.id == id }

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
    fun paramsFor(coin: String, strategy: ScalpStrategy = ScalpStrategy.FAIR_GAP): ScalpParams =
        paramsByCoin[ScalpParams.key(coin, strategy)]?.let { ScalpParams.byId(it) }?.takeIf { it.strategy == strategy }
            ?: ScalpParams.seedFor(coin, strategy)
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
        for (strategy in ScalpStrategy.values()) for (coin in ScalpParams.COINS) {
            val key = ScalpParams.key(coin, strategy)
            val tag = "$coin ${strategy.label}"
            val mine = closed.filter { it.coin == coin && it.strategy == strategy }
            val windows = mine.map { ScalpTicker.closeMs(it.ticker) ?: it.signalAtMs }.distinct().sorted()
            val current = cur.paramsFor(coin, strategy)
            if (windows.size < 5) {
                if (strategy == ScalpStrategy.FAIR_GAP || mine.isNotEmpty()) {
                    notes += "$tag: ${mine.size} round trips — not enough windows to tune; keeping ${current.id}"
                }
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
                params[key] = candidate.id
                adopted = true
                oos[key] = candOos * 100
                oosN[key] = later[candidate.id]!!.size
                notes += String.format(Locale.US, "%s: adopted %s — later block %.2f¢/ct (n=%d) vs current %s %s",
                    tag, candidate.id, candOos * 100, later[candidate.id]!!.size, current.id,
                    curOos?.let { String.format(Locale.US, "%.2f¢", it * 100) } ?: "n/a")
            } else {
                curOos?.let { oos[key] = it * 100; oosN[key] = later[current.id]!!.size }
                notes += String.format(Locale.US, "%s: kept %s — later block %s; fit best %s",
                    tag, current.id, curOos?.let { String.format(Locale.US, "%.2f¢/ct", it * 100) } ?: "n<$MIN_OOS_N",
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
    fun byStrategy(trades: List<ScalpTrade>) = rows(closed(trades)) { it.strategy.label }
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
        val perStrategy = ScalpStrategy.values().flatMap { st ->
            val mine = closed(trades).filter { it.strategy == st }
            if (mine.isEmpty()) return@flatMap listOf("${st.label} · no closed round trips yet")
            val r = rows(mine) { st.label }.single()
            listOf(fmt(r)) + byCoin(mine).map { "  " + fmt(it) } + byHourEt(mine).map { "  " + fmt(it) }
        }
        return listOf("By strategy (net after both fees; coin and hour ET below each)") + perStrategy +
            listOf("By coin (net after both fees)") + byCoin(trades).map(::fmt) +
            listOf("By hour (ET, entry)") + byHourEt(trades).map(::fmt) +
            listOf("By time left at entry") + byTimeLeft(trades).map(::fmt) +
            listOf(String.format(Locale.US, "Round trips per window: %.2f avg, %d max over %d windows", pw.avg, pw.max, pw.windows))
    }
}
