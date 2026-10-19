package com.dirk.kalshiodds.decision

import kotlinx.serialization.Serializable
import kotlin.math.ceil
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
    /** t1 = tighter (2–4 min left, edge ≥ 12¢, 26–42¢); t2 = looser (1.5–6 min left, edge ≥ 8¢, gap ≤ 23¢, 22–44¢). */
    val LATE_SPECS: Map<String, LateSpec> = mapOf(
        LATE_T1 to LateSpec(minS = 120, maxS = 240, edgeCents = 12, maxGap = 0.21, loCents = 26, hiCents = 42),
        LATE_T2 to LateSpec(minS = 90, maxS = 360, edgeCents = 8, maxGap = 0.23, loCents = 22, hiCents = 44)
    )
    fun isLate(id: String): Boolean = id == LATE || id in LATE_SPECS
    /** Price band of a late-favourite leg: your price sliders win, else the copy's own band, else the fair-gap 24–42¢. */
    fun lateBand(id: String, s: ScalpStrategySettings?): FairGapTune.Band {
        val sp = LATE_SPECS[id]
        val manual = s != null && (s.minEntryCents > 1 || s.maxEntryCents != null)
        return if (sp == null || manual) FairGapTune.band(s) else FairGapTune.Band(sp.loCents / 100.0, sp.hiCents / 100.0)
    }
    /** 1.0.10: r-kelly removed from the registry (KELLY kept so old saved trades still close/decode). 1.0.14: + r-late-t1/t2. */
    val IDS = listOf(LATE, HOLD, MAKER, LATE_T1, LATE_T2)
    fun isResearch(variantId: String) = variantId.startsWith(PREFIX)

    /** Frozen research parameters (sliders). */
    @Serializable
    data class Params(
        val edgeCents: Double = 2.0, val lateMinS: Int = 120, val lateMaxS: Int = 300,
        val lateLoCents: Int = 80, val lateHiCents: Int = 95, val makerEdgeCents: Double = 3.0,
        val kellyFraction: Double = 0.25, val windowCapUsd: Double = 100.0, val clip: Int = 10,
        val kellyBankrollUsd: Double = 1000.0
    ) {
        fun normalized() = copy(edgeCents = edgeCents.coerceIn(0.0, 20.0), lateMinS = lateMinS.coerceIn(30, 600),
            lateMaxS = lateMaxS.coerceIn(lateMinS.coerceIn(30, 600), 900), lateLoCents = lateLoCents.coerceIn(50, 98),
            lateHiCents = lateHiCents.coerceIn(lateLoCents.coerceIn(50, 98), 99), makerEdgeCents = makerEdgeCents.coerceIn(0.0, 20.0),
            kellyFraction = kellyFraction.coerceIn(0.0, 1.0), windowCapUsd = windowCapUsd.coerceIn(1.0, 100_000.0), clip = clip.coerceIn(1, 500), kellyBankrollUsd = kellyBankrollUsd.coerceIn(10.0, 1_000_000.0))
    }

    /** Kalshi taker fee for [n] contracts at [p]: ceil(0.07·C·P·(1−P)) cents. */
    fun fee(n: Int, p: Double): Double = ScalpRule.orderFee(n, p)

    private fun phi(x: Double): Double { // standard normal CDF (Abramowitz–Stegun 7.1.26 via erf)
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
     * "AI learns" (all strategies): every 3 closed paper trades, nudge ONE slider by 1¢ from the strategy's own net.
     * Research → edge gate (+1¢ after a losing batch, −1¢ after a winning batch, never below your setting, at most +4¢).
     * Others → highest buy price (−1¢ after a losing batch, +1¢ back after a winning one, never above your setting, at most −10¢).
     */
    fun learn(code: String, s: ScalpStrategySettings, net: Double, defaultMaxCents: Int): ScalpStrategySettings {
        if (!s.aiLearns) return s
        val n = s.aiN + 1; val sum = s.aiNet + net
        if (n < 3) return s.copy(aiN = n, aiNet = sum)
        val money = String.format(java.util.Locale.US, "%+.2f", sum)
        return if (isResearch(code)) {
            val cur = s.edgeCents ?: defaultEdge(code); val base = s.aiBaseCents ?: cur
            val nx = if (sum < 0) minOf(cur + 1, base + 4) else maxOf(cur - 1, base)
            s.copy(edgeCents = nx, aiBaseCents = base, aiN = 0, aiNet = 0.0,
                aiNote = if (nx == cur) "AI kept edge ${cur}¢ (last 3 trades $money)" else "AI moved edge ${cur}¢ → ${nx}¢ (last 3 trades $money)")
        } else {
            val cur = s.maxEntryCents ?: defaultMaxCents; val base = s.aiBaseCents ?: cur
            val nx = if (sum < 0) maxOf(cur - 1, base - 10, s.minEntryCents) else minOf(cur + 1, base)
            s.copy(maxEntryCents = nx, aiBaseCents = base, aiN = 0, aiNet = 0.0,
                aiNote = if (nx == cur) "AI kept highest buy ${cur}¢ (last 3 trades $money)" else "AI moved highest buy ${cur}¢ → ${nx}¢ (last 3 trades $money)")
        }
    }

    /** Ceil helper used by tests. */
    fun feeCents(n: Int, p: Double): Int = ceil(7.0 * n * p * (1.0 - p) - 1e-9).toInt()
}
