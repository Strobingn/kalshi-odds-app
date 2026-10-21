package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ChartGalleryTest {
    @Test fun sideGroupsKeepUpAndDownAndDropEmpty() {
        val up = trade("a", "YES", "v|v2-fair|P", 1.5)
        val down = trade("b", "NO", "v|v2-fair|P", -0.4)
        val other = trade("c", "YES", "v|v2-dip|P", 0.2)
        val skip = trade("d", "NO", "v|v2-dip|P", 9.0).copy(state = ScalpState.NO_FILL, entryPrice = null, contracts = 0)
        val groups = ChartGallery.sideGroups(listOf(up, down, other, skip)) { if (it.id == "c") "Dip-hunter" else "Fair-gap" }
        assertEquals(listOf("Fair-gap", "Dip-hunter"), groups.map { it.label })
        val fair = groups[0]
        assertEquals(1, fair.upEntries)
        assertEquals(1, fair.downEntries)
        assertEquals(1.5, fair.upNet, 1e-9)
        assertEquals(-0.4, fair.downNet, 1e-9)
        assertEquals(1, groups[1].upEntries)
        assertEquals(0, groups[1].downEntries)
        assertTrue(ChartGallery.sideGroups(emptyList()).isEmpty())
        assertTrue(ChartGallery.sideGroups(listOf(skip)).isEmpty())
    }

    @Test fun winRateHeatIsHourByCoin() {
        val day = ZonedDateTime.of(2026, 10, 18, 15, 30, 0, 0, ChartShapes.ET).toInstant().toEpochMilli()
        val btcWin = trade("a", "YES", "v|v2-fair|P", 1.0).copy(entryAtMs = day, ticker = "KXBTC15M-26OCT181530-30")
        val btcLoss = btcWin.copy(id = "b", side = "NO", netUsd = -0.2)
        val ethLoss = btcWin.copy(id = "c", ticker = "KXETH15M-26OCT181530-30", netUsd = -0.5)
        val open = btcWin.copy(id = "d", state = ScalpState.OPEN, netUsd = null)
        val grid = ChartGallery.winRateByHourCoin(listOf(btcWin, btcLoss, ethLoss, open))!!
        assertEquals(listOf("BTC", "ETH"), grid.rows)
        assertEquals(24, grid.columns.size)
        assertEquals("3p", grid.columns[15])
        assertEquals(0.5, grid.cells[0][15].rate!!, 0.0)
        assertEquals(2, grid.cells[0][15].n)
        assertEquals(0.0, grid.cells[1][15].rate!!, 0.0)
        assertNull(grid.cells[0][0].rate)
        assertEquals(0, grid.cells[0][0].n)
        assertNull(ChartGallery.winRateByHourCoin(emptyList()))
        assertNull(ChartGallery.winRateByHourCoin(listOf(open)))
    }

    @Test fun leaderboardKeepsFrozenAndDropsNonFinite() {
        val rows = ChartGallery.leaderboard(
            listOf(
                ChartGallery.LeaderRow("Fade", -4.0, true),
                ChartGallery.LeaderRow("Peak", 2.0, false),
                ChartGallery.LeaderRow("Flat", 0.0, true),
                ChartGallery.LeaderRow("Broken", Double.NaN, true),
                ChartGallery.LeaderRow(" ", 5.0, false)
            )
        )
        assertEquals(listOf("Peak", "Flat", "Fade"), rows.map { it.label })
        assertTrue(rows[1].frozen && rows[2].frozen)
        assertTrue(!rows[0].frozen)
        assertTrue(ChartGallery.leaderboard(emptyList()).isEmpty())
    }

    @Test fun sparksKeepACellForEveryStrategy() {
        val m0 = ScalpModels.ALL[0]
        val m1 = ScalpModels.ALL[1]
        val day = ZonedDateTime.of(2026, 10, 18, 15, 30, 0, 0, ChartShapes.ET).toInstant().toEpochMilli()
        val windows = listOf(ScalpModels.WindowRow("a", day, mapOf(m0 to 1.5), mapOf(m0 to 1), m0))
        val lines = ChartGallery.equitySparks(windows, day + 1_000, listOf(m0 to false, m1 to true))
        assertEquals(listOf(m0.label, m1.label), lines.map { it.label })
        assertEquals(1.5, lines[0].values.last(), 1e-9)
        assertTrue(lines[0].values.size >= 2)
        assertTrue(lines[1].values.isEmpty() && lines[1].frozen)
        assertTrue(ChartGallery.equitySparks(windows, day, emptyList()).isEmpty())
        val filled = ChartGallery.equitySparks(windows, day + 1_000, listOf(m1 to false), mapOf(m1 to -2.5))
        assertEquals(listOf(0.0, -2.5), filled.single().values)
        assertEquals("12 of 30 closed trades. The line starts at 30.", ChartGallery.rollingHoldback(12))
        assertEquals(null, ChartGallery.rollingHoldback(0))
        assertEquals(null, ChartGallery.rollingHoldback(30))
    }

    @Test fun dailyCorrelationNeedsTwoMovers() {
        val m0 = ScalpModels.ALL[0]
        val m1 = ScalpModels.ALL[1]
        val day = ZonedDateTime.of(2026, 10, 18, 15, 30, 0, 0, ChartShapes.ET).toInstant().toEpochMilli()
        val later = day + 86_400_000
        val windows = listOf(
            ScalpModels.WindowRow("a", day, mapOf(m0 to 1.0, m1 to -2.0), emptyMap(), m0),
            ScalpModels.WindowRow("b", later, mapOf(m0 to 3.0, m1 to -4.0), emptyMap(), m1)
        )
        val corr = ChartGallery.dailyCorrelation(windows, later + 1, listOf(m0, m1))!!
        assertEquals(listOf(m1.label, m0.label), corr.labels)
        assertEquals(1.0, corr.matrix[0][0]!!, 1e-9)
        assertEquals(-1.0, corr.matrix[0][1]!!, 1e-9)
        assertEquals("−1.00", ChartGallery.corrText(-1.0))
        assertNull(ChartGallery.dailyCorrelation(emptyList(), later, listOf(m0, m1)))
        assertNull(ChartGallery.dailyCorrelation(windows.take(1), later, listOf(m0, m1)))
        assertNull(ChartGallery.dailyCorrelation(windows, later + 1, listOf(m0)))
        assertNull(ChartGallery.pearson(listOf(1.0, 1.0), listOf(2.0, 2.0)))
    }

    @Test fun rollingHistogramAndScatter() {
        val early = (0 until 29).map { i ->
            trade("t$i", "YES", "v|v2-fair|P", if (i % 2 == 0) 1.0 else -1.0).copy(closedAtMs = 1_000L + i)
        }
        assertTrue(ChartGallery.rollingWinRate(early).isEmpty())
        val line = ChartGallery.rollingWinRate(early + trade("last", "NO", "v|v2-fair|P", 1.0).copy(closedAtMs = 2_000))
        assertEquals(1, line.size)
        assertEquals(16.0 / 30.0 * 100.0, line[0].value, 1e-9)

        val nets = listOf(-3.0, -1.5, -0.5, 0.0, 0.5, 1.5, 3.0).mapIndexed { i, net ->
            trade("p$i", "YES", "v|v2-fair|P", net)
        }
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), ChartGallery.pnlBins(nets).map { it.count })
        assertTrue(ChartGallery.pnlBins(emptyList()).isEmpty())

        val win = trade("w", "YES", "v|v2-fair|P", 1.2).copy(soldContracts = 10, proceedsUsd = 6.0)
        val loss = trade("l", "NO", "v|v2-fair|P", -0.4).copy(entryPrice = 0.55, soldContracts = 10, proceedsUsd = 2.0)
        val open = win.copy(id = "o", state = ScalpState.OPEN, soldContracts = 0, proceedsUsd = 0.0, netUsd = null)
        val dots = ChartGallery.entryExit(listOf(win, loss, open))
        assertEquals(2, dots.size)
        assertEquals(40.0, dots[0].entryCents, 0.0)
        assertEquals(60.0, dots[0].exitCents, 0.0)
        assertTrue(dots[0].win)
        assertEquals(20.0, dots[1].exitCents, 0.0)
        assertTrue(!dots[1].win)
        assertEquals("1 win · 1 loss", ChartGallery.winLossCaption(1, 1))
        assertTrue(ChartGallery.entryExit(listOf(open)).isEmpty())
    }

    @Test fun familyStackSumsToCombined() {
        val model = ChartGallery.familyStacks(
            listOf(
                ChartGallery.FamilyEvent(10, "AI alert", 2.0),
                ChartGallery.FamilyEvent(20, "manual paper", -0.5),
                ChartGallery.FamilyEvent(30, "AI alert", 1.0),
                ChartGallery.FamilyEvent(40, "", Double.NaN)
            )
        )!!
        assertEquals(listOf("AI alert", "manual paper"), model.names)
        assertEquals(0.0, model.values[0].first(), 0.0)
        assertEquals(3.0, model.values[0].last(), 1e-9)
        assertEquals(-0.5, model.values[1].last(), 1e-9)
        val total = model.values.sumOf { it.last() }
        assertEquals(2.5, total, 1e-9)
        assertNull(ChartGallery.familyStacks(emptyList()))
        assertEquals("manual paper", ChartGallery.scorecardFamily("manual", null, "Manual"))
        assertEquals("AI picks", ChartGallery.scorecardFamily(null, null, "AI picks"))
    }

    @Test fun cumulativeDepthKeepsBothSides() {
        val series = ChartGallery.cumulativeDepth(
            yes = listOf(0.60 to 10.0, 0.50 to 5.0, -1.0 to 9.0),
            no = listOf(0.40 to 8.0, 0.40 to 0.0)
        )
        assertEquals(listOf("UP bid", "UP ask", "DOWN bid", "DOWN ask"), series.map { it.name })
        assertEquals(listOf(60.0, 50.0), series[0].cents)
        assertEquals(listOf(10.0, 15.0), series[0].size)
        assertEquals(listOf(60.0), series[1].cents)
        assertEquals(listOf(8.0), series[1].size)
        assertEquals(listOf(40.0), series[2].cents)
        assertEquals(listOf(40.0, 50.0), series[3].cents)
        assertEquals(listOf(10.0, 15.0), series[3].size)
        assertTrue(series[0].up && series[0].bid && !series[1].bid && !series[3].up)
        assertTrue(ChartGallery.cumulativeDepth(emptyList(), emptyList()).isEmpty())
    }

    @Test fun calibrationBucketsUpChance() {
        val points = ChartGallery.calibration(
            listOf(42.0 to true, 0.44 to false, 90.0 to true, 140.0 to true, 50.0 to true)
        )
        assertEquals(listOf("40–50%", "50–60%", "90–100%"), points.map { it.label })
        assertEquals(2, points[0].n)
        assertEquals(43.0, points[0].predicted, 1e-9)
        assertEquals(50.0, points[0].actual, 1e-9)
        assertEquals(100.0, points[1].actual, 0.0)
        assertEquals(100.0, points[2].actual, 0.0)
        assertTrue(ChartGallery.calibration(emptyList()).isEmpty())
        assertTrue(ChartGallery.calibration(listOf(Double.NaN to true)).isEmpty())
    }

    @Test fun feeLegSplitsGrossFeesAndNet() {
        val closed = trade("w", "YES", "v|v2-fair|P", 1.0).copy(entryFeeUsd = 0.20, exitFeeUsd = 0.10)
        val leg = ChartGallery.feeLegFromTrades("Window", listOf(closed, closed.copy(id = "open", state = ScalpState.OPEN, netUsd = null)))!!
        assertEquals(1.3, leg.gross, 1e-9)
        assertEquals(0.3, leg.fees, 1e-9)
        assertEquals(1.0, leg.net, 1e-9)
        assertNull(ChartGallery.feeLegFromTrades("Window", emptyList()))
        assertNull(ChartGallery.feeLeg("Day", 0.0, 0.0, 0.0))
        assertNull(ChartGallery.dayLeg(emptyList()))
    }

    @Test fun knobTicksDropUnchangedAndSort() {
        val ticks = ChartGallery.knobTicks(
            listOf(
                ChartGallery.KnobTick(200, "v2-fair", "stopCents", "20", "19"),
                ChartGallery.KnobTick(100, "v2-dip", "maxEntryCents", "50", "48"),
                ChartGallery.KnobTick(300, "v2-fair", "stopCents", "19", "19"),
                ChartGallery.KnobTick(0, "v2-fair", "stopCents", "1", "2"),
                ChartGallery.KnobTick(150, " ", "stopCents", "1", "2")
            )
        )
        assertEquals(listOf(100L, 200L), ticks.map { it.atMs })
        assertEquals("48", ticks[0].to)
        assertTrue(ChartGallery.knobTicks(emptyList()).isEmpty())
    }

    @Test fun distanceToStrikeSubtractsTheStrike() {
        val points = listOf(
            BidPoint(2, 41f, 59f, 130.0),
            BidPoint(1, 40f, 60f, 100.0),
            BidPoint(3, null, null, null),
            BidPoint(4, 40f, 60f, -5.0)
        )
        val line = ChartGallery.distanceToStrike(points, 110.0)
        assertEquals(listOf(1L, 2L), line.map { it.tMs })
        assertEquals(listOf(-10.0, 20.0), line.map { it.value })
        assertTrue(ChartGallery.distanceToStrike(points, null).isEmpty())
        assertTrue(ChartGallery.distanceToStrike(emptyList(), 110.0).isEmpty())
        assertTrue(ChartGallery.distanceToStrike(points, Double.NaN).isEmpty())
    }

    @Test fun quietWindowStillDrawsBothSidesAtZero() {
        val start = 1_700_000_000_000L
        val lines = ChartGallery.windowSidePnl(emptyList(), "KXBTC15M-26OCT210930-30", start, start + 900_000, start + 60_000, 0.42, 0.58)
        assertEquals(listOf("UP", "DOWN"), lines.map { it.name })
        assertTrue(lines.all { it.points.size >= 2 && it.points.all { p -> p.value == 0.0 } })
        assertTrue(ChartGallery.windowSidePnl(emptyList(), "KXBTC", 10, 10, 12, null, null).isEmpty())
    }

    @Test fun windowPnlStepsOnlyTheClosedSide() {
        val start = 1_700_000_000_000L
        val close = start + 900_000
        val up = trade("up", "yes", "v|v2-fair|P", 1.25).copy(closedAtMs = start + 120_000)
        val other = trade("eth", "YES", "v|v2-fair|P", 4.0).copy(ticker = "KXETH15M-26OCT181500-00")
        val lines = ChartGallery.windowSidePnl(listOf(up, other), up.ticker, start, close, start + 200_000, 0.40, 0.55)
        val upLine = lines.first { it.name == "UP" }
        val downLine = lines.first { it.name == "DOWN" }
        assertEquals(0.0, upLine.points.first().value, 0.0)
        assertEquals(1.25, upLine.points.last().value, 1e-9)
        assertTrue(downLine.points.all { it.value == 0.0 })
    }

    @Test fun openMarkSitsOnTheRightEdge() {
        val start = 1_700_000_000_000L
        val close = start + 900_000
        val open = trade("o", "YES", "v|v2-fair|P", 0.0).copy(state = ScalpState.OPEN, netUsd = null, closedAtMs = null)
        val lines = ChartGallery.windowSidePnl(listOf(open), open.ticker, start, close, start + 60_000, 0.55, 0.40)
        assertEquals(open.unrealizedUsd(0.55)!!, lines.first { it.name == "UP" }.points.last().value, 1e-6)
        assertEquals(0.0, lines.first { it.name == "DOWN" }.points.last().value, 0.0)
    }

    @Test fun depthTotalsAreContractsNotCentsTimesContracts() {
        val series = ChartGallery.cumulativeDepth(listOf(0.40 to 100.0, 0.30 to 50.0), listOf(0.60 to 80.0))
        val upBid = series.first { it.name == "UP bid" }
        assertEquals(listOf(100.0, 150.0), upBid.size)
        assertEquals("150 ct", ChartGallery.contractText(upBid.size.last()))
        assertEquals("1,528,863 ct", ChartGallery.contractText(1_528_863.0))
        val summary = ChartGallery.depthSummary(series)
        assertTrue(summary.contains("150 ct"))
        assertTrue(summary.contains("80 ct"))
        assertTrue(!summary.contains("4000") && !summary.contains("4,000"))
    }

    private fun trade(id: String, side: String, rule: String, net: Double) = ScalpTrade(
        id = id,
        ticker = "KXBTC15M-26OCT181500-00",
        side = side,
        state = ScalpState.CLOSED,
        signalAtMs = 1_000,
        signalAsk = 0.40,
        fairAtSignal = 0.5,
        contracts = 10,
        entryPrice = 0.40,
        entryAtMs = 2_000,
        closedAtMs = 3_000,
        netUsd = net,
        ruleVersion = rule
    )
}
