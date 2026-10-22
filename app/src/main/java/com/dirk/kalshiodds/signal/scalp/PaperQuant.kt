package com.dirk.kalshiodds.signal.scalp

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.sqrt

/** Spot data is an explicitly named exchange proxy, captured as-of the book, never BRTI settlement data. */
@Serializable data class PaperQuantSpot(val fetchedAtMs: Long, val price: Double? = null,
    val return1m: Double? = null, val return5m: Double? = null, val vol15m: Double? = null,
    val strike: Double? = null, val source: String = "unknown")
/** Entry-time features and a frozen prediction; no future outcome information. */
@Serializable data class PaperQuantEntry(val x: List<Double>, val probability: Double? = null,
    val trainedAtMs: Long = 0, val schema: Int = 1)
@Serializable data class PaperQuantObservation(val ticker: String, val setup: String, val side: String,
    val openedMs: Long, val closeMs: Long, val outcomeMs: Long, val x: List<Double>, val netPerContract: Double) {
    val key: String get() = "$ticker/$setup/$side"
}
@Serializable data class PaperQuantRegime(val label: String, val windows: Int, val baseline: Double, val filtered: Double)
@Serializable data class PaperQuantDecision(val atMs: Long, val ticker: String, val setup: String, val side: String,
    val entry: PaperQuantEntry, val selected: Boolean, val modelReady: Boolean, val filterRequested: Boolean)
@Serializable data class PaperQuantModel(val weights: List<Double> = emptyList(), val win: Double = 0.0,
    val loss: Double = 0.0, val trainedAtMs: Long = 0, val windows: Int = 0,
    val testWindows: Int = 0, val selectedWindows: Int = 0, val baseline: Double = 0.0,
    val filtered: Double = 0.0, val incrementalLower: Double = 0.0, val filteredLower: Double = 0.0, val regimes: List<PaperQuantRegime> = emptyList()) {
    val ready: Boolean get() = weights.size == PaperQuant.N + 1 && weights.all { it.isFinite() } &&
        testWindows >= 30 && selectedWindows >= 10 && incrementalLower > 0 && filteredLower > 0
}
@Serializable data class PaperQuantState(val filterRequested: Boolean = false,
    val observations: List<PaperQuantObservation> = emptyList(), val model: PaperQuantModel = PaperQuantModel(),
    val lastFitMs: Long = 0) {
    val label: String get() = when {
        model.ready && filterRequested -> "Entry filter active · 15% books remain controls"
        model.ready -> "Shadow model · validation gate passed on retained paper data"
        filterRequested -> "Filter requested · collecting evidence in shadow"
        else -> "Learning in shadow · entries follow existing rules"
    }
}

/** Small deterministic L2 logistic model. Its label is positive NET scalp PnL, not settlement direction.
 * Training and walk-forward tests use completed, embargoed contract windows from unfiltered controls.
 */
object PaperQuant {
    const val N = 20
    const val EMBARGO_MS = 90_000L
    val features = listOf("time_left_900s", "ask", "bid_move_10s_x10", "bid_move_30s_x10",
        "bid_step_rms_30s_x10", "spread_x100", "top3_depth_imbalance", "recent_recovery_x10",
        "range", "sweep", "trend", "forecast", "spot_distance_x1000", "spot_distance_present",
        "spot_return_1m_x1000", "spot_return_1m_present", "spot_return_5m_x1000", "spot_return_5m_present",
        "spot_vol_15m_x1000", "spot_vol_15m_present")
    private val setups = listOf("cheap", "dip", "momentum", "forecast-fade")
    fun entry(q: PaperDepth, history: List<PaperDepth>, side: String, setup: String, model: PaperQuantModel): PaperQuantEntry? {
        if (setup !in setups) return null
        val rows = PaperScalpSignals.seconds(history, q)
        fun prior(ms: Long) = rows.lastOrNull { it.atMs <= q.atMs - ms }?.bid(side)?.takeIf { it > 0 }
        val b10 = prior(10_000) ?: return null
        val b30 = prior(30_000) ?: return null
        val bid = q.bid(side); val ask = q.ask(side) ?: return null
        if (bid <= 0 || ask < bid) return null
        val recent = rows.filter { q.atMs - it.atMs <= 30_000 }.map { it.bid(side) }
        if (recent.any { it <= 0 }) return null
        val steps = recent.zipWithNext { a, b -> (b - a) * (b - a) }
        val support = q.bids(side).take(3).sumOf { it.second }
        val opposition = q.asks(side).take(3).sumOf { it.second }
        val sign = if (side == "YES") 1 else -1
        val spot = q.spot?.takeIf { it.fetchedAtMs > 0 && q.atMs - it.fetchedAtMs in 0..90_000 }
        fun masked(value: Double?) = value?.takeIf { it.isFinite() }?.let { listOf(it, 1.0) } ?: listOf(0.0, 0.0)
        val distance = spot?.let { v -> v.price?.takeIf { it.isFinite() && it > 0 }?.let { price ->
            v.strike?.takeIf { it.isFinite() && it > 0 }?.let { (price / it - 1) * 1000 * sign } } }
        val proxy = masked(distance) + masked(spot?.return1m?.times(1000 * sign)) +
            masked(spot?.return5m?.times(1000 * sign)) + masked(spot?.vol15m?.takeIf { it >= 0 }?.times(1000))
        val x = (listOf((q.closeMs - q.atMs) / 900_000.0, ask, (bid - b10) * 10,
            (bid - b30) * 10, sqrt(steps.average().takeIf { it.isFinite() } ?: 0.0) * 10,
            (ask - bid) * 100, (support - opposition) / (support + opposition).coerceAtLeast(1.0),
            (bid - recent.min()) * 10) + setups.map { if (it == setup) 1.0 else 0.0 } + proxy).map { it.coerceIn(-5.0, 5.0) }
        if (!validX(x)) return null
        return PaperQuantEntry(x, probability(model, x), model.trainedAtMs)
    }
    fun validX(x: List<Double>) = x.size == N && x.all { it.isFinite() && it in -5.0..5.0 }
    fun probability(model: PaperQuantModel, x: List<Double>): Double? {
        if (!validX(x) || model.weights.size != N + 1 || model.weights.any { !it.isFinite() }) return null
        val z = (model.weights[0] + x.indices.sumOf { x[it] * model.weights[it + 1] }).coerceIn(-30.0, 30.0)
        return 1.0 / (1.0 + exp(-z))
    }
    fun accepts(model: PaperQuantModel, x: List<Double>): Boolean {
        val p = probability(model, x) ?: return true // missing feature/model never suppresses stops or baseline entries
        return p >= .55 && p * model.win - (1 - p) * model.loss >= .01
    }
    /** One canonical completed control trade per setup/side/window; sizing copies are never extra samples. */
    fun observe(state: PaperQuantState, accounts: List<PaperScalpAccount>): PaperQuantState {
        val known = state.observations.map { it.key }.toMutableSet()
        val additions = accounts.filter { it.id.endsWith("-15") && !it.id.startsWith("adaptive-") }
            .flatMap { it.results }.sortedBy { it.atMs }.mapNotNull { r ->
                val e = r.quantEntry ?: return@mapNotNull null
                if (r.policyVersion != 3 || e.schema != 1 || !validX(e.x) || r.count <= 0 || !r.netUsd.isFinite()) return@mapNotNull null
                val o = PaperQuantObservation(r.ticker, r.setup, r.side, r.openedMs, r.closeMs, r.atMs, e.x, r.netUsd / r.count)
                if (o.closeMs <= o.openedMs || o.outcomeMs < o.openedMs || !known.add(o.key)) null else o
            }
        if (additions.isEmpty()) return state
        return state.copy(observations = (state.observations + additions).sortedBy { it.closeMs }.takeLast(600))
    }
    fun eligible(rows: List<PaperQuantObservation>, beforeMs: Long) = rows.filter {
        validX(it.x) && it.netPerContract.isFinite() && it.openedMs < it.closeMs &&
            maxOf(it.closeMs, it.outcomeMs) + EMBARGO_MS < beforeMs
    }
    /** Window weights prevent a busy market from dominating. Fixed transforms need no future statistics. */
    fun fit(rows: List<PaperQuantObservation>, atMs: Long): PaperQuantModel {
        if (rows.isEmpty()) return PaperQuantModel(trainedAtMs = atMs)
        val groups = rows.groupBy { it.ticker }
        val weights = rows.map { 1.0 / groups.getValue(it.ticker).size }
        val total = weights.sum()
        val w = DoubleArray(N + 1)
        repeat(70) {
            val g = DoubleArray(N + 1)
            rows.forEachIndexed { i, row ->
                val z = (w[0] + row.x.indices.sumOf { w[it + 1] * row.x[it] }).coerceIn(-30.0, 30.0)
                val error = (1 / (1 + exp(-z)) - if (row.netPerContract > 0) 1.0 else 0.0) * weights[i]
                g[0] += error
                row.x.indices.forEach { j -> g[j + 1] += error * row.x[j] }
            }
            w.indices.forEach { j -> w[j] -= .25 * (g[j] / total + if (j == 0) 0.0 else .02 * w[j]) }
        }
        fun mean(positive: Boolean): Double {
            val indices = rows.indices.filter { (rows[it].netPerContract > 0) == positive }
            val denom = indices.sumOf { weights[it] }
            return if (denom == 0.0) 0.0 else indices.sumOf { kotlin.math.abs(rows[it].netPerContract) * weights[it] } / denom
        }
        return PaperQuantModel(w.toList(), mean(true), mean(false), atMs, groups.size)
    }
    data class Fold(val train: List<PaperQuantObservation>, val test: List<List<PaperQuantObservation>>)
    fun folds(rows: List<PaperQuantObservation>): List<Fold> {
        val windows = rows.groupBy { it.ticker }.values.sortedBy { group -> group.minOf { it.openedMs } }
        if (windows.size < 60) return emptyList()
        return (0..2).map { fold ->
            val first = windows.size - 30 + fold * 10
            val test = windows.subList(first, first + 10)
            Fold(eligible(windows.take(first).flatten(), test.flatten().minOf { it.openedMs }), test)
        }
    }
    /** Three chronological blocks of ten entire windows. No shuffled rows, same-window or label overlap. */
    fun validate(rows: List<PaperQuantObservation>, model: PaperQuantModel): PaperQuantModel {
        val folds = folds(rows)
        if (folds.size != 3 || folds.any { it.train.map { row -> row.ticker }.distinct().size < 30 }) return model
        val baseline = mutableListOf<Double>(); val filtered = mutableListOf<Double>()
        var selected = 0
        val regimeValues = mutableMapOf<String, MutableList<Pair<Double, Double>>>()
        for ((train, test) in folds) {
            val candidate = fit(train, test.flatten().minOf { it.openedMs })
            for (window in test) {
                baseline += window.map { it.netPerContract }.average()
                val kept = window.filter { accepts(candidate, it.x) }
                if (kept.isNotEmpty()) selected++
                // Rejected trades contribute zero; same opportunities and denominator for both arms.
                filtered += kept.sumOf { it.netPerContract } / window.size
                window.groupBy { when { it.x[0] * 900 >= 600 -> "10+ min"; it.x[0] * 900 >= 300 -> "5–10 min"; else -> "<5 min" } }.forEach { (label, group) ->
                    regimeValues.getOrPut(label) { mutableListOf() } += group.map { it.netPerContract }.average() to
                        (group.filter { accepts(candidate, it.x) }.sumOf { it.netPerContract } / group.size)
                }
            }
        }
        fun lower(v: List<Double>): Double {
            val mean = v.average()
            val se = sqrt(v.sumOf { (it - mean) * (it - mean) } / (v.size - 1) / v.size)
            return mean - 2 * se // descriptive window-level uncertainty gate; not a profitability guarantee
        }
        return model.copy(testWindows = baseline.size, selectedWindows = selected,
            baseline = baseline.average(), filtered = filtered.average(), filteredLower = lower(filtered),
            incrementalLower = lower(filtered.zip(baseline) { f, b -> f - b }),
            regimes = listOf("10+ min", "5–10 min", "<5 min").mapNotNull { label -> regimeValues[label]?.let { values ->
                PaperQuantRegime(label, values.size, values.map { it.first }.average(), values.map { it.second }.average()) } })
    }
    fun update(state: PaperQuantState, accounts: List<PaperScalpAccount>, atMs: Long): PaperQuantState {
        val observed = observe(state, accounts)
        if (atMs - state.lastFitMs < 15_000) return observed
        val rows = eligible(observed.observations, atMs)
        val model = if (rows.map { it.ticker }.distinct().size < 30) PaperQuantModel(trainedAtMs = atMs, windows = rows.map { it.ticker }.distinct().size)
            else validate(rows, fit(rows, atMs))
        return observed.copy(model = model, lastFitMs = atMs)
    }
}
