package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.ScalpFills
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.ScalpWindowChartData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ChartShapesTest {
    private val m0 = ScalpModels.ALL[0]
    private val m1 = ScalpModels.ALL[1]
    private val day = ZonedDateTime.of(2026, 10, 18, 15, 30, 0, 0, ChartShapes.ET).toInstant().toEpochMilli()

    @Test fun cumulativeAndDrawdown() {
        val equity = ChartShapes.cumulative(listOf(1L to 2.0, 2L to -3.0, 3L to Double.NaN))
        assertEquals(listOf(2.0, -1.0), equity.map { it.value })
        assertEquals(listOf(0.0, -3.0), ChartShapes.drawdown(equity).map { it.value })
        assertTrue(ChartShapes.cumulative(emptyList()).isEmpty())
        assertTrue(ChartShapes.drawdown(emptyList()).isEmpty())
    }

    @Test fun equitySkipsFutureAndOtherModels() {
        val later = day + 3_600_000
        val windows = listOf(
            ScalpModels.WindowRow("a", day, mapOf(m0 to 1.5, m1 to -0.5), mapOf(m0 to 2, m1 to 1), m0),
            ScalpModels.WindowRow("b", day + 900_000, mapOf(m0 to 2.0), mapOf(m0 to 1), null),
            ScalpModels.WindowRow("future", later + 1, mapOf(m0 to 99.0), mapOf(m0 to 9), null),
            ScalpModels.WindowRow("open", null, mapOf(m0 to 5.0), emptyMap(), null),
            ScalpModels.WindowRow("other", day, mapOf(ScalpModels.ALL[2] to 50.0), mapOf(ScalpModels.ALL[2] to 4), null)
        )
        val lines = ChartShapes.equityFromWindows(windows, later, listOf(m0, m1))
        assertEquals(listOf("Combined", m0.label, m1.label), lines.map { it.name })
        assertEquals(0.0, lines[0].points.first().value, 0.0)
        assertEquals(3.0, lines[0].points.last().value, 1e-9)
        assertEquals(3.5, lines[1].points.last().value, 1e-9)
        assertEquals(-0.5, lines[2].points.last().value, 1e-9)
        assertTrue(ChartShapes.equityFromWindows(windows, later, emptyList()).isEmpty())
    }

    @Test fun hourBinsUseEasternClose() {
        val windows = listOf(
            ScalpModels.WindowRow("a", day, mapOf(m0 to 1.25), mapOf(m0 to 2), null),
            ScalpModels.WindowRow("b", day + 3_600_000, mapOf(m0 to -0.25), mapOf(m0 to 1), null)
        )
        val heat = ChartShapes.windowHourPnl(windows, day + 4_000_000, listOf(m0))
        assertEquals(1.25, heat[15].net, 1e-9)
        assertEquals(1, heat[15].trades)
        assertEquals(-0.25, heat[16].net, 1e-9)
        assertEquals(0, heat[0].trades)
        val hours = ChartShapes.windowTradesPerHour(windows, day + 4_000_000, listOf(m0))
        assertEquals(24, hours.size)
        assertEquals("3p", hours[15].label)
        assertEquals(2, hours[15].count)
        assertEquals(1, hours[16].count)
        assertEquals("12a", ChartShapes.hourLabel(0))
        assertEquals("12p", ChartShapes.hourLabel(12))
        assertTrue(ChartShapes.tradesPerHour(emptyList()).isEmpty())
        assertTrue(ChartShapes.pnlByHour(emptyList()).isEmpty())
    }

    @Test fun entryAndHoldBins() {
        val entry = ChartShapes.entryPriceBins(listOf(0.19, 0.20, 0.40, 0.80, 1.0, -1.0, 1.2))
        assertEquals(listOf(1, 1, 1, 0, 2), entry.map { it.count })
        assertEquals(listOf("<20¢", "20–40¢", "40–60¢", "60–80¢", "≥80¢"), entry.map { it.label })
        val hold = ChartShapes.holdBins(listOf(10_000.0, 30_000.0, 90_000.0, 300_000.0, 600_000.0))
        assertEquals(listOf(1, 2, 0, 1, 1), hold.map { it.count })
        assertTrue(ChartShapes.entryPriceBins(emptyList()).isEmpty())
    }

    @Test fun depthKeepsBothSidesAndDropsEmpty() {
        val rungs = ChartShapes.depthRungs(
            yes = listOf(0.60 to 10.0, 0.10 to 4.0, 0.60 to 2.0, -1.0 to 9.0),
            no = listOf(0.40 to 5.0, 0.40 to 1.0, 0.20 to 0.0)
        )
        assertEquals(listOf(60, 40, 10), rungs.map { it.cents })
        assertEquals(12.0, rungs[0].upSize, 0.0)
        assertEquals(0.0, rungs[0].downSize, 0.0)
        assertEquals(6.0, rungs[1].downSize, 0.0)
        assertTrue(ChartShapes.depthRungs(emptyList(), emptyList()).isEmpty())
    }

    @Test fun tweaksAndFlowUseOnlyRealNotes() {
        assertNull(ChartShapes.tweakMark("Peak", "  "))
        val kept = ChartShapes.tweakMark("Peak", "AI kept edge 4¢")!!
        assertNull(kept.fromCents)
        val moved = ChartShapes.tweakMark("Fade", "AI moved highest buy 40¢ → 42¢")!!
        assertEquals(40, moved.fromCents)
        assertEquals(42, moved.toCents)
        assertTrue(ChartShapes.flowCounts(0, 0, 0).isEmpty())
        assertEquals(listOf("Signal", "Entry", "Exit"), ChartShapes.flowCounts(3, 2, 1).map { it.title })
        assertEquals(listOf("Signal", "Entry"), ChartShapes.flowPath(0.41, 0.40, null, null, 10).map { it.title })
        assertTrue(ChartShapes.flowPath(null, null, null, null, 0).isEmpty())
    }

    @Test fun aiAndMarketFlipToUp() {
        assertEquals(40.0, ChartShapes.aiUp(true, 40.0)!!, 0.0)
        assertEquals(60.0, ChartShapes.aiUp(false, 40.0)!!, 0.0)
        assertEquals(50.0, ChartShapes.scalePct(0.5)!!, 0.0)
        assertEquals(60.0, ChartShapes.marketUp(false, 40.0, 0.40)!!, 0.0)
        assertEquals(60.0, ChartShapes.marketUp(false, 60.0, 0.40)!!, 0.0)
        assertEquals(40.0, ChartShapes.marketUp(true, 40.0, 0.40)!!, 0.0)
        assertNull(ChartShapes.marketUp(false, 40.0, null))
        assertEquals(40.0, ChartShapes.marketUp(true, 40.0, null)!!, 0.0)
        assertNull(ChartShapes.aiMarket(1L, true, null, null, null))
    }

    @Test fun windowEventsKeepBothSidesAndPartialSells() {
        val tk = "KXBTC15M-26OCT122015-15"
        val close = com.dirk.kalshiodds.decision.ScalpTicker.closeMs(tk)!!
        val start = close - 900_000
        val up = ScalpTrade(
            "up", tk, "YES", ScalpState.OPEN, start + 60_000, 0.40, 0.5,
            contracts = 10, entryPrice = 0.40, entryAtMs = start + 62_000, entryFeeUsd = 0.18
        )
        val closedUp = ScalpFills.sell(up, start + 90_000, 10, 6.0, 0.10).copy(
            state = ScalpState.CLOSED, closedAtMs = start + 90_000, netUsd = 1.2
        )
        val down = up.copy(id = "dn", side = "NO", signalAtMs = start + 70_000, entryAtMs = start + 80_000, entryPrice = 0.33)
        val events = ChartShapes.windowEvents(start, close, listOf(closedUp, down))
        assertTrue(events.any { it.up && it.kind == "entry" && it.price == 0.40 })
        assertTrue(events.any { it.up && it.kind == "exit" })
        assertTrue(events.any { !it.up && it.kind == "signal" })
        assertTrue(events.none { it.price !in 0.0..1.0 })
    }

    @Test fun rollupAndBothMarkerSides() {
        val tk = "KXBTC15M-26OCT122015-15"
        val close = com.dirk.kalshiodds.decision.ScalpTicker.closeMs(tk)!!
        val start = close - 900_000
        val up = ScalpTrade(
            "up", tk, "YES", ScalpState.CLOSED, start + 60_000, 0.40, 0.5,
            contracts = 10, entryPrice = 0.40, entryFeeUsd = 0.10, exitFeeUsd = 0.20,
            entryAtMs = day, closedAtMs = day + 90_000, netUsd = 1.5, soldContracts = 10, proceedsUsd = 6.0
        )
        val down = up.copy(id = "dn", side = "NO", entryPrice = 0.25, netUsd = -0.4, entryAtMs = day + 1_000)
        val roll = ChartShapes.rollup(listOf(up, down))
        assertEquals(1.1, roll.equity.last().value, 1e-9)
        assertEquals(0.60, roll.fees.last().value, 1e-9)
        assertTrue(roll.drawdown.last().value <= 0.0)
        assertEquals(2, roll.entryBins.sumOf { it.count })
        assertEquals(2, roll.sides.sumOf { it.up + it.down })
        assertTrue(roll.perHour.any { it.count == 2 || it.count == 1 })
        val data = ScalpWindowChartData(tk, close, close, trades = listOf(up, down))
        assertEquals(setOf("YES", "NO"), data.allMarkers().map { it.side }.toSet())
        assertTrue(data.markers("YES").all { it.side == "YES" })
        assertTrue(data.markers("NO").all { it.side == "NO" })
        assertTrue(data.markers("YES").isNotEmpty())
    }

    @Test fun bidsSpotAndScrub() {
        val points = listOf(
            BidPoint(1, 40f, null, 100.0),
            BidPoint(2, null, 55f, null),
            BidPoint(3, 140f, 10f, -5.0)
        )
        assertEquals(listOf(40.0), ChartShapes.bidSamples(points, true).map { it.value })
        assertEquals(listOf(55.0, 10.0), ChartShapes.bidSamples(points, false).map { it.value })
        assertEquals(100.0, ChartShapes.spotSamples(points).single().value, 0.0)
        assertEquals(40.0, ChartShapes.latestBidCents(points, true)!!, 0.0)
        assertEquals(0, ChartShapes.indexAt(-5f, 100f, 4))
        assertEquals(3, ChartShapes.indexAt(100f, 100f, 4))
        val sides = ChartShapes.sideCounts(listOf("A" to listOf("YES", "NO", "MAYBE"))).single()
        assertEquals(1, sides.up)
        assertEquals(1, sides.down)
    }
}
