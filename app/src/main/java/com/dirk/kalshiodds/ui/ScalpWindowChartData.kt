package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.*

data class ScalpWindowChartData(
    val ticker: String = "", val closeMs: Long = 0, val nowMs: Long = 0,
    val bookAtMs: Long? = null, val points: List<BidPoint> = emptyList(),
    val trades: List<ScalpTrade> = emptyList(), val error: String? = null,
    val sideLossFloorUsd: Double = WindowLossGate.DEFAULT_LOSS_USD,
    val adverseFillCap: Int = WindowLossGate.DEFAULT_ADVERSE_FILLS
) {
    val startMs get() = closeMs - 900_000L
    enum class Kind { SIGNAL, ORDER, BUY, SELL, SETTLEMENT, LEGACY_EXIT }
    data class Marker(val id: String, val tradeId: String, val atMs: Long, val price: Double, val qty: Int, val kind: Kind, val recordedAtMs: Long = atMs, val side: String = "")
    fun markers(side: String): List<Marker> = buildList {
        for (t in trades.filter { onThisMarket(it) && it.side.equals(side, true) }) {
            val orders = ScalpFills.orders(t)
            orders.forEachIndexed { i, o -> add(Marker("${t.id}-order-$i", t.id, o.atMs, o.price, 0, Kind.ORDER, side = side)) }
            if (t.signalAtMs in startMs..closeMs && orders.none { it.atMs == t.signalAtMs && kotlin.math.abs(it.price - t.signalAsk) < 1e-9 }) add(Marker("${t.id}-signal", t.id, t.signalAtMs, t.signalAsk, 0, Kind.SIGNAL, side = side))
            if (t.entryPrice != null && t.entryAtMs != null) add(Marker("${t.id}-buy", t.id, t.entryAtMs, t.entryPrice, t.contracts, Kind.BUY, side = side))
            val sales = ScalpFills.sales(t)
            sales.forEachIndexed { i, f -> add(Marker("${t.id}-sell-$i", t.id, if (f.settlement) minOf(f.atMs, closeMs) else f.atMs, f.price, f.contracts, if (f.settlement) Kind.SETTLEMENT else Kind.SELL, f.atMs, side)) }
            // Older trades have only an aggregate exit, never invent individual partial fills.
            if (sales.isEmpty() && t.soldContracts > 0 && t.closedAtMs != null) add(Marker("${t.id}-avg", t.id, t.closedAtMs, t.proceedsUsd / t.soldContracts, t.soldContracts, Kind.LEGACY_EXIT, side = side))
        }
    }.mapNotNull { m ->
        val price = chartPrice01(m.price) ?: return@mapNotNull null
        if (m.atMs !in startMs..closeMs) return@mapNotNull null
        if (price == m.price) m else m.copy(price = price)
    }.sortedBy { it.atMs }
    /** UP and DOWN markers together. Same rows [markers] returns, one side at a time. */
    fun allMarkers(): List<Marker> = (markers("YES") + markers("NO")).sortedWith(compareBy({ it.atMs }, { it.id }))

    /**
     * Sides [WindowLossGate] would refuse on the rows drawn here.
     * "UP paused: window −$12.40" when that side is through the floor or the losing streak.
     */
    fun sidePauseLine(): String? {
        if (closeMs <= startMs && trades.isEmpty()) return null
        val parts = listOf("YES", "NO").mapNotNull { side ->
            WindowLossGate.evaluate(
                trades, ticker, side, bid(side), sideLossFloorUsd, adverseFillCap, primaryOnly = false
            ).reason
        }
        return parts.joinToString(" · ").ifBlank { null }
    }

    /**
     * One side has markers and the other does not. The missing side's ask is the complement of the
     * recorded bid, so a 5¢ UP print says why DOWN did not trigger.
     */
    fun quietSideLine(): String? {
        if (closeMs <= startMs) return null
        val marks = allMarkers()
        val up = marks.any { !it.side.equals("NO", true) }
        val down = marks.any { it.side.equals("NO", true) }
        if (up == down) return null
        val missingDown = up && !down
        val name = if (missingDown) "DOWN" else "UP"
        val ask = complementaryAskCents(missingDown)
        val ceiling = PaperFleetDefaults.BUY_CEILING.toDouble()
        val reason = if (ask != null && ask > ceiling + 1e-6) "ask above ${PaperFleetDefaults.BUY_CEILING}¢ ceiling" else "no scalper took this side"
        return "no $name trigger this window ($reason)"
    }

    private fun complementaryAskCents(missingDown: Boolean): Double? {
        val last = points.lastOrNull { point ->
            point.tMs in startMs..minOf(nowMs, closeMs) && (if (missingDown) point.upBidCents != null else point.downBidCents != null)
        } ?: return null
        val bid = if (missingDown) last.upBidCents else last.downBidCents
        return bid?.toDouble()?.let { 100.0 - it }
    }
    private fun onThisMarket(t: ScalpTrade) = ticker.isEmpty() || t.ticker.equals(ticker, true)
    /** YES and UP are the same side. Stored cents (1–100) are not a dollar price. */
    fun bid(side: String): Double? {
        val up = side.equals("YES", true) || side.equals("UP", true)
        return points.lastOrNull { (if (up) it.upBidCents else it.downBidCents) != null }
            ?.let { (if (up) it.upBidCents else it.downBidCents)?.toDouble()?.div(100) }
    }
    /** Closed primary rows passed in for this market. Shadows never reach this list. */
    fun windowRoundTrips() = trades.count { onThisMarket(it) && it.state == ScalpState.CLOSED }
    fun windowNet() = trades.sumOf { t ->
        if (!onThisMarket(t) || t.state != ScalpState.CLOSED) 0.0
        else t.netUsd?.takeIf { it.isFinite() } ?: 0.0
    }
    fun openNet() = trades.sumOf { t -> if (onThisMarket(t)) t.unrealizedUsd(bid(t.side)) ?: 0.0 else 0.0 }
    companion object {
        /**
         * Trade prices are dollars in 0..1. A value in (1, 100] is cents that never
         * plotted, which left the marker chart empty.
         */
        fun chartPrice01(px: Double): Double? {
            if (!px.isFinite()) return null
            val p = if (px in 0.0..1.0) px else if (px > 1.0 && px <= 100.0) px / 100.0 else return null
            return p.takeIf { it in 0.0..1.0 }
        }
        /** Completed windows are selectable only while their quote path is still available. */
        fun availableMarkets(live: List<ScalpTabMarket>, recent: List<ScalpTabMarket>, hasQuotes: (ScalpTabMarket) -> Boolean): List<ScalpTabMarket> {
            val current = live.map { it.ticker }.toSet()
            return (live + recent.distinctBy { it.ticker }.take(12).filter { it.ticker in current || hasQuotes(it) })
                .distinctBy { it.ticker }.sortedByDescending { it.closeMs }.take(12)
        }
    }

}
