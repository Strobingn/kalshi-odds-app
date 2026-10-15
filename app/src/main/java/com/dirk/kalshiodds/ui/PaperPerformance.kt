package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.scalp.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Pure chart preparation shared by the phone and graph regression tests. No reconstructed backtest points. */
object PaperPerformance {
    data class Point(val atMs: Long, val value: Double)
    data class Series(val id: String, val name: String, val points: List<Point>)
    data class Axis(val low: Double, val high: Double, val step: Double)
    val families = listOf("adaptive" to "Adaptive", "cheap" to "Range", "dip" to "Sweep", "momentum" to "Trend", "forecast-fade" to "Forecast")

    fun observations(a: PaperScalpAccount, quoteMs: Long): List<PaperCurvePoint> {
        val last = a.curve.lastOrNull()?.atMs ?: 0
        val live = if (a.policyStartedMs > 0 && quoteMs > last) listOf(PaperCurvePoint(quoteMs, a.equity(), a.realized,
            a.fees, a.peakEquity, a.policyVersion)) else emptyList()
        return (a.curve + live).sortedBy { it.atMs }.distinctBy { it.atMs to it.policyVersion }
    }
    fun series(state: PaperScalpState, allocation: Int, newOnly: Boolean, hours: Int?, drawdown: Boolean): List<Series> {
        val latest = max(state.lastQuoteMs, state.accounts.maxOfOrNull { it.curve.lastOrNull()?.atMs ?: 0 } ?: 0)
        val from = hours?.let { latest - it * 3_600_000L } ?: Long.MIN_VALUE
        return families.map { (id, name) ->
            val a = state.accounts.firstOrNull { it.id == "$id-$allocation" }
            val rows = a?.let { observations(it, state.lastQuoteMs) }.orEmpty()
                .filter { it.atMs >= from && (!newOnly || it.policyVersion == PaperScalpPolicy.VERSION) }
            var peak = rows.firstOrNull()?.equity ?: 0.0
            val baseline = if (newOnly) a?.policyStartEquity ?: 0.0 else PaperScalpPolicy.START_CASH
            Series(id, name, rows.map { p ->
                peak = max(peak, p.equity)
                Point(p.atMs, if (drawdown) p.equity - peak else p.equity - baseline)
            })
        }
    }
    /** Keep actual missing-data gaps visible; never join an overnight/feed outage with a smooth line. */
    fun segments(points: List<Point>): List<List<Point>> {
        val result = mutableListOf<MutableList<Point>>()
        for (point in points) {
            if (result.isEmpty() || point.atMs - result.last().last().atMs > 60_000) result += mutableListOf<Point>()
            result.last() += point
        }
        return result
    }
    fun valueAt(series: Series, atMs: Long): Point? = series.points.lastOrNull { it.atMs <= atMs }
    fun axis(series: List<Series>): Axis {
        val all = series.flatMap { it.points }.map { it.value }.filter { it.isFinite() }
        val low = minOf(0.0, all.minOrNull() ?: 0.0)
        val high = maxOf(0.0, all.maxOrNull() ?: 0.0)
        val rough = ((high - low).coerceAtLeast(1.0) / 4)
        val power = 10.0.pow(floor(log10(rough)))
        val scaled = rough / power
        val step = (when { scaled <= 1 -> 1.0; scaled <= 2 -> 2.0; scaled <= 5 -> 5.0; else -> 10.0 }) * power
        val bottom = floor(low / step) * step
        val top = max(bottom + step, ceil(high / step) * step)
        return Axis(bottom, top, step)
    }
}
