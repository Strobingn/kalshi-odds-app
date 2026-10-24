package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.*
import org.junit.Assert.*
import org.junit.Test

class ScalpWindowChartTest {
    private val tk = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(tk)!!
    private val start = close - 900_000
    private fun trade() = ScalpTrade("t", tk, "YES", ScalpState.OPEN, start + 60_000, .40, .5,
        contracts = 10, entryPrice = .40, entryFeeUsd = .18, entryAtMs = start + 62_000,
        ruleVersion = "${ScalpRule.VERSION}|v2-sf41|P")

    @Test fun partialSalesPersistExactTimesAndDoNotTurnIntoAnAverageFinalSale() {
        val first = ScalpFills.sell(trade(), start + 90_000, 2, 1.20, .04)
        val last = ScalpFills.sell(first, start + 100_000, 8, 4.40, .14).copy(state = ScalpState.CLOSED,
            closedAtMs = start + 100_000, netUsd = 1.24)
        val data = ScalpWindowChartData(tk, close, start + 120_000, trades = listOf(last))
        val markers = data.markers("YES")
        assertEquals(listOf(.60, .55), markers.filter { it.kind == ScalpWindowChartData.Kind.SELL }.map { it.price })
        assertEquals(listOf(start + 90_000, start + 100_000), ScalpFills.sales(last).map { it.atMs })
        assertEquals(listOf(2, 8), ScalpFills.sales(last).map { it.contracts })
        assertEquals(.18, ScalpFills.sales(last).sumOf { it.fee }, 1e-9)
        assertEquals(0, data.markers("NO").size)
        assertEquals(1.24, data.windowNet(), 1e-9)
        assertFalse(markers.any { it.kind == ScalpWindowChartData.Kind.LEGACY_EXIT })
    }

    @Test fun pendingAndNoFillNeverGetBuyOrSellMarkersAndLegacyIsLabeled() {
        val pending = trade().copy(state = ScalpState.NO_FILL, entryAtMs = null, entryPrice = null, contracts = 0)
        val data = ScalpWindowChartData(tk, close, close, trades = listOf(pending))
        assertEquals(listOf(ScalpWindowChartData.Kind.SIGNAL), data.markers("YES").map { it.kind })
        val old = trade().copy(state = ScalpState.CLOSED, soldContracts = 10, proceedsUsd = 6.0, closedAtMs = start + 100_000)
        assertTrue(data.copy(trades = listOf(old)).markers("YES").any { it.kind == ScalpWindowChartData.Kind.LEGACY_EXIT && it.price == .60 })
    }

    @Test fun sideAndTickerIsolationSettlementAndMissingQuotes() {
        val t = ScalpFills.sell(trade().copy(side = "NO"), close, 10, 10.0, 0.0, true)
        val other = trade().copy(id = "other", ticker = "OTHER")
        val data = ScalpWindowChartData(tk, close, close, points = listOf(BidPoint(start + 30_000, 60f, 39f)), trades = listOf(t, other))
        assertTrue(data.markers("YES").isEmpty())
        assertEquals(ScalpWindowChartData.Kind.SETTLEMENT, data.markers("NO").last().kind)
        assertEquals(.39, data.bid("NO")!!, 1e-9)
        assertNull(data.copy(points = emptyList()).bid("YES"))
    }

    @Test fun postedLimitUsesItsActualPriceAndTimeRatherThanTheSignalAsk() {
        val pending = trade().copy(state = ScalpState.PENDING_ENTRY, entryAtMs = null, entryPrice = null,
            signalAsk = .51, restingLimit = .49, restingSinceMs = start + 65_000)
        val recorded = pending.copy(note = ScalpFills.orderNote(pending, pending.copy(restingLimit = null)))
        val filled = recorded.copy(state = ScalpState.OPEN, entryAtMs = start + 70_000, entryPrice = .49, restingLimit = null)
        val markers = ScalpWindowChartData(tk, close, close, trades = listOf(filled)).markers("YES")
        assertTrue(markers.any { it.kind == ScalpWindowChartData.Kind.ORDER && it.atMs == start + 65_000 && it.price == .49 })
        assertTrue(markers.any { it.kind == ScalpWindowChartData.Kind.SIGNAL && it.price == .51 })
        assertEquals(recorded.note, ScalpFills.orderNote(recorded, recorded))
    }

    @Test fun lateSettlementPlotsAtCloseButKeepsItsActualRecordedTime() {
        val sold = ScalpFills.sell(trade(), close + 12_000, 10, 10.0, 0.0, true)
        val marker = ScalpWindowChartData(tk, close, close + 20_000, trades = listOf(sold)).markers("YES").last()
        assertEquals(ScalpWindowChartData.Kind.SETTLEMENT, marker.kind)
        assertEquals(close, marker.atMs)
        assertEquals(close + 12_000, marker.recordedAtMs)
    }

    @Test fun lowercaseTickerAndCentAskStillPlotAndOtherMarketsDoNotCount() {
        val raw = trade().copy(
            ticker = tk.lowercase(), side = "yes", signalAsk = 40.0,
            state = ScalpState.CLOSED, netUsd = 0.50, closedAtMs = start + 80_000,
            soldContracts = 10, proceedsUsd = 5.0
        )
        val other = trade().copy(id = "else", ticker = "OTHER", state = ScalpState.CLOSED, netUsd = 9.0, closedAtMs = start + 90_000)
        val data = ScalpWindowChartData(tk, close, close, trades = listOf(raw, other))
        val signal = data.markers("YES").first { it.kind == ScalpWindowChartData.Kind.SIGNAL }
        assertEquals(0.40, signal.price, 1e-9)
        assertEquals(1, data.windowRoundTrips())
        assertEquals(0.50, data.windowNet(), 1e-9)
        assertTrue(data.markers("NO").isEmpty())
    }

    @Test fun openPnlUsesTheYesBidForAnUpPosition() {
        val held = trade().copy(side = "yes")
        val data = ScalpWindowChartData(tk, close, close, points = listOf(BidPoint(start, 60f, 39f)), trades = listOf(held))
        assertEquals(held.unrealizedUsd(0.60)!!, data.openNet(), 1e-9)
        assertEquals(0, data.windowRoundTrips())
        assertEquals(0.0, data.windowNet(), 0.0)
    }

    @Test fun pickerKeepsLiveWindowAndOnlyRecentWindowsWithRecordedQuotes() {
        val live = ScalpTabMarket(tk, "BTC", close, "Live")
        val old = ScalpTabMarket("older", "BTC", close - 900_000, "Older")
        val missing = ScalpTabMarket("missing", "BTC", close - 1_800_000, "Missing")
        val choices = ScalpWindowChartData.availableMarkets(listOf(live), listOf(live, old, missing)) { it.ticker == old.ticker }
        assertEquals(listOf(tk, "older"), choices.map { it.ticker })
    }

    @Test fun settleCalloutsStayInsideThePlot() {
        assertFalse(ScalpChartReadability.labelAbove(99.9))
        assertEquals(16f, ScalpChartReadability.labelDyDp(99.9))
        assertTrue(ScalpChartReadability.labelAbove(0.1))
        assertEquals(-14f, ScalpChartReadability.labelDyDp(0.1))
        assertTrue(ScalpChartReadability.labelAbove(50.0))
        assertEquals(1.35f, com.dirk.kalshiodds.ui.charts.ChartStroke.SERIES_DP)
        assertTrue(ScalpChartReadability.MARKER_DP > 5f)
        assertTrue(ScalpChartReadability.END_DOT_DP > 3f)
    }

    @Test fun bookPartialExitRecordsSurvivePersistenceAndRestart() {
        val store = InMemoryScalpPersistence().also { it.seed(listOf(trade().copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = start + 80_000, exitReason = "trail"))) }
        val book = ScalpBook(store, variants = emptyList(), liveExtras = true)
        fun q(ms: Long, size: Double) = ScalpRule.Quote(tk, ms, close, ms, .60, size, .61, 100.0, 100_000.0, 100_000.0, 3e-5)
        book.onQuote(q(start + 90_000, 2.0), false)
        book.flushWrites()
        val restarted = ScalpBook(store, variants = emptyList(), liveExtras = true)
        restarted.onQuote(q(start + 100_000, 8.0), false)
        restarted.flushWrites()
        val row = store.windowTrades("v2-sf41", tk).single()
        assertEquals(listOf(2, 8), ScalpFills.sales(row).map { it.contracts })
        assertEquals(listOf(start + 90_000, start + 100_000), ScalpFills.sales(row).map { it.atMs })
        assertEquals(ScalpState.CLOSED, row.state)
        assertTrue(store.windowTrades("sf41", tk).isEmpty())
    }
}
