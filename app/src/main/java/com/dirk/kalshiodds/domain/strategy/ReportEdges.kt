package com.dirk.kalshiodds.domain.strategy

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Strategies derived from the KXBTC15M structural-edges report
 * ("Outside-the-Box Ways to Win Kalshi's 15-Minute Bitcoin Market").
 *
 * These complement the classic indicator set in [Strategies.kt] with edges
 * that are structural rather than predictive:
 *  - 15-minute sign reversal after flow-driven moves (arXiv 2608.21888:
 *    sign-flip rate 50.2% → 53.0% from smallest to largest prior-move decile,
 *    weaker in funding-settlement hours).
 *  - Final-minute settlement mathematics: payout is the average of 60
 *    one-second RTI prints, so the outcome locks in progressively; retail
 *    chases the spot print while the running average already decided it.
 *  - Tie resolves Yes — a permanent unpriced tilt near the line.
 *  - Quarter-hour boundary flow fade (arXiv 2607.09426).
 *  - Window selection: funding hours (00/08/16 UTC), 8:30 ET macro prints,
 *    and the 21:00 UTC liquidity trough change payoff geometry.
 *
 * All signals remain advisory — analysis-only, never orders. The report's
 * core execution rule (maker-only; taker fee 0.07·P·(1−P) exceeds the
 * measured +0.67¢/contract gross edge) is enforced downstream by the app's
 * fee model and is documented in each rationale.
 */

/** Clock-based window-selection helpers (pure functions, unit-testable). */
object WindowRegime {

    /** Funding settlement hours in UTC: 00:00, 08:00, 16:00. */
    val FUNDING_HOURS_UTC = setOf(0, 8, 16)

    /** Hour (UTC) of the documented institutional liquidity withdrawal. */
    const val LIQUIDITY_TROUGH_HOUR_UTC = 21

    /** 8:30 AM ET = 13:30 UTC (EDT) or 12:30 UTC (EST); cover both. */
    val MACRO_MINUTES_UTC = setOf(13 * 60 + 30, 12 * 60 + 30)

    fun utcHour(nowMs: Long): Int =
        Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.UTC).hour

    fun utcMinuteOfDay(nowMs: Long): Int =
        Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.UTC).let { it.hour * 60 + it.minute }

    /** True when the window contains a perp funding settlement hour. */
    fun isFundingHour(nowMs: Long): Boolean = utcHour(nowMs) in FUNDING_HOURS_UTC

    /** True within ±15 min of the 8:30 AM ET macro release. */
    fun isMacroWindow(nowMs: Long): Boolean {
        val m = utcMinuteOfDay(nowMs)
        return MACRO_MINUTES_UTC.any { abs(m - it) <= 15 }
    }

    /** True during the 21:00 UTC depth trough hour. */
    fun isLiquidityTrough(nowMs: Long): Boolean = utcHour(nowMs) == LIQUIDITY_TROUGH_HOUR_UTC

    /**
     * 0.0–1.0 multiplier on strategy confidence for this window.
     * Reversal quality degrades in funding hours (per the reversal study);
     * thin-depth windows raise single-exchange/RTI divergence risk.
     */
    fun qualityMultiplier(nowMs: Long): Double = when {
        isFundingHour(nowMs) -> 0.6
        isLiquidityTrough(nowMs) -> 0.8
        else -> 1.0
    }
}

/**
 * 15-minute sign-reversal tilt (report Edge 3.1). Bets against the previous
 * completed 15-minute candle's direction; confidence scales with the prior
 * move's size rank among recent moves (50.2% smallest → 53.0% largest
 * decile, per the matched cross-market study). Directional tilt only —
 * intended for maker-side shading, never taker entries. Suppressed in
 * funding-settlement hours where the study measured degraded accuracy.
 */
object SignReversalTilt : Strategy {
    override val name = "Reversal"

    private const val LOOKBACK = 40

    override fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal? {
        if (candles.size < Strategy.MIN_CANDLES) return null
        if (WindowRegime.isFundingHour(ctx.nowMs)) return null
        val last = candles.size - 1
        // Previous completed candle's signed move.
        val prevMove = candles[last].close - candles[last].open
        if (prevMove == 0.0) return null
        // Rank |prevMove| among recent candle ranges as the size decile.
        val window = candles.subList(max(0, last - LOOKBACK), last)
        val sizes = window.map { abs(it.close - it.open) }.sorted()
        val rank = sizes.count { it < abs(prevMove) }.toDouble() / max(1, sizes.size)
        val flipProb = 0.502 + (0.530 - 0.502) * rank
        val confidence = flipProb * WindowRegime.qualityMultiplier(ctx.nowMs)
        val direction = if (prevMove > 0) Direction.DOWN else Direction.UP
        val pct = (flipProb * 1000).roundToInt() / 10.0
        val rationale = "fade prior ${if (prevMove > 0) "up" else "down"} candle " +
            "(${pct}% sign-flip, size decile ${(rank * 10).roundToInt()})"
        return StrategySignal(name, direction, confidence, rationale, ctx.windowCloseMs)
    }
}

/**
 * Final-minute settlement math (report Edge 2.1 / 2.2). Settlement is the
 * mean of 60 one-second RTI prints in the last minute; with n seconds
 * elapsed, n/60 of the outcome is already locked. Fair P(UP) is computed
 * from the running average versus the opening reference, with the remaining
 * seconds treated as the only free variables. Emits when the live contract
 * price diverges from that fair value by >= [GAP_CENTS_MIN] — i.e. when the
 * crowd is chasing a spot spike the average can no longer reach.
 *
 * Requires [WindowContext.settlement] (running RTI samples); returns null
 * without it. Active only in the final [ACTIVE_MS].
 */
object SettlementAverage : Strategy {
    override val name = "Settlement"

    const val ACTIVE_MS = 75_000L
    const val GAP_CENTS_MIN = 6.0
    const val SAMPLES_PER_MIN = 60

    override fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal? {
        val s = ctx.settlement ?: return null
        val leftMs = ctx.windowCloseMs - ctx.nowMs
        if (leftMs <= 0 || leftMs > ACTIVE_MS) return null
        val marketCents = ctx.marketUpPriceCents ?: return null
        if (s.samples.isEmpty() || s.openReference <= 0.0) return null

        val n = min(SAMPLES_PER_MIN, s.samples.size)
        val runningAvg = s.samples.takeLast(n).average()
        val lockedFrac = n.toDouble() / SAMPLES_PER_MIN
        val remaining = SAMPLES_PER_MIN - n

        // Best/worst achievable final average if every remaining second prints
        // at the current RTI — the range the outcome can still reach.
        val rtiNow = s.samples.last()
        val bestAvg = (runningAvg * n + max(rtiNow, rtiNow * 1.0005) * remaining) / SAMPLES_PER_MIN
        val worstAvg = (runningAvg * n + min(rtiNow, rtiNow * 0.9995) * remaining) / SAMPLES_PER_MIN

        val fairUp: Double = when {
            worstAvg > s.openReference -> 1.0          // cannot flip down anymore
            bestAvg < s.openReference -> 0.0           // cannot flip up anymore
            else -> {
                // Interpolate how much of the remaining range favors up,
                // plus the tie-pays-Yes tilt near the line.
                val pos = (rtiNow - (s.openReference - (runningAvg - s.openReference)))
                val frac = if (worstAvg < bestAvg) {
                    (bestAvg - s.openReference) / (bestAvg - worstAvg)
                } else 0.5
                val tilt = if (abs(runningAvg - s.openReference) / s.openReference < 0.0002) 0.02 else 0.0
                (frac + tilt).coerceIn(0.0, 1.0).also { if (pos.isNaN()) return null }
            }
        }

        val gapCents = fairUp * 100.0 - marketCents
        if (abs(gapCents) < GAP_CENTS_MIN) return null
        val direction = if (gapCents > 0) Direction.UP else Direction.DOWN
        val confidence = (0.55 + min(0.30, abs(gapCents) / 100.0)) *
            WindowRegime.qualityMultiplier(ctx.nowMs)
        val lockedPct = (lockedFrac * 100).roundToInt()
        val rationale = "${lockedPct}% of settlement locked (avg ${runningAvg.roundToInt()} " +
            "vs ref ${s.openReference.roundToInt()}); fair UP ${(fairUp * 100).roundToInt()}¢ " +
            "vs market ${marketCents}¢"
        return StrategySignal(name, direction, confidence, rationale, ctx.windowCloseMs)
    }
}

/**
 * Quarter-hour boundary fade (report Edge 4.2). The opening seconds of each
 * :00/:15/:30/:45 window carry abnormal algorithmic flow; documented
 * short-run reversal follows boundary imbalance. When the first candle of
 * the current window moved more than [MOVE_ATR_MULT] × ATR in its opening
 * stretch, fade it. Active only in the first [EARLY_MS] of the window.
 */
object BoundaryFade : Strategy {
    override val name = "Boundary"

    const val EARLY_MS = 120_000L
    private const val MOVE_ATR_MULT = 0.8

    override fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal? {
        if (candles.size < Strategy.MIN_CANDLES) return null
        val windowStartMs = ctx.windowCloseMs - Strategy.WINDOW_MS
        val elapsedMs = ctx.nowMs - windowStartMs
        if (elapsedMs <= 0 || elapsedMs > EARLY_MS) return null

        val atr = Indicators.atr(candles).lastOrNull { it != null } ?: return null
        val cur = candles.last()
        val burst = cur.close - cur.open
        if (abs(burst) < MOVE_ATR_MULT * atr) return null

        val direction = if (burst > 0) Direction.DOWN else Direction.UP
        val confidence = 0.53 * WindowRegime.qualityMultiplier(ctx.nowMs)
        val rationale = "opening burst ${(burst / atr * 10).roundToInt() / 10.0}× ATR at " +
            "quarter-hour boundary; fading boundary flow"
        return StrategySignal(name, direction, confidence, rationale, ctx.windowCloseMs)
    }
}
