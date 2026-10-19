package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.*

data class ScalpWindowChartData(
    val ticker: String = "", val closeMs: Long = 0, val nowMs: Long = 0,
    val bookAtMs: Long? = null, val points: List<BidPoint> = emptyList(),
    val trades: List<ScalpTrade> = emptyList(), val error: String? = null
) {
    val startMs get() = closeMs - 900_000L
    enum class Kind { SIGNAL, ORDER, BUY, SELL, SETTLEMENT, LEGACY_EXIT }
    data class Marker(val id: String, val tradeId: String, val atMs: Long, val price: Double, val qty: Int, val kind: Kind, val recordedAtMs: Long = atMs)
    fun markers(side: String): List<Marker> = buildList {
        for (t in trades.filter { it.ticker.equals(ticker, true) && it.side == side }) {
            val orders = ScalpFills.orders(t)
            orders.forEachIndexed { i, o -> add(Marker("${t.id}-order-$i", t.id, o.atMs, o.price, 0, Kind.ORDER)) }
            if (t.signalAtMs in startMs..closeMs && orders.none { it.atMs == t.signalAtMs && kotlin.math.abs(it.price - t.signalAsk) < 1e-9 }) add(Marker("${t.id}-signal", t.id, t.signalAtMs, t.signalAsk, 0, Kind.SIGNAL))
            if (t.entryPrice != null && t.entryAtMs != null) add(Marker("${t.id}-buy", t.id, t.entryAtMs, t.entryPrice, t.contracts, Kind.BUY))
            val sales = ScalpFills.sales(t)
            sales.forEachIndexed { i, f -> add(Marker("${t.id}-sell-$i", t.id, if (f.settlement) minOf(f.atMs, closeMs) else f.atMs, f.price, f.contracts, if (f.settlement) Kind.SETTLEMENT else Kind.SELL, f.atMs)) }
            // Older trades have only an aggregate exit, never invent individual partial fills.
            if (sales.isEmpty() && t.soldContracts > 0 && t.closedAtMs != null) add(Marker("${t.id}-avg", t.id, t.closedAtMs, t.proceedsUsd / t.soldContracts, t.soldContracts, Kind.LEGACY_EXIT))
        }
    }.filter { it.atMs in startMs..closeMs && it.price.isFinite() && it.price in 0.0..1.0 }.sortedBy { it.atMs }
    fun bid(side: String): Double? = points.lastOrNull { (if (side == "YES") it.upBidCents else it.downBidCents) != null }
        ?.let { (if (side == "YES") it.upBidCents else it.downBidCents)?.toDouble()?.div(100) }
    /** Recorded index prints only. Missing or non-positive spots are left out — nothing is invented. */
    fun recordedSpots(): List<Pair<Long, Double>> = points.mapNotNull { p ->
        val spot = p.spotUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
        p.tMs to spot
    }
    fun windowNet() = trades.sumOf { it.netUsd ?: 0.0 }
    fun openNet() = trades.sumOf { it.unrealizedUsd(bid(it.side)) ?: 0.0 }
    companion object {
        /** Completed windows are selectable only while their quote path is still available. */
        fun availableMarkets(live: List<ScalpTabMarket>, recent: List<ScalpTabMarket>, hasQuotes: (ScalpTabMarket) -> Boolean): List<ScalpTabMarket> {
            val current = live.map { it.ticker }.toSet()
            return (live + recent.distinctBy { it.ticker }.take(12).filter { it.ticker in current || hasQuotes(it) })
                .distinctBy { it.ticker }.sortedByDescending { it.closeMs }.take(12)
        }
    }

}
