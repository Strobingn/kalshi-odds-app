package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.llm.PaperMarketFeed
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.ScalpSignal

/** Derived display data only; never changes a paper or live order. */
internal object PaperChartsData {
    data class Point(val timeMs: Long, val values: List<Double?>)
    data class Chart(val title: String, val unit: String, val labels: List<String>, val points: List<Point>, val note: String)
    data class Track(val label: String, val open: Int, val settled: Int, val pnl: Double?, val fees: Double)

    fun market(samples: List<PaperMarketFeed.Sample>): List<Chart> {
        fun quote(sample: PaperMarketFeed.Sample, value: Double?): Double? =
            value?.takeIf { it.isFinite() && it in 0.0..1.0 && sample.quoteAgeMs != null && sample.quoteAgeMs in 0..10_000 }
        fun chart(title: String, unit: String, labels: List<String>, note: String, values: (PaperMarketFeed.Sample) -> List<Double?>) =
            Chart(title, unit, labels, samples.map { Point(it.observedAtMs, values(it)) }, note)
        fun cents(value: Double?) = value?.times(100.0)
        fun spread(s: PaperMarketFeed.Sample, bid: Double?, ask: Double?): Double? {
            val b = quote(s, bid) ?: return null
            val a = quote(s, ask) ?: return null
            return (a - b).takeIf { it >= 0.0 }?.times(100.0)
        }
        val quoteNote = "Last sample per 15s · gaps mean missing, crossed, or over 10s old."
        return listOf(
            chart("YES quotes", "¢", listOf("Bid", "Ask"), quoteNote) { listOf(cents(quote(it, it.yesBid)), cents(quote(it, it.yesAsk))) },
            chart("NO quotes", "¢", listOf("Bid", "Ask"), quoteNote) { listOf(cents(quote(it, it.noBid)), cents(quote(it, it.noAsk))) },
            chart("Bid–ask spread", "¢", listOf("YES", "NO"), quoteNote) { listOf(spread(it, it.yesBid, it.yesAsk), spread(it, it.noBid, it.noAsk)) },
            chart("Model vs market", "%", listOf("Model YES", "YES midpoint"), "Model is a forecast, not a guaranteed probability; quote freshness applies.") {
                val bid = quote(it, it.yesBid); val ask = quote(it, it.yesAsk)
                listOf(it.fairPercent?.takeIf { p -> p.isFinite() && p in 0.0..100.0 }, if (bid != null && ask != null && ask >= bid) (bid + ask) * 50.0 else null)
            },
            chart("Model − market", "percentage points", listOf("YES model − midpoint"), "Difference from midpoint, not executable after-fee edge.") {
                val bid = quote(it, it.yesBid); val ask = quote(it, it.yesAsk)
                val fair = it.fairPercent?.takeIf { p -> p.isFinite() && p in 0.0..100.0 }
                listOf(if (bid != null && ask != null && ask >= bid && fair != null) fair - (bid + ask) * 50.0 else null)
            },
            chart("YES + NO ask", "¢", listOf("Combined asks"), "Displayed quote sum only; excludes fees, available depth, and execution risk.") {
                val yes = quote(it, it.yesAsk); val no = quote(it, it.noAsk)
                listOf(if (yes != null && no != null) (yes + no) * 100.0 else null)
            },
            chart("Spot vs target", "USD", listOf("Spot − target"), "Public spot is a proxy, not the settlement index; freshness is unknown.") {
                val spot = it.spot?.takeIf(Double::isFinite); val target = it.target?.takeIf(Double::isFinite)
                listOf(if (spot != null && target != null) spot - target else null)
            },
            chart("Bitcoin spot", "USD", listOf("Spot"), "Public spot proxy · freshness unknown.") { listOf(it.spot?.takeIf(Double::isFinite)) },
            chart("Quote age", "seconds", listOf("Kalshi age"), "Age of the latest Kalshi quote at each observation; missing age is a gap.") { listOf(it.quoteAgeMs?.takeIf { age -> age >= 0 }?.div(1000.0)) },
            chart("Paper realized P&L", "USD", listOf("After-fee P&L"), "All paper strategies · sampled during this market · excludes unrealized P&L.") { listOf(it.realizedPaper.takeIf(Double::isFinite)) },
            chart("Open paper positions", "positions", listOf("Open"), "All paper strategies · sampled during this market.") { listOf(it.openPaper.toDouble()) }
        )
    }

    fun tracks(fills: List<PaperFill>): List<Track> = ScalpSignal.Track.values().map { track ->
        val rows = fills.filter { it.strategyVersion == track.formulaVersion }
        val settled = rows.filter { it.settled && !it.outcome.equals("void", true) && it.pnlUsd?.isFinite() == true }
        Track(track.label, rows.count { !it.settled }, settled.size,
            settled.takeIf { it.isNotEmpty() }?.sumOf { it.pnlUsd ?: 0.0 },
            settled.sumOf { (it.feeUsd.takeIf(Double::isFinite) ?: 0.0) + (it.exitFeeUsd.takeIf(Double::isFinite) ?: 0.0) })
    }
}
