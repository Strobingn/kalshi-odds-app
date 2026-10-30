package com.dirk.kalshiodds.decision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Paper-only Hybrid Quant entry gates. Each one is its own pref.
 * Multi-timeframe and the strong-move score default off. Fibonacci defaults to log-only.
 * Bars are closed before the decision: a tick at or after [decisionMs] never enters a feature.
 * AI Learn and [PaperSettingTuner] do not write these prefs.
 */
object HybridQuantGates {
    const val MTF_DISAGREE = "mtf_disagree"
    const val STRONG_MOVE_LOW = "strong_move_low"
    const val FIB_BARRIER = "fib_barrier"
    const val MINUTE_MS = 60_000L
    const val FIVE_MS = 300_000L
    /** ofiFeature is already in (-1, 1) and signed with the trade. At or under this is weak or against. */
    const val OFI_WEAK = 0.20
    val FIB_RATIOS = doubleArrayOf(0.382, 0.5, 0.618)
    val REASON_CODES = listOf(MTF_DISAGREE, STRONG_MOVE_LOW, FIB_BARRIER)

    enum class FibMode {
        OFF, LOG_ONLY, BLOCK;

        companion object {
            fun from(raw: String?): FibMode = entries.firstOrNull { it.name == raw } ?: LOG_ONLY
        }
    }

    data class Prefs(
        val mtfAgree: Boolean = false,
        val strongMove: Boolean = false,
        val fibMode: FibMode = FibMode.LOG_ONLY
    )

    data class MinuteBar(
        val startMs: Long,
        val endMs: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

    data class MoveClasses(val strong: Double, val weak: Double, val other: Double)

    data class FibRead(
        val fibPos: Double?,
        val signedDist: Double?,
        val barrier: Boolean,
        val nearestRatio: Double?
    )

    data class Verdict(
        val enter: Boolean,
        val reason: String,
        val pStrongCents: Double?,
        val fibPos: Double?,
        val signedDist: Double?,
        val fibBarrier: Boolean
    )

    /**
     * k in sigma units so an at-the-money Student-t (ν = 4.2) digital moves about
     * [PaperFleetDefaults.PROFIT_CENTS]. Delta at z = 0 is the density, so
     * k = (take-profit as a probability) / pdf(0). The same k is a larger dollar
     * distance when time-to-close sigma is larger.
     */
    fun strongK(takeProfitCents: Int = PaperFleetDefaults.PROFIT_CENTS): Double {
        val pdf0 = HftSignals.studentTPdf(0.0, HftSignals.NU)
        if (!pdf0.isFinite() || pdf0 <= 1e-9) return 1.0
        return (takeProfitCents / 100.0 / pdf0).coerceIn(0.05, 4.0)
    }

    /** Dollar distance from the strike to the strong-move boundary. Grows with sigma and with time. */
    fun strongBoundaryDistance(strike: Double, sigmaPerSec: Double, tauS: Double, k: Double = strongK()): Double {
        if (!strike.isFinite() || strike <= 0.0 || !sigmaPerSec.isFinite() || sigmaPerSec <= 0.0 || tauS <= 0.0) return 0.0
        val scale = sigmaPerSec * sqrt(tauS)
        return strike * (exp(k * scale) - 1.0)
    }

    /**
     * Three-way settle under the same Student-t scale as [HftSignals.studentTFair].
     * Strong: terminal spot is at least k·σ past the strike in the trade direction.
     * Other: terminal spot finishes on the other side of the strike.
     * Weak: the remaining mass, on our side but near the strike.
     */
    fun moveClasses(
        spot: Double?,
        strike: Double?,
        sigmaPerSec: Double?,
        tauS: Double,
        side: String,
        k: Double = strongK()
    ): MoveClasses? {
        if (spot == null || strike == null || sigmaPerSec == null) return null
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        if (!sigmaPerSec.isFinite() || sigmaPerSec <= 0.0 || tauS <= 0.0) return null
        val scale = sigmaPerSec * sqrt(tauS)
        if (scale <= 1e-12) return null
        val d = ln(spot / strike) / scale
        val up = isUp(side) ?: return null
        val pStrong = if (up) HftSignals.studentTCdf(d - k) else HftSignals.studentTCdf(-d - k)
        val pOther = if (up) HftSignals.studentTCdf(-d) else HftSignals.studentTCdf(d)
        val strong = pStrong.coerceIn(0.0, 1.0)
        val other = pOther.coerceIn(0.0, 1.0 - strong)
        val weak = (1.0 - strong - other).coerceIn(0.0, 1.0)
        return MoveClasses(strong, weak, other)
    }

    fun pStrong(
        spot: Double?,
        strike: Double?,
        sigmaPerSec: Double?,
        tauS: Double,
        side: String,
        k: Double = strongK()
    ): Double? = moveClasses(spot, strike, sigmaPerSec, tauS, side, k)?.strong

    /** Entry ask in cents plus the per-contract taker fee from [ScalpRule.orderFee]. */
    fun hurdleCents(ask: Double): Double {
        if (!ask.isFinite() || ask <= 0.0 || ask >= 1.0) return Double.POSITIVE_INFINITY
        return ask * 100.0 + ScalpRule.orderFee(1, ask) * 100.0
    }

    fun strongClears(pStrong: Double, ask: Double): Boolean =
        pStrong.isFinite() && pStrong * 100.0 > hurdleCents(ask)

    /** Completed 1-minute bars. A bar is closed only when its end is at or before [decisionMs]. Ticks at or after [decisionMs] are dropped. */
    fun completedMinuteBars(ticks: List<Pair<Long, Double>>, decisionMs: Long): List<MinuteBar> {
        if (ticks.isEmpty()) return emptyList()
        val groups = HashMap<Long, ArrayList<Pair<Long, Double>>>()
        for ((at, px) in ticks) {
            if (at >= decisionMs || !px.isFinite() || px <= 0.0) continue
            val start = at / MINUTE_MS * MINUTE_MS
            val end = start + MINUTE_MS
            if (end > decisionMs) continue
            groups.getOrPut(start) { ArrayList() }.add(at to px)
        }
        return groups.map { (start, pts) ->
            val ordered = pts.sortedBy { it.first }
            MinuteBar(
                startMs = start,
                endMs = start + MINUTE_MS,
                open = ordered.first().second,
                high = ordered.maxOf { it.second },
                low = ordered.minOf { it.second },
                close = ordered.last().second
            )
        }.sortedBy { it.startMs }
    }

    /** Close-to-close of the last two completed 1-minute bars. One bar uses its own close minus open. */
    fun trend1m(bars: List<MinuteBar>): Double? {
        if (bars.isEmpty()) return null
        if (bars.size == 1) return bars[0].close - bars[0].open
        return bars.last().close - bars[bars.size - 2].close
    }

    /** Close-to-close of the last two completed 5-minute buckets. One bucket uses close minus open. */
    fun trend5m(bars: List<MinuteBar>, decisionMs: Long): Double? {
        val fives = completedFive(bars, decisionMs)
        if (fives.isEmpty()) return null
        if (fives.size == 1) return fives[0].close - fives[0].open
        return fives.last().close - fives[fives.size - 2].close
    }

    /** YES/UP needs both slopes ≥ 0. NO/DOWN needs both slopes ≤ 0. */
    fun signsAgree(side: String, slope1m: Double, slope5m: Double): Boolean {
        if (!slope1m.isFinite() || !slope5m.isFinite()) return false
        val up = isUp(side) ?: return false
        return if (up) slope1m >= 0.0 && slope5m >= 0.0 else slope1m <= 0.0 && slope5m <= 0.0
    }

    fun fib(
        bars: List<MinuteBar>,
        spot: Double?,
        strike: Double?,
        side: String,
        ofiWithTrade: Double,
        sigmaPerSec: Double?
    ): FibRead {
        val blank = FibRead(null, null, false, null)
        if (bars.size < 2 || spot == null || strike == null) return blank
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return blank
        val high = bars.maxOf { it.high }
        val low = bars.minOf { it.low }
        val range = high - low
        if (!range.isFinite() || range <= 1e-12 || high == low) return blank
        val fibPos = (spot - low) / range
        if (!fibPos.isFinite()) return blank
        var nearestRatio = FIB_RATIOS[0]
        var nearest = high - range * nearestRatio
        var nearestAbs = abs(nearest - strike)
        val levels = DoubleArray(FIB_RATIOS.size)
        for (i in FIB_RATIOS.indices) {
            val level = high - range * FIB_RATIOS[i]
            levels[i] = level
            val dist = abs(level - strike)
            if (dist < nearestAbs) {
                nearestAbs = dist
                nearest = level
                nearestRatio = FIB_RATIOS[i]
            }
        }
        val atr = atr(bars)
        val sigmaPx = if (sigmaPerSec != null && sigmaPerSec.isFinite() && sigmaPerSec > 0.0) {
            spot * sigmaPerSec * sqrt(60.0)
        } else 0.0
        val unit = when {
            atr > 1e-12 -> atr
            sigmaPx > 1e-12 -> sigmaPx
            else -> 0.0
        }
        val signed = if (unit > 0.0) (nearest - strike) / unit else null
        val up = isUp(side)
        val barrier = up != null && ofiWeak(ofiWithTrade) && levelBetween(levels, spot, strike, up)
        return FibRead(fibPos, signed?.takeIf { it.isFinite() }, barrier, nearestRatio)
    }

    fun evaluate(
        prefs: Prefs,
        side: String,
        ask: Double,
        spot: Double?,
        strike: Double?,
        sigmaPerSec: Double?,
        tauS: Double,
        decisionMs: Long,
        windowStartMs: Long,
        ticks: List<Pair<Long, Double>>,
        ofiWithTrade: Double,
        priorEnter: Boolean,
        priorReason: String
    ): Verdict {
        val bars = completedMinuteBars(ticks, decisionMs)
        val inWindow = bars.filter { it.startMs >= windowStartMs && it.endMs <= decisionMs }
        val p = pStrong(spot, strike, sigmaPerSec, tauS, side)
        val pCents = p?.times(100.0)
        val fibRead = fib(inWindow, spot, strike, side, ofiWithTrade, sigmaPerSec)
        if (!priorEnter) {
            return Verdict(false, priorReason, pCents, fibRead.fibPos, fibRead.signedDist, fibRead.barrier)
        }
        if (prefs.mtfAgree) {
            val s1 = trend1m(bars)
            val s5 = trend5m(bars, decisionMs)
            if (s1 == null || s5 == null || !signsAgree(side, s1, s5)) {
                return Verdict(false, MTF_DISAGREE, pCents, fibRead.fibPos, fibRead.signedDist, fibRead.barrier)
            }
        }
        if (prefs.strongMove) {
            if (p == null || !strongClears(p, ask)) {
                return Verdict(false, STRONG_MOVE_LOW, pCents, fibRead.fibPos, fibRead.signedDist, fibRead.barrier)
            }
        }
        if (fibRead.barrier && prefs.fibMode == FibMode.BLOCK) {
            return Verdict(false, FIB_BARRIER, pCents, fibRead.fibPos, fibRead.signedDist, true)
        }
        val reason = if (fibRead.barrier && prefs.fibMode == FibMode.LOG_ONLY && !priorReason.contains(FIB_BARRIER)) {
            "$priorReason · $FIB_BARRIER"
        } else priorReason
        return Verdict(true, reason, pCents, fibRead.fibPos, fibRead.signedDist, fibRead.barrier && prefs.fibMode != FibMode.OFF)
    }

    private data class FiveBar(val startMs: Long, val open: Double, val close: Double)

    private fun completedFive(bars: List<MinuteBar>, decisionMs: Long): List<FiveBar> {
        val grouped = HashMap<Long, ArrayList<MinuteBar>>()
        for (bar in bars) {
            if (bar.endMs > decisionMs) continue
            val start = bar.startMs / FIVE_MS * FIVE_MS
            if (start + FIVE_MS > decisionMs) continue
            grouped.getOrPut(start) { ArrayList() }.add(bar)
        }
        return grouped.map { (start, mins) ->
            val ordered = mins.sortedBy { it.startMs }
            FiveBar(start, ordered.first().open, ordered.last().close)
        }.sortedBy { it.startMs }
    }

    private fun atr(bars: List<MinuteBar>): Double {
        if (bars.size < 2) return 0.0
        var sum = 0.0
        var n = 0
        for (i in 1 until bars.size) {
            val prev = bars[i - 1].close
            val bar = bars[i]
            val tr = max(bar.high - bar.low, max(abs(bar.high - prev), abs(bar.low - prev)))
            if (tr.isFinite() && tr >= 0.0) {
                sum += tr
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun levelBetween(levels: DoubleArray, spot: Double, strike: Double, up: Boolean): Boolean {
        val toward = if (up) 1.0 else -1.0
        for (level in levels) {
            if ((level - spot) * toward > 0.0 && (strike - level) * toward > 0.0) return true
        }
        return false
    }

    private fun ofiWeak(ofiWithTrade: Double): Boolean =
        !ofiWithTrade.isFinite() || ofiWithTrade <= OFI_WEAK

    private fun isUp(side: String): Boolean? = when (side.uppercase()) {
        "YES", "UP" -> true
        "NO", "DOWN" -> false
        else -> null
    }
}
