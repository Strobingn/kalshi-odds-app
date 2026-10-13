package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers behind the Arena charts. */
class ArenaChartsTest {

    private fun trade(strategy: ScalpStrategy, at: Long, pnl: Double, contracts: Int = 10) = ScalpTrade(
        closedAtMs = at, run = 1L, id = at, strategy = strategy.name, ticker = "KXBTC15M-X", side = "YES",
        contracts = contracts, entry = 0.50, exit = 0.51, kind = "TARGET", entryFeeUsd = 0.0, exitFeeUsd = 0.0,
        pnlUsd = pnl, postedAtMs = at, filledAtMs = at, queueAhead = 0.0, queueKnown = true,
        predictionCents = 0.0, expectedCents = 0.0
    )

    @Test
    fun equityCurvesStartAtZeroTogetherAndRunInTimeOrder() {
        val trades = listOf(
            trade(ScalpStrategy.BOUNCE, 3_000L, -0.5),
            trade(ScalpStrategy.DIP_HUNTER, 1_000L, 1.0),
            trade(ScalpStrategy.DIP_HUNTER, 2_000L, -0.25)
        )
        val curves = ArenaCharts.equityCurves(trades)
        assertEquals(listOf(ScalpStrategy.DIP_HUNTER, ScalpStrategy.BOUNCE), curves.map { it.strategy })
        val dip = curves.first()
        assertEquals(listOf(1_000L, 1_000L, 2_000L), dip.points.map { it.tMs })
        assertEquals(listOf(0.0, 1.0, 0.75), dip.points.map { it.pnlUsd })
        assertEquals("every curve starts at the first trade shown", 1_000L, curves.last().points.first().tMs)
        assertEquals(-0.5, curves.last().last, 1e-12)
        assertEquals(1.0, ArenaCharts.valueAt(dip, 1_500L), 1e-12)
        assertEquals(0.0, ArenaCharts.valueAt(curves.last(), 2_999L), 1e-12)
        assertTrue(ArenaCharts.equityCurves(emptyList()).isEmpty())
    }

    @Test
    fun thinningKeepsTheEnds() {
        val pts = (0 until 1_000).map { CurvePoint(it.toLong(), it.toDouble()) }
        val thin = ArenaCharts.thin(pts, 50)
        assertEquals(50, thin.size)
        assertEquals(pts.first(), thin.first())
        assertEquals(pts.last(), thin.last())
    }

    @Test
    fun theTopThreeAndTheSelectedOneAreColoured() {
        val curves = listOf(1.0, 5.0, -2.0, 3.0, 0.5).mapIndexed { i, v ->
            ArenaCharts.Curve(ScalpStrategy.values()[i], listOf(CurvePoint(0, 0.0), CurvePoint(1, v)))
        }
        assertEquals(
            listOf(ScalpStrategy.values()[1], ScalpStrategy.values()[3], ScalpStrategy.values()[0]),
            ArenaCharts.highlighted(curves, null).map { it.strategy }
        )
        assertEquals(4, ArenaCharts.highlighted(curves, ScalpStrategy.values()[2]).size)
        assertEquals("already in the top three", 3, ArenaCharts.highlighted(curves, ScalpStrategy.values()[1]).size)
    }

    @Test
    fun histogramBinsCentsPerContractWithOpenEnds() {
        val trades = listOf(
            trade(ScalpStrategy.DIP_HUNTER, 1, -0.31),      // −3.1¢ a contract
            trade(ScalpStrategy.DIP_HUNTER, 2, 0.05),       // +0.5¢
            trade(ScalpStrategy.LATE_FAVORITE, 3, 1.4),     // +14¢
            trade(ScalpStrategy.LONG_SHOT, 4, -1.6),        // −16¢
            trade(ScalpStrategy.LONG_SHOT, 5, 8.5),         // +85¢: top open bin
            trade(ScalpStrategy.LONG_SHOT, 6, -2.5)         // −25¢: bottom open bin
        )
        val bins = ArenaCharts.histogram(trades)
        assertEquals(22, bins.size)
        assertEquals(6, bins.sumOf { it.count })
        assertEquals(1, bins.first().count)
        assertEquals("under −20¢", bins.first().label)
        assertEquals(1, bins.last().count)
        assertEquals("+20¢ or more", bins.last().label)
        val b = bins.first { it.loCents == -4.0 }
        assertEquals(1, b.count)
        assertEquals("−4¢ to −2¢", b.label)
        assertTrue(b.negative)
        assertEquals(1, bins.first { it.loCents == 0.0 }.count)
        assertTrue(!bins.first { it.loCents == 0.0 }.negative)
        assertEquals(3, ArenaCharts.histogram(trades, ScalpStrategy.LONG_SHOT).sumOf { it.count })
    }

    @Test
    fun barsFromTheRecordFollowTheLeaderboard() {
        val st = ScalperState(
            groups = mapOf(
                ScalpStrategy.BOUNCE.name to ScalpStats(closed = 4, wins = 3, losses = 1, pnlUsd = 2.0, feesUsd = 0.5, targets = 3, stops = 1),
                ScalpStrategy.LONG_SHOT.name to ScalpStats(closed = 2, wins = 0, losses = 2, pnlUsd = -3.0, feesUsd = 0.2, settled = 2),
                ScalperState.hourKey(14) to ScalpStats(closed = 6, pnlUsd = -1.0)
            ),
            version = ScalperState.VERSION
        )
        assertEquals(listOf(ScalpStrategy.BOUNCE, ScalpStrategy.LONG_SHOT), ArenaCharts.exitMix(st).map { it.strategy })
        assertEquals(ArenaCharts.ExitMix(ScalpStrategy.LONG_SHOT, 0, 0, 0, 2), ArenaCharts.exitMix(st)[1])
        assertEquals(0.75, ArenaCharts.winRates(st).first().rate, 1e-12)
        val fees = ArenaCharts.feeDrag(st).first()
        assertEquals(2.5, fees.grossUsd, 1e-12)
        assertEquals(2.0, fees.netUsd, 1e-12)
        val hours = ArenaCharts.hourly(st)
        assertEquals(24, hours.size)
        assertEquals(-1.0, hours[14]!!, 1e-12)
        assertEquals(null, hours[13])
    }

    @Test
    fun axisRangesAreRoundAndIncludeZero() {
        assertEquals(0.0 to 10.0, ArenaCharts.niceRange(1.3, 9.2))
        assertEquals(-4.0 to 2.0, ArenaCharts.niceRange(-3.1, 1.9))
        assertEquals(-1.0 to 1.0, ArenaCharts.niceRange(0.0, 0.0))
        assertEquals(0.5, ArenaCharts.niceStep(0.31), 1e-12)
        assertEquals(20.0, ArenaCharts.niceStep(11.0), 1e-12)
    }
}
