package com.dirk.kalshiodds.chart

import com.dirk.kalshiodds.decision.*
import org.junit.Assert.*
import org.junit.Test

class ScalpWindowTest {
    private val start = 1_790_000_000_000L
    private val t = ScalpTrade("trade", "KXBTC15M-X", "NO", ScalpState.PENDING_ENTRY, start + 1000, .40, .5,
        ruleVersion = "${ScalpRule.VERSION}|sf41|P")
    @Test fun preservesActualBuyAndSeparatePartialSellsAcrossSerialization() {
        val buy = ScalpFillLog.capture(t.copy(state = ScalpState.OPEN, entryAtMs = start + 3000, entryPrice = .40,
            contracts = 10, entryFeeUsd = .17), t, start + 3000)
        val partial = ScalpFillLog.capture(buy.copy(state = ScalpState.PENDING_EXIT, soldContracts = 3,
            proceedsUsd = 1.35, exitFeeUsd = .06), buy, start + 10_000)
        val close = ScalpFillLog.capture(partial.copy(state = ScalpState.CLOSED, soldContracts = 10,
            proceedsUsd = 4.57, exitFeeUsd = .19, closedAtMs = start + 15_000), partial, start + 15_000)
        val marks = ScalpWindow("KXBTC15M-X", start, start + 900_000, start + 20_000, trades = listOf(close)).markers()
        assertEquals(listOf(ScalpFillLog.Kind.BUY, ScalpFillLog.Kind.SELL, ScalpFillLog.Kind.SELL), marks.map { it.fill.kind })
        assertEquals(listOf(start + 3000, start + 10_000, start + 15_000), marks.map { it.fill.atMs })
        assertEquals(listOf(10, 3, 7), marks.map { it.fill.qty })
        assertEquals(.46, marks.last().fill.price, 1e-9)
        assertTrue(marks.all { it.side == "NO" }) // own-side prices, never silently plotted as YES
        assertEquals(.40, marks.first().fill.price, 0.0)
        assertEquals(.36, marks.sumOf { it.fill.fee }, 1e-9)
        assertEquals(close, ScalpFillLog.capture(close, close, start + 16_000))
    }
    @Test fun noFillHasNoExecutionMarkersAndLegacyExitIsExplicitlyAnAverage() {
        assertTrue(ScalpFillLog.display(t.copy(state = ScalpState.NO_FILL)).isEmpty())
        val legacy = t.copy(state = ScalpState.CLOSED, contracts = 10, entryPrice = .40, entryAtMs = start + 3000,
            soldContracts = 10, proceedsUsd = 4.5, closedAtMs = start + 15_000)
        assertTrue(ScalpFillLog.display(legacy).last().aggregate)
        assertEquals(legacy, ScalpFillLog.capture(legacy, null, start + 30_000))
    }
    @Test fun settlementIsNotReportedAsAMarketSaleAndOtherWindowMarkersAreExcluded() {
        val done = t.copy(state = ScalpState.CLOSED, entryAtMs = start - 1000, entryPrice = .4, contracts = 10,
            soldContracts = 10, proceedsUsd = 10.0, closedAtMs = start + 900_000, exitReason = "settled")
        val window = ScalpWindow("KXBTC15M-X", start, start + 900_000, start + 900_000, trades = listOf(done))
        assertEquals(listOf(ScalpFillLog.Kind.SETTLE), window.markers().map { it.fill.kind })
    }
    @Test fun fullWindowQuoteSeriesRetainsBothEndsExcludesFutureAndDoesNotStretchStaleQuotes() {
        val points = (0..899).map { BidPoint(start + it * 1000, it % 99f, 99 - it % 99f, 100_000.0 + it) }
        val result = ScalpWindow.points(points, listOf(BidPoint(start + 800_000, 44f, null)), start, start + 900_000, start + 850_000)
        assertEquals(start, result.first().tMs); assertEquals(start + 850_000, result.last().tMs)
        assertEquals(44f, result.first { it.tMs == start + 800_000 }.upBidCents)
        assertNotNull(result.first { it.tMs == start + 800_000 }.downBidCents)
        val stale = ScalpWindow.points(points.take(10), emptyList(), start, start + 900_000, start + 850_000)
        assertEquals(start + 9000, stale.last().tMs)
    }
}
