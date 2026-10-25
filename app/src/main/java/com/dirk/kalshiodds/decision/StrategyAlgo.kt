package com.dirk.kalshiodds.decision

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 1.0.14: per-strategy algorithm knobs the window watcher may move.
 * Paper only. Each knob has a hard min/max and a maximum step per change.
 * Old [ScalpControls] records omit [ScalpControls.algo] and load as empty.
 */
data class AlgoKnob(val key: String, val label: String, val min: Double, val max: Double, val step: Double) {
    init {
        require(step > 0.0 && min <= max)
    }
}

/** Recent paper results the watcher reads. [recentReal] and [shadow] are oldest-first nets. */
data class PaperTape(
    val code: String,
    val trades: Int,
    val wins: Int,
    val netUsd: Double,
    val winRate: Double?,
    val recentReal: List<Double>,
    val shadow: List<Double>
)

object StrategyAlgo {
    private fun cents(key: String, label: String, min: Double, max: Double, step: Double = 1.0) =
        AlgoKnob(key, label, min, max, step)

    private fun size(cap: Int) = AlgoKnob("contracts", "size", PaperClip.MIN.toDouble(), maxOf(cap, PaperClip.MIN).toDouble().coerceAtMost(500.0), 1.0)
    private fun hold(maxS: Double = 900.0, step: Double = 30.0) = AlgoKnob("maxHoldSeconds", "max hold", 0.0, maxS, step)
    private val stop = cents("stopCents", "stop distance", 1.0, 48.0)
    private val tp = cents("takeProfitCents", "take profit", 0.0, 48.0)

    /** Largest paper clip the AI may set: the wider of the two paper size caps, never a live size, never under the paper floor. */
    fun contractCap(c: ScalpControls): Int = maxOf(c.contracts, c.greenContracts, PaperClip.MIN).coerceIn(PaperClip.MIN, 500)

    fun knobs(c: ScalpControls, code: String): List<AlgoKnob> {
        if (code == "v2-llm" || ScalpModels.modelByCode(code) == null) return emptyList()
        val cap = contractCap(c)
        return when {
            code.startsWith("v2-") -> listOf(
                cents("maxEntryCents", "entry ceiling", 1.0, 55.0),
                stop, tp, hold(), size(cap)
            )
            Research.isResearch(code) -> listOf(
                cents("edgeCents", "edge minimum", 0.0, 20.0),
                cents("minEntryCents", "entry floor", 10.0, 99.0),
                cents("maxEntryCents", "entry ceiling", 1.0, 99.0),
                size(cap)
            )
            code.startsWith("fc") -> listOf(stop, hold(), size(cap))
            code.startsWith("sf") -> shockKnobs(code, cap)
            else -> originalKnobs(code, cap)
        }
    }

    fun knob(c: ScalpControls, code: String, key: String): AlgoKnob? = knobs(c, code).firstOrNull { it.key == key }

    /** Value in force: an AI override, otherwise the strategy's own built-in number. */
    fun current(c: ScalpControls, code: String, knob: AlgoKnob): Double {
        c.algo[code]?.get(knob.key)?.takeIf { it.isFinite() }?.let { return it.coerceIn(knob.min, knob.max) }
        return baseline(c, code, knob.key).coerceIn(knob.min, knob.max)
    }

    /**
     * Move [proposed] to at most one [AlgoKnob.step] from [current], then into [knob]'s min/max.
     * Returns null when the proposal is not a finite number.
     */
    fun step(current: Double, proposed: Double, knob: AlgoKnob): Double? {
        if (!current.isFinite() || !proposed.isFinite()) return null
        val lo = maxOf(knob.min, current - knob.step)
        val hi = minOf(knob.max, current + knob.step)
        if (lo > hi) return null
        return proposed.coerceIn(lo, hi).roundToInt().toDouble().coerceIn(lo, hi)
    }

    fun fmt(v: Double): String =
        if (v.isFinite() && v == kotlin.math.round(v)) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)

    fun describe(c: ScalpControls, code: String): String = knobs(c, code).joinToString(",") { k ->
        "${k.key}=${fmt(current(c, code, k))}[${fmt(k.min)}..${fmt(k.max)} step ${fmt(k.step)}]"
    }

    /** Drop unknown keys and pull every stored override inside its bounds. Keeps older records loadable. */
    fun sanitize(c: ScalpControls): Map<String, Map<String, Double>> {
        val out = LinkedHashMap<String, Map<String, Double>>()
        for ((code, raw) in c.algo) {
            val allowed = knobs(c, code).associateBy { it.key }
            if (allowed.isEmpty()) continue
            val clean = LinkedHashMap<String, Double>()
            for ((k, v) in raw) {
                val knob = allowed[k] ?: continue
                if (!v.isFinite()) continue
                clean[k] = v.coerceIn(knob.min, knob.max)
            }
            if (clean.isNotEmpty()) out[code] = clean
        }
        return out
    }

    /** Settings the paper lane should read: built-in sliders, with AI overrides on top. Empty algo map returns [ScalpControls.settings]. */
    fun view(c: ScalpControls, code: String): ScalpStrategySettings {
        val s = c.settings(code)
        val a = c.algo[code].orEmpty()
        if (a.isEmpty()) return s
        fun i(key: String) = a[key]?.takeIf { it.isFinite() }?.roundToInt()
        return s.copy(
            contracts = i("contracts")?.coerceIn(1, contractCap(c)) ?: s.contracts,
            minEntryCents = i("minEntryCents")?.coerceIn(1, 99) ?: s.minEntryCents,
            maxEntryCents = i("maxEntryCents")?.coerceIn(1, 99) ?: s.maxEntryCents,
            stopCents = i("stopCents")?.coerceIn(1, 99) ?: s.stopCents,
            takeProfitCents = i("takeProfitCents")?.coerceIn(0, 99) ?: s.takeProfitCents,
            maxHoldSeconds = i("maxHoldSeconds")?.coerceIn(0, 3600) ?: s.maxHoldSeconds,
            edgeCents = i("edgeCents")?.coerceIn(0, 30) ?: s.edgeCents,
            stopLossPercent = i("stopLossPercent")?.coerceIn(1, 99) ?: s.stopLossPercent
        ).normalized()
    }

    fun storedInt(c: ScalpControls, code: String, key: String, fallback: Int): Int {
        val raw = c.algo[code]?.get(key)?.takeIf { it.isFinite() } ?: return fallback
        val knob = knob(c, code, key) ?: return fallback
        return raw.roundToInt().coerceIn(knob.min.toInt(), knob.max.toInt())
    }

    /**
     * Paper clip. A saved per-scalper contracts value is the clip (manual or tuner).
     * An algorithm override cannot grow past the built-in clip, and green size is not a clip.
     */
    fun clip(c: ScalpControls, code: String?, fallback: Int): Int {
        val cap = contractCap(c)
        if (code.isNullOrBlank()) return PaperClip.sized(fallback).coerceAtMost(cap).coerceAtLeast(PaperClip.MIN)
        val explicit = c.strategySettings[code]?.contracts
        if (explicit != null) return ScalpParamSanity.ownClip(code, explicit)
        val own = ScalpParamSanity.ownClip(code, null)
        val algo = c.algo[code]?.get("contracts")?.takeIf { it.isFinite() }?.roundToInt()
        val chosen = minOf((algo ?: fallback).coerceIn(1, cap), own)
        return PaperClip.sized(chosen).coerceAtMost(maxOf(own, PaperClip.MIN))
    }

    /** Numeric overlay for an original strategy. Does not change the params id the book uses to match open trades. */
    fun overlay(p: ScalpParams, algo: Map<String, Double>): ScalpParams {
        if (algo.isEmpty()) return p
        fun px(key: String, cur: Double, lo: Double, hi: Double) =
            algo[key]?.takeIf { it.isFinite() }?.div(100.0)?.coerceIn(lo, hi) ?: cur
        return p.copy(
            minGap = px("minGapCents", p.minGap, 0.01, 0.30),
            target = px("takeProfitCents", p.target, 0.0, 0.40),
            stop = px("stopCents", p.stop, 0.01, 0.40)
        )
    }

    fun overlayShock(p: ShockFav.Params, algo: Map<String, Double>): ShockFav.Params {
        if (algo.isEmpty()) return p
        fun has(k: String) = algo[k]?.isFinite() == true
        fun px(k: String) = algo.getValue(k) / 100.0
        var n = p
        if (has("shockBp")) n = n.copy(xBp = algo.getValue("shockBp").coerceIn(1.0, 30.0))
        if (has("lagCents")) n = n.copy(lag = px("lagCents").coerceIn(0.01, 0.20))
        if (has("dipCents")) n = n.copy(dip = px("dipCents").coerceIn(0.01, 0.25))
        if (has("gapCents")) n = n.copy(gap = px("gapCents").coerceIn(0.01, 0.20))
        if (has("fairMinCents")) n = n.copy(fmin = px("fairMinCents").coerceIn(0.50, 0.99))
        if (has("maxEntryCents")) n = n.copy(amax = px("maxEntryCents").coerceIn(0.50, 0.99))
        if (has("catchUpCents")) n = n.copy(conv = px("catchUpCents").coerceIn(0.0, 0.10))
        if (has("takeProfitCents")) {
            val v = px("takeProfitCents").coerceIn(0.0, 0.40)
            n = n.copy(tp = v, tpNetO50 = if (p.tpNetO50 != null) v.coerceIn(0.01, 0.20) else p.tpNetO50)
        }
        if (has("trailCents") && p.trail != null) n = n.copy(trail = px("trailCents").coerceIn(0.01, 0.20))
        if (has("maxHoldSeconds")) n = n.copy(maxHoldS = algo.getValue("maxHoldSeconds").roundToInt().coerceIn(0, 900))
        return n
    }

    private fun originalKnobs(code: String, cap: Int): List<AlgoKnob> = buildList {
        add(cents("minGapCents", "entry gap", 1.0, 30.0))
        add(cents("maxEntryCents", "entry ceiling", 1.0, 55.0))
        if (!code.contains("notp")) add(tp)
        add(stop)
        add(hold())
        add(size(cap))
    }

    private fun shockKnobs(code: String, cap: Int): List<AlgoKnob> {
        val p = ShockFav.byVariantId(code) ?: return emptyList()
        val s = size(cap)
        return when (p.kind) {
            ShockFav.Kind.SIMPLE_DIP -> listOf(cents("dipCents", "dip below average", 1.0, 25.0), tp, stop, s)
            ShockFav.Kind.OPEN50 -> buildList {
                if (p.trail != null) add(cents("trailCents", "trail below peak", 1.0, 20.0))
                if (p.tpNetO50 != null) add(cents("takeProfitCents", "net take profit", 1.0, 20.0))
                add(stop); add(hold(300.0, 30.0)); add(s)
            }
            ShockFav.Kind.LAGGARD, ShockFav.Kind.FADE_MAKER -> listOf(
                AlgoKnob("shockBp", "move threshold", 1.0, 30.0, 1.0), tp, hold(), stop, s
            )
            ShockFav.Kind.IMBALANCE -> listOf(hold(), stop, s)
            ShockFav.Kind.SHOCK_FAV -> if (p.fav && !p.shock) listOf(
                cents("gapCents", "gap minimum", 1.0, 20.0),
                cents("fairMinCents", "fair minimum", 50.0, 99.0),
                cents("maxEntryCents", "entry ceiling", 50.0, 99.0),
                stop, s
            ) else listOf(
                AlgoKnob("shockBp", "spot shock", 1.0, 30.0, 1.0),
                cents("lagCents", "book lag", 1.0, 20.0),
                cents("catchUpCents", "catch-up", 0.0, 10.0),
                hold(), stop, s
            )
        }
    }

    private fun baseline(c: ScalpControls, code: String, key: String): Double {
        val s = c.settings(code)
        val spec = if (code.startsWith("v2-")) ScalpV2.spec(code) else null
        val shock = if (code.startsWith("sf")) ShockFav.byVariantId(code) else null
        val orig = originalParams(code)
        return when (key) {
            "minGapCents" -> (orig?.minGap ?: 0.10) * 100.0
            "maxEntryCents" -> when {
                s.maxEntryCents != null -> s.maxEntryCents.toDouble()
                spec != null -> spec.maxAsk * 100.0
                shock != null -> shock.amax * 100.0
                FairGapTune.defaultMaxCents(code) != null -> FairGapTune.defaultMaxCents(code)!!.toDouble()
                else -> 55.0
            }
            "minEntryCents" -> FairGapTune.effectiveMinCents(code, s).toDouble()
            "edgeCents" -> (s.edgeCents ?: Research.defaultEdge(code)).toDouble()
            "stopCents" -> s.stopCents?.toDouble()
                ?: spec?.stop?.times(100.0)
                ?: shock?.let { ShockFav.stopC(it).toDouble() }
                ?: if (code.startsWith("fc")) ForecastScalp.STOP_C.toDouble() else null
                ?: orig?.stop?.times(100.0)
                ?: c.stopCents.toDouble()
            "takeProfitCents" -> s.takeProfitCents?.toDouble()
                ?: shock?.tpNetO50?.times(100.0)
                ?: shock?.tp?.times(100.0)
                ?: orig?.takeIf { it.target < ScalpParams.NO_TP - 1e-9 }?.target?.times(100.0)
                ?: c.takeProfitCents.toDouble()
            "maxHoldSeconds" -> when {
                s.maxHoldSeconds != null -> s.maxHoldSeconds.toDouble()
                shock != null && shock.kind == ShockFav.Kind.OPEN50 -> ShockFav.O50_MAX_HOLD_S
                shock != null -> shock.maxHoldS.toDouble()
                else -> c.maxHoldSeconds.toDouble()
            }
            "contracts" -> when {
                s.contracts != null -> s.contracts.toDouble()
                spec != null -> spec.clip.toDouble()
                else -> c.contracts.toDouble()
            }
            "dipCents" -> (shock?.dip ?: 0.05) * 100.0
            "shockBp" -> shock?.xBp ?: 5.0
            "lagCents" -> (shock?.lag ?: 0.05) * 100.0
            "gapCents" -> (shock?.gap ?: 0.05) * 100.0
            "fairMinCents" -> (shock?.fmin ?: 0.90) * 100.0
            "trailCents" -> (shock?.trail ?: 0.02) * 100.0
            "catchUpCents" -> (shock?.conv ?: 0.01) * 100.0
            else -> 0.0
        }
    }

    private fun originalParams(code: String): ScalpParams? = when (code) {
        "fair", KeptScalpers.FAIR_ID -> ScalpParams.byId(KeptScalpers.FAIR_ID) ?: ScalpParams.seedFor("BTC")
        "fair-t1" -> ScalpParams.FAIR_TWEAKS[0]
        "fair-t2" -> ScalpParams.FAIR_TWEAKS[1]
        "fairh60" -> ScalpParams.seedFor("BTC").copy(hold60 = true)
        "dip" -> ScalpParams.seedFor("BTC", ScalpStrategy.DIP_HUNTER)
        "momo" -> ScalpParams.seedFor("BTC", ScalpStrategy.MOMENTUM)
        "momoh60" -> ScalpParams.seedFor("BTC", ScalpStrategy.MOMENTUM).copy(hold60 = true)
        "momonotp" -> ScalpParams.seedFor("BTC", ScalpStrategy.MOMENTUM).copy(target = ScalpParams.NO_TP)
        "xrev" -> ScalpParams.seedFor("BTC", ScalpStrategy.EXTREME_REVERSION)
        "cfr" -> ScalpParams.seedFor("BTC", ScalpStrategy.CF_REPRICE)
        else -> null
    }
}

/**
 * 1.0.14 auto-bench. A strategy with 30 or more settled paper trades whose net is clearly
 * negative (more than $1 down) is benched. Shadow paper after that can bring it back.
 * Callers must skip this when Dirk has Force trade or Force bench set.
 */
object AiBench {
    const val MIN_TRADES = 30
    /** Net at or under this (dollars) over the window is "clearly negative". A few cents is not. */
    const val CLEAR_LOSS_USD = 1.0

    data class Decision(val bench: Boolean, val unbench: Boolean, val netUsd: Double, val reason: String)

    @Suppress("UNUSED_PARAMETER")
    fun decide(wasBenched: Boolean, recentReal: List<Double>, shadow: List<Double>, window: Int = MIN_TRADES): Decision {
        val net = recentReal.filter { it.isFinite() }.takeLast(window.coerceAtLeast(1)).sum()
        // No automatic bench. A losing book keeps trading. Force bench is a settings override, not this call.
        return Decision(false, false, net, "trading · no auto-bench")
    }

    private fun money(v: Double) = String.format(Locale.US, "%+.2f", v)
}
