package com.dirk.kalshiodds.signal.scalper

import kotlin.math.floor

/**
 * The numbers behind every chart on the Arena tab, worked out from the
 * paper record ([ScalperState]) and the trade file ([ScalpTrade]). Pure and
 * unit tested; the composables in `ui/ArenaChartViews.kt` only draw them.
 * Paper dollars after fees throughout.
 */
object ArenaCharts {

    /** One strategy's running P&L, one point per closed trade (thinned to [maxPoints]). */
    data class Curve(val strategy: ScalpStrategy, val points: List<CurvePoint>) {
        val last: Double get() = points.lastOrNull()?.pnlUsd ?: 0.0
    }

    /**
     * Running P&L per strategy over [trades] (any order), oldest first. Every
     * curve starts at $0 at the first trade shown, so the curves race from the
     * same line. Strategies with no trade in [trades] are left out.
     */
    fun equityCurves(trades: List<ScalpTrade>, maxPoints: Int = 160): List<Curve> {
        if (trades.isEmpty()) return emptyList()
        val sorted = trades.sortedBy { it.closedAtMs }
        val t0 = sorted.first().closedAtMs
        val byName = ScalpStrategy.values().associateBy { it.name }
        return sorted.groupBy { it.strategy }
            .mapNotNull { (name, rows) ->
                val st = byName[name] ?: return@mapNotNull null
                var run = 0.0
                val pts = ArrayList<CurvePoint>(rows.size + 1)
                pts += CurvePoint(t0, 0.0)
                for (r in rows) {
                    run += r.pnlUsd
                    pts += CurvePoint(r.closedAtMs, run)
                }
                Curve(st, thin(pts, maxPoints))
            }
            .sortedBy { it.strategy.ordinal }
    }

    /** At most [maxPoints] points, always keeping the first and the last. */
    fun thin(points: List<CurvePoint>, maxPoints: Int): List<CurvePoint> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        return (0 until maxPoints).map { i -> points[(i * step).toInt().coerceAtMost(points.lastIndex)] }
            .let { it.dropLast(1) + points.last() }
    }

    /**
     * The curves worth colouring: the top [n] by final P&L, plus [selected]
     * when it is not already among them. Everything else is drawn grey.
     */
    fun highlighted(curves: List<Curve>, selected: ScalpStrategy?, n: Int = 3): List<Curve> {
        val top = curves.sortedByDescending { it.last }.take(n)
        val sel = selected?.let { s -> curves.firstOrNull { it.strategy == s } }
        return if (sel == null || sel in top) top else top + sel
    }

    /** Value of [curve] at time [tMs]: the last point at or before it, $0 before the first. */
    fun valueAt(curve: Curve, tMs: Long): Double {
        var v = 0.0
        for (p in curve.points) {
            if (p.tMs > tMs) break
            v = p.pnlUsd
        }
        return v
    }

    /** One histogram bin of per-trade results, in cents per contract: [loCents, hiCents). Open ends are infinite. */
    data class Bin(val loCents: Double, val hiCents: Double, val count: Int) {
        val label: String
            get() = when {
                loCents == Double.NEGATIVE_INFINITY -> "under ${fmtC(hiCents)}"
                hiCents == Double.POSITIVE_INFINITY -> "${fmtC(loCents)} or more"
                else -> "${fmtC(loCents)} to ${fmtC(hiCents)}"
            }
        /** Bins that hold losses are drawn as losses. */
        val negative: Boolean get() = hiCents <= 0.0
    }

    /** Histogram edges: 2¢ bins from −20¢ to +20¢, then one open bin at each end (holds land there). */
    const val BIN_CENTS = 2.0
    const val BIN_LIMIT_CENTS = 20.0

    /** Result per contract of one trade, in cents, after fees. */
    fun centsPerContract(t: ScalpTrade): Double = if (t.contracts > 0) t.pnlUsd / t.contracts * 100.0 else 0.0

    fun histogram(trades: List<ScalpTrade>, strategy: ScalpStrategy? = null): List<Bin> {
        val inner = ((2 * BIN_LIMIT_CENTS) / BIN_CENTS).toInt()
        val counts = IntArray(inner + 2)
        for (t in trades) {
            if (strategy != null && t.strategy != strategy.name) continue
            val c = centsPerContract(t)
            val i = when {
                c < -BIN_LIMIT_CENTS -> 0
                c >= BIN_LIMIT_CENTS -> inner + 1
                else -> 1 + floor((c + BIN_LIMIT_CENTS) / BIN_CENTS).toInt().coerceIn(0, inner - 1)
            }
            counts[i]++
        }
        return (0 until inner + 2).map { i ->
            when (i) {
                0 -> Bin(Double.NEGATIVE_INFINITY, -BIN_LIMIT_CENTS, counts[0])
                inner + 1 -> Bin(BIN_LIMIT_CENTS, Double.POSITIVE_INFINITY, counts[i])
                else -> {
                    val lo = -BIN_LIMIT_CENTS + (i - 1) * BIN_CENTS
                    Bin(lo, lo + BIN_CENTS, counts[i])
                }
            }
        }
    }

    /** P&L of each hour of the day (the phone's clock), 0–23; null for an hour with no closed trade. */
    fun hourly(state: ScalperState): List<Double?> = (0..23).map { h ->
        state.groups[ScalperState.hourKey(h)]?.takeIf { it.closed > 0 }?.pnlUsd
    }

    /** How one strategy's trades ended. */
    data class ExitMix(val strategy: ScalpStrategy, val target: Int, val stop: Int, val timeout: Int, val settled: Int) {
        val total: Int get() = target + stop + timeout + settled
    }

    /** Strategies that have closed a trade, in leaderboard order. */
    fun exitMix(state: ScalperState): List<ExitMix> =
        ScalperResults.leaderboard(state).filter { it.stats.closed > 0 }.map {
            ExitMix(it.strategy, it.stats.targets, it.stats.stops, it.stats.timeouts, it.stats.settled)
        }

    /** Win rate and trade count per strategy that has closed a trade, in leaderboard order. */
    data class WinRate(val strategy: ScalpStrategy, val rate: Double, val closed: Int)

    fun winRates(state: ScalperState): List<WinRate> =
        ScalperResults.leaderboard(state).mapNotNull { r ->
            r.stats.winRate?.let { WinRate(r.strategy, it, r.stats.closed) }
        }

    /** Before fees (net + fees) and after fees, per strategy that has closed a trade, in leaderboard order. */
    data class FeeDrag(val strategy: ScalpStrategy, val grossUsd: Double, val netUsd: Double, val feesUsd: Double)

    fun feeDrag(state: ScalperState): List<FeeDrag> =
        ScalperResults.leaderboard(state).filter { it.stats.closed > 0 }.map {
            FeeDrag(it.strategy, it.stats.pnlUsd + it.stats.feesUsd, it.stats.pnlUsd, it.stats.feesUsd)
        }

    /** Nice round axis bounds that include 0 and both [lo] and [hi]. */
    fun niceRange(lo: Double, hi: Double): Pair<Double, Double> {
        val a = minOf(lo, 0.0)
        val b = maxOf(hi, 0.0)
        if (b - a < 1e-9) return -1.0 to 1.0
        val step = niceStep((b - a) / 4.0)
        return floor(a / step) * step to kotlin.math.ceil(b / step) * step
    }

    /** 1, 2 or 5 × a power of ten, at least [raw]. */
    fun niceStep(raw: Double): Double {
        if (raw <= 0.0 || !raw.isFinite()) return 1.0
        val mag = Math.pow(10.0, floor(kotlin.math.log10(raw)))
        val f = raw / mag
        val nice = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * mag
    }

    private fun fmtC(c: Double): String {
        val v = if (c == floor(c)) c.toInt().toString() else String.format(java.util.Locale.US, "%.1f", c)
        return (if (c > 0) "+" else if (c < 0) "−" else "") + v.removePrefix("-") + "¢"
    }
}
