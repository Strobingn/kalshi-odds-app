package com.dirk.kalshiodds.decision

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Kalshi Scalper "Research" paper strategies (BTC only, paper only). Exempt from the 3¢ trail / window-start / >95% rules
 * used by the other strategies. Params are frozen (no online retuning) and editable with sliders.
 * Source: /workspace/research/market-methods-2026-10-16.md.
 */
object Research {
    const val PREFIX = "r-"
    const val LATE = "r-late"; const val HOLD = "r-hold"; const val MAKER = "r-maker"; const val KELLY = "r-kelly"
    /** 1.0.14: two tweaked paper copies of r-late (the money maker): own entry window, edge gate, max gap and default band. */
    const val LATE_T1 = "r-late-t1"; const val LATE_T2 = "r-late-t2"
    data class LateSpec(val minS: Int, val maxS: Int, val edgeCents: Int, val maxGap: Double, val loCents: Int, val hiCents: Int)
    /** 1.0.21: no settle-hold entry with under 180s left. t1 stays 26–42¢ / 12¢. t2 tightens to 22–42¢ / 10¢. */
    val LATE_SPECS: Map<String, LateSpec> = mapOf(
        LATE_T1 to LateSpec(minS = 180, maxS = 240, edgeCents = 12, maxGap = 0.21, loCents = 26, hiCents = 42),
        LATE_T2 to LateSpec(minS = 180, maxS = 360, edgeCents = 10, maxGap = 0.23, loCents = 22, hiCents = 42)
    )
    fun isLate(id: String): Boolean = id == LATE || id in LATE_SPECS
    /** Seconds left in the window. Spec bounds are seconds, not milliseconds. Inclusive on both ends. */
    fun lateWindowOk(tauS: Double, id: String, fallbackMinS: Int, fallbackMaxS: Int): Boolean {
        val spec = LATE_SPECS[id]
        val min = (spec?.minS ?: fallbackMinS).toDouble()
        val max = (spec?.maxS ?: fallbackMaxS).toDouble()
        return tauS >= min && tauS <= max
    }
    /** Max fair-gap after fees, in dollars (21¢ is 0.21, not 21). */
    fun lateMaxGapDollars(id: String): Double = LATE_SPECS[id]?.maxGap ?: FairGapTune.MAX_GAP
    /**
     * Price band of a late-favourite leg. A price slider wins one side at a time.
     * r-late with no slider uses [Params.lateLoCents]/[Params.lateHiCents] (80–95¢), not the 24–42¢ gate.
     * Copies use [LATE_SPECS]. Anything else falls through to the fair-gap band.
     */
    fun lateBand(id: String, s: ScalpStrategySettings?): FairGapTune.Band {
        val sp = LATE_SPECS[id]
        val manual = s != null && (s.minEntryCents > 1 || s.maxEntryCents != null)
        if (!manual) {
            if (sp != null) return FairGapTune.Band(sp.loCents / 100.0, sp.hiCents / 100.0)
            if (id == LATE) {
                val p = Params()
                return FairGapTune.Band(p.lateLoCents / 100.0, p.lateHiCents / 100.0)
            }
            return FairGapTune.band(s)
        }
        if (sp != null || id == LATE) {
            val defLo = sp?.loCents ?: Params().lateLoCents
            val defHi = sp?.hiCents ?: Params().lateHiCents
            val lo = if (s!!.minEntryCents > 1) s.minEntryCents / 100.0 else defLo / 100.0
            val hi = s.maxEntryCents?.div(100.0) ?: (defHi / 100.0)
            return FairGapTune.Band(lo, maxOf(lo, hi))
        }
        return FairGapTune.band(s)
    }
    /** 1.0.21: r-kelly is visible again and frozen (no new paper trades unless Force trade). 1.0.14: + r-late-t1/t2. */
    val IDS = listOf(LATE, HOLD, MAKER, KELLY, LATE_T1, LATE_T2)
    fun isResearch(variantId: String) = variantId.startsWith(PREFIX)

    /** Frozen research parameters (sliders). */
    @Serializable
    data class Params(
        val edgeCents: Double = 2.0, val lateMinS: Int = 180, val lateMaxS: Int = 300,
        val lateLoCents: Int = 80, val lateHiCents: Int = 95, val makerEdgeCents: Double = 3.0,
        val kellyFraction: Double = 0.25, val windowCapUsd: Double = 0.0, val clip: Int = 10,
        val kellyBankrollUsd: Double = 1000.0
    ) {
        fun normalized() = copy(edgeCents = edgeCents.coerceIn(0.0, 20.0), lateMinS = lateMinS.coerceIn(30, 600),
            lateMaxS = lateMaxS.coerceIn(lateMinS.coerceIn(30, 600), 900), lateLoCents = lateLoCents.coerceIn(50, 98),
            lateHiCents = lateHiCents.coerceIn(lateLoCents.coerceIn(50, 98), 99), makerEdgeCents = makerEdgeCents.coerceIn(0.0, 20.0),
            kellyFraction = kellyFraction.coerceIn(0.0, 1.0), windowCapUsd = windowCapUsd.coerceIn(0.0, 100_000.0), clip = clip.coerceIn(1, 500), kellyBankrollUsd = kellyBankrollUsd.coerceIn(10.0, 1_000_000.0))
    }

    /** Kalshi taker fee for [n] contracts at [p]: ceil(0.07·C·P·(1−P)) cents. */
    fun fee(n: Int, p: Double): Double = ScalpRule.orderFee(n, p)

    private fun phi(x: Double): Double { // standard normal CDF (Abramowitz–Stegun 7.1.26 via erf)
        if (x == 0.0) return 0.5
        val t = 1.0 / (1.0 + 0.3275911 * kotlin.math.abs(x) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x / 2.0)
        return if (x >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
    }

    data class Fair(val pYes: Double, val brti: Boolean, val underlying: Double, val gapUsd: Double?)

    /**
     * Settlement-aware fair value: P(final 60 s BRTI average ≥ opening 60 s average = strike).
     * Uses the on-phone authenticated CF Benchmarks (BRTI) feed when fresh; otherwise falls back to exchange spot (flagged).
     * Before the final minute the averaged variance uses τ−40 s; inside it, k of 60 s are already fixed, so the remaining
     * sd shrinks to σ·√τ·(τ/60)/√3. σ = recent realized per-second vol from the app's spot history.
     */
    fun fair(q: ScalpRule.Quote): Fair? {
        val k = q.strike?.takeIf { it > 0 } ?: return null
        val sig = q.sigmaPerSec?.takeIf { it > 0 && it.isFinite() } ?: return null
        val brti = q.cfFresh()
        val u = (if (brti) q.cfSpot else q.spot)?.takeIf { it > 0 } ?: return null
        val tau = q.tauS
        if (tau <= 0) return Fair(if (u >= k) 1.0 else 0.0, brti, u, gap(q))
        val sd = if (tau > 60.0) sig * sqrt(tau - 40.0) else sig * sqrt(tau) * (tau / 60.0) / sqrt(3.0)
        val z = kotlin.math.ln(u / k) / maxOf(sd, 1e-9)
        return Fair(phi(z).coerceIn(0.0005, 0.9995), brti, u, gap(q))
    }
    private fun gap(q: ScalpRule.Quote): Double? = if (q.cfSpot != null && q.spot != null) q.spot - q.cfSpot else null

    /** Edge per contract after costs for buying at [ask] with fair [p]: (p − ask) − entry fee − expected exit cost. */
    fun edgeAfterCosts(p: Double, ask: Double, n: Int, holdToSettle: Boolean): Double {
        val entry = fee(n, ask) / n
        val exit = if (holdToSettle) 0.0 else fee(n, p) / n
        return (p - ask) - entry - exit
    }
    fun gateOk(p: Double, ask: Double, n: Int, holdToSettle: Boolean, edgeCents: Double) = edgeAfterCosts(p, ask, n, holdToSettle) > edgeCents / 100.0

    /** Quarter-Kelly style contracts: f = frac·max(0,(p−c)/(1−c)), c includes the fee; stake = f·bankroll, capped by [capUsd]. */
    fun kellyContracts(p: Double, ask: Double, frac: Double, bankrollUsd: Double, capUsd: Double): Int {
        val c = ask + fee(1, ask)
        if (c >= 1.0 || p <= c) return 0
        val f = frac * (p - c) / (1.0 - c)
        val stake = minOf(f * bankrollUsd, capUsd)
        return kotlin.math.floor(stake / c + 1e-9).toInt().coerceAtLeast(0)
    }

    /** Fair-value exit: take profit when bid beats fair plus exit fee; bail when fair falls below entry minus exit fee. */
    fun fairExit(p: Double, bid: Double, entry: Double, n: Int): String? {
        val ef = fee(n, bid) / n
        return when {
            bid > p + ef + 1e-9 -> "research: bid above fair + exit fee"
            p < entry - ef - 1e-9 -> "research: fair fell below entry − exit fee"
            else -> null
        }
    }

    /** Default per-strategy edge gate (¢) when the slider is untouched. */
    fun defaultEdge(id: String): Int = LATE_SPECS[id]?.edgeCents ?: if (id == LATE) 10 else 2 // 1.0.3: late favourite → fair-gap 10¢ minimum gap

    /**
     * "AI learns": after every close, move several sliders. The step scales with that trade's P&L.
     * Winners loosen (higher ceiling, lower floor down to the strategy floor, bigger clip).
     * Losers tighten and shrink. It never freezes or benches. A manual edit clears [ScalpStrategySettings.aiBaseCents]
     * and the next close starts from the values the owner set.
     */
    fun learn(code: String, s: ScalpStrategySettings, net: Double, defaultMaxCents: Int): ScalpStrategySettings {
        if (!s.aiLearns || !net.isFinite()) return s
        val floor = ScalpFloors.cents(code)
        val ceil = ScalpFloors.ceilingCents(code)
        val step = PaperSettingTuner.stepCents(net)
        val trackedN = s.aiN + 1
        val trackedNet = (if (s.aiNet.isFinite()) s.aiNet else 0.0) + net
        // One winning fill is not a sample. Loosen only after enough closes and a positive net.
        val winner = net > 0.0 && trackedN >= PaperSettingTuner.LOOSEN_MIN && trackedNet > 0.0
        if (net > 0.0 && !winner) {
            val held = String.format(java.util.Locale.US, "AI held settings (%d closes, net %+.2f; loosen after %d and a positive net)", trackedN, trackedNet, PaperSettingTuner.LOOSEN_MIN)
            return ScalpParamSanity.settings(code, s.copy(aiN = trackedN, aiNet = trackedNet, aiNote = held))
        }
        val notes = ArrayList<String>()
        val min0 = s.minEntryCents.coerceAtLeast(floor)
        var min = min0
        var max = s.maxEntryCents
        var edge = s.edgeCents
        var contracts = s.contracts
        var until = s.untilMinute
        var stopPct = s.stopLossPercent
        val anchor = s.aiBaseCents ?: if (isResearch(code)) s.edgeCents ?: defaultEdge(code) else s.maxEntryCents ?: defaultMaxCents
        if (isResearch(code)) {
            val cur = edge ?: defaultEdge(code)
            val base = s.aiBaseCents ?: cur
            val nx = if (winner) maxOf(cur - step, base) else minOf(cur + step, base + 12)
            if (nx != cur) notes += "edge ${cur}¢ → ${nx}¢"
            edge = nx
        } else {
            val cur = max ?: defaultMaxCents
            val base = s.aiBaseCents ?: cur
            val nx = if (winner) minOf(cur + step, base + 12, ceil) else maxOf(cur - step, base - 20, ScalpFloors.minCeilingCents(code))
            if (nx != cur) notes += "highest buy ${cur}¢ → ${nx}¢"
            max = nx
        }
        val clip0 = contracts ?: ScalpParamSanity.builtinClip(code)
        val clipCap = ScalpVariants.clipOf(code) ?: 100
        val clip1 = if (winner) (clip0 + step).coerceAtMost(clipCap) else (clip0 - step).coerceAtLeast(1)
        if (clip1 != clip0) {
            contracts = clip1
            notes += "clip $clip0 → $clip1"
        }
        val min1 = if (winner) (min - step).coerceAtLeast(floor) else (min + 1).coerceAtMost(((max ?: ceil) - 1).coerceAtLeast(floor))
        if (min1 != min) {
            min = min1
            notes += "lowest buy ${min0}¢ → ${min1}¢"
        }
        val until1 = if (winner) (until + 1).coerceAtMost(14) else (until - 1).coerceAtLeast(1)
        if (until1 != until) until = until1
        if (!isResearch(code) && stopPct != null && stopPct > 0) {
            val nsp = if (winner) (stopPct + 1).coerceAtMost(40) else (stopPct - 1).coerceAtLeast(1)
            if (nsp != stopPct) stopPct = nsp
        }
        val money = String.format(java.util.Locale.US, "%+.2f", net)
        val why = if (winner) "winner" else "loser"
        val note = if (notes.isEmpty()) "AI kept settings ($why $money)" else "AI $why $money · " + notes.joinToString(", ")
        return ScalpParamSanity.settings(code, s.copy(
            edgeCents = edge,
            maxEntryCents = max,
            minEntryCents = min,
            contracts = contracts,
            untilMinute = until,
            stopLossPercent = stopPct,
            aiBaseCents = anchor,
            aiN = trackedN,
            aiNet = trackedNet,
            aiNote = note
        ))
    }

    /** Ceil helper used by tests. */
    fun feeCents(n: Int, p: Double): Int = kotlin.math.round(ScalpRule.orderFee(n, p) * 100.0).toInt()
}
