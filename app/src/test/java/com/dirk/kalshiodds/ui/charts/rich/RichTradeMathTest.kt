package com.dirk.kalshiodds.ui.charts.rich

import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTicker
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.ui.charts.rich.RichTradeMath.ExitBucket
import com.dirk.kalshiodds.ui.charts.rich.RichTradeMath.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.sqrt

/** Recomputes every chart stat from fixture trades. Fees use ceil(0.07·C·P·(1−P)) to the cent. */
class RichTradeMathTest {
    private val et: ZoneId = ZoneId.of("America/New_York")

    private fun fee(contracts: Int, price: Double): Double {
        if (contracts <= 0 || price <= 0.0 || price >= 1.0) return 0.0
        return ceil(0.07 * contracts * price * (1.0 - price) * 100.0 - 1e-9) / 100.0
    }

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(et).toInstant().toEpochMilli()

    private fun trade(
        id: String,
        closeLocal: String,
        net: Double,
        sideUp: Boolean = true,
        entryLocal: String = closeLocal,
        price: Double = 0.40,
        contracts: Int = 10,
        secondsLeft: Double? = 600.0,
        holdMs: Long? = 45_000L,
        exit: ExitBucket = ExitBucket.TP,
        predicted: Double? = null,
        confidence: Double? = predicted,
        won: Boolean = net > 0.0,
        stake: Double = price * contracts,
        scalperId: String? = "alpha",
        open: Boolean = false,
        feeUsd: Double = fee(contracts, price)
    ) = Trade(
        id = id,
        closedAtMs = at(closeLocal),
        entryAtMs = at(entryLocal),
        netUsd = net,
        feeUsd = feeUsd,
        sideUp = sideUp,
        entryPrice = price,
        contracts = contracts,
        secondsLeft = secondsLeft,
        holdMs = holdMs,
        exit = exit,
        predicted = predicted,
        confidence = confidence,
        won = won,
        stakeUsd = stake,
        scalperId = scalperId,
        open = open
    )

    @Test fun feeMatchesKalshiCeilToTheCent() {
        val cases = listOf(10 to 0.40, 1 to 0.50, 10 to 0.50, 100 to 0.50, 10 to 0.42, 25 to 0.20, 7 to 0.37, 0 to 0.40, 10 to 0.0, 10 to 1.0)
        for ((c, p) in cases) {
            assertEquals(fee(c, p), RichTradeMath.kalshiCeilFee(c, p), 0.0)
            assertEquals(ScalpRule.orderFee(c, p), RichTradeMath.kalshiCeilFee(c, p), 0.0)
        }
        assertEquals(0.17, fee(10, 0.40), 0.0)
        assertEquals(0.18, fee(10, 0.50), 0.0)
        assertEquals(1.75, fee(100, 0.50), 0.0)
    }

    @Test fun emptyReportSaysNoTrades() {
        val report = RichTradeMath.report(emptyList(), 0L)
        assertEquals(0, report.summary.n)
        assertEquals("No trades yet", RichTradeMath.countLabel(report.summary.n))
        assertTrue(report.equity.isEmpty())
        assertTrue(report.drawdown.isEmpty())
        assertTrue(report.rollingWin20.isEmpty())
        assertTrue(report.calibration.isEmpty())
        assertNull(report.summary.profitFactor)
        assertNull(report.summary.sharpe)
        assertNull(report.summary.expectancyUsd)
    }

    @Test fun smallSampleLabelsNAndSkipsShortWindows() {
        val rows = (1..3).map { trade("t$it", "2026-10-20T10:0$it:00", if (it == 2) -0.2 else 0.4) }
        val report = RichTradeMath.report(rows, at("2026-10-20T12:00:00"))
        assertEquals(3, report.summary.n)
        assertEquals("n=3", RichTradeMath.countLabel(report.summary.n))
        assertTrue(report.rollingWin20.isEmpty())
        assertTrue(report.rollingWin50.isEmpty())
        assertTrue(report.rollingNet20.isEmpty())
        assertTrue(report.rollingNet50.isEmpty())
        assertEquals(4, report.equity.size) // $0 anchor + 3
    }

    @Test fun equityDrawdownAndSideSplitRecompute() {
        val rows = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0, sideUp = true),
            trade("b", "2026-10-20T11:00:00", -0.5, sideUp = false),
            trade("c", "2026-10-20T12:00:00", 0.25, sideUp = true)
        )
        val report = RichTradeMath.report(rows, at("2026-10-20T13:00:00"))
        assertEquals(0.0, report.equity.first().value, 1e-9)
        assertEquals(0.75, report.equity.last().value, 1e-9)
        assertEquals(0.0, report.drawdown[0].value, 1e-9)
        assertEquals(0.0, report.drawdown[1].value, 1e-9)
        assertEquals(-0.5, report.drawdown[2].value, 1e-9)
        assertEquals(-0.25, report.drawdown.last().value, 1e-9)
        assertEquals(1.25, report.equityUp.last().value, 1e-9)
        assertEquals(-0.5, report.equityDown.last().value, 1e-9)
        val side = report.bySide.single()
        assertEquals(1.25, side.up, 1e-9)
        assertEquals(-0.5, side.down, 1e-9)
        assertEquals(3, side.n)
    }

    @Test fun flatSideIsZeroNotMissing() {
        val rows = listOf(trade("a", "2026-10-20T10:00:00", 1.0, sideUp = true))
        val report = RichTradeMath.report(rows, at("2026-10-20T11:00:00"))
        assertEquals(1.0, report.equityUp.last().value, 1e-9)
        assertEquals(0.0, report.equityDown.first().value, 0.0)
        assertEquals(0.0, report.equityDown.last().value, 0.0)
        assertTrue(report.equityDown.size >= 2)
    }

    @Test fun summaryStatsRecomputeFromFixture() {
        val rows = listOf(
            trade("a", "2026-10-20T10:00:00", 1.00, contracts = 10, price = 0.40),
            trade("b", "2026-10-20T10:05:00", 0.40, contracts = 10, price = 0.42),
            trade("c", "2026-10-20T10:10:00", -0.50, sideUp = false, contracts = 8, price = 0.37),
            trade("d", "2026-10-20T10:15:00", -0.20, sideUp = false, contracts = 5, price = 0.22),
            trade("e", "2026-10-21T15:00:00", 0.25, contracts = 10, price = 0.55),
            trade("f", "2026-10-21T15:05:00", -0.80, sideUp = false, contracts = 10, price = 0.31),
            trade("g", "2026-10-21T15:10:00", 2.00, contracts = 12, price = 0.48)
        )
        val report = RichTradeMath.report(rows, at("2026-10-21T16:00:00"))
        val s = report.summary
        val nets = rows.map { it.netUsd }
        val wins = nets.filter { it > 0.0 }
        val losses = nets.filter { it < 0.0 }
        assertEquals(7, s.n)
        assertEquals(wins.size, s.wins)
        assertEquals(losses.size, s.losses)
        assertEquals(0, s.scratches)
        assertEquals(nets.sum(), s.netUsd, 1e-9)
        assertEquals(rows.sumOf { it.feeUsd }, s.feesUsd, 1e-9)
        assertEquals(s.netUsd + s.feesUsd, s.grossUsd, 1e-9)
        assertEquals(nets.max(), s.bestUsd!!, 1e-9)
        assertEquals(nets.min(), s.worstUsd!!, 1e-9)
        assertEquals(2, s.longestWinStreak)
        assertEquals(2, s.longestLossStreak)
        assertEquals(wins.sum() / -losses.sum(), s.profitFactor!!, 1e-9)
        assertEquals(nets.sum() / nets.size, s.expectancyUsd!!, 1e-9)
        assertEquals(wins.average(), s.avgWinUsd!!, 1e-9)
        assertEquals(losses.average(), s.avgLossUsd!!, 1e-9)
        assertEquals(wins.size.toDouble() / nets.size, s.winRate!!, 1e-9)
        val mean = nets.average()
        val sd = sqrt(nets.sumOf { (it - mean) * (it - mean) } / (nets.size - 1))
        assertEquals(mean / sd, s.sharpe!!, 1e-9)
        val first = LocalDateTime.parse("2026-10-20T10:00:00").atZone(et).toLocalDate()
        val last = LocalDateTime.parse("2026-10-21T15:10:00").atZone(et).toLocalDate()
        val span = ChronoUnit.DAYS.between(first, last) + 1
        assertEquals(nets.size / span.toDouble(), s.tradesPerDay!!, 1e-9)
        assertEquals(s.grossUsd, report.grossSeries.last().value, 1e-9)
        assertEquals(s.feesUsd, report.feeSeries.last().value, 1e-9)
        assertEquals(rows.sumOf { fee(it.contracts, it.entryPrice!!) }, s.feesUsd, 1e-9)
    }

    @Test fun scratchBreaksStreakAndProfitFactorIsInfiniteWithoutLosses() {
        val scratch = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0),
            trade("b", "2026-10-20T10:05:00", 0.0),
            trade("c", "2026-10-20T10:10:00", 1.0)
        )
        val s = RichTradeMath.report(scratch, at("2026-10-20T11:00:00")).summary
        assertEquals(1, s.longestWinStreak)
        assertEquals(1, s.scratches)
        assertEquals(2.0 / 3.0, s.winRate!!, 1e-9)
        val onlyWins = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0),
            trade("b", "2026-10-20T10:05:00", 3.0)
        )
        val w = RichTradeMath.report(onlyWins, at("2026-10-20T11:00:00")).summary
        assertTrue(w.profitFactor!!.isInfinite())
        assertNull(w.avgLossUsd)
        assertEquals(sqrt(2.0), w.sharpe!!, 1e-9)
    }

    @Test fun identicalNetsHaveNoSharpe() {
        val rows = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0),
            trade("b", "2026-10-20T10:05:00", 1.0)
        )
        assertNull(RichTradeMath.report(rows, at("2026-10-20T11:00:00")).summary.sharpe)
    }

    @Test fun pnlHistogramCountsUpAndDownFromEdges() {
        val nets = listOf(-2.5 to true, -2.0 to false, -1.5 to true, -0.5 to false, 0.0 to true, 0.5 to false, 1.5 to true, 2.5 to false)
        val rows = nets.mapIndexed { i, (net, up) ->
            trade("h$i", "2026-10-20T10:%02d:00".format(i), net, sideUp = up)
        }
        val bins = RichTradeMath.report(rows, at("2026-10-20T12:00:00")).pnlHistogram
        assertEquals(listOf("< −$2", "−$2 to −$1", "−$1 to −25¢", "±25¢", "25¢ to $1", "$1 to $2", "≥ $2"), bins.map { it.label })
        assertEquals(listOf(1, 2, 1, 1, 1, 1, 1), bins.map { it.n })
        assertEquals(1.0, bins[0].up, 0.0)
        assertEquals(1.0, bins[1].down, 0.0) // -2.0 is not < -2
        assertEquals(1.0, bins[1].up, 0.0)
    }

    @Test fun rollingTwentyAndFiftyRecompute() {
        val wins = (0 until 25).map { trade("w$it", "2026-10-20T08:00:00".let { base -> plusMinutes(base, it) }, 1.0) }
        val losses = (0 until 25).map { trade("l$it", plusMinutes("2026-10-20T08:00:00", 25 + it), -1.0) }
        val report = RichTradeMath.report(wins + losses, at("2026-10-20T12:00:00"))
        assertEquals(1, report.rollingWin50.size)
        assertEquals(0.5, report.rollingWin50.last().value, 1e-9)
        assertEquals(0.0, report.rollingNet50.last().value, 1e-9)
        val win20AtLastWin = report.rollingWin20[25 - 20]
        assertEquals(1.0, win20AtLastWin.value, 1e-9)
        assertEquals(0.0, report.rollingWin20.last().value, 1e-9)
        assertEquals(-1.0, report.rollingNet20.last().value, 1e-9)
        val only20 = RichTradeMath.report(wins.take(20), at("2026-10-20T12:00:00"))
        assertEquals(1, only20.rollingWin20.size)
        assertEquals(1.0, only20.rollingWin20.single().value, 0.0)
        assertEquals(1.0, only20.rollingNet20.single().value, 0.0)
        assertTrue(only20.rollingWin50.isEmpty())
    }

    @Test fun entryPriceBandsAreFiveCentGroups() {
        assertEquals("40–45¢", RichTradeMath.entryBand(0.40))
        assertEquals("40–45¢", RichTradeMath.entryBand(0.42))
        assertEquals("5–10¢", RichTradeMath.entryBand(0.05))
        assertEquals("0–5¢", RichTradeMath.entryBand(0.0))
        assertEquals("95–100¢", RichTradeMath.entryBand(1.0))
        assertNull(RichTradeMath.entryBand(1.01))
        val rows = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0, price = 0.42, sideUp = true),
            trade("b", "2026-10-20T10:05:00", -0.4, price = 0.44, sideUp = false),
            trade("c", "2026-10-20T10:10:00", 0.2, price = 0.10, sideUp = true)
        )
        val bins = RichTradeMath.report(rows, at("2026-10-20T11:00:00")).byEntry
        val mid = bins.single { it.label == "40–45¢" }
        assertEquals(2, mid.n)
        assertEquals(1.0, mid.up, 1e-9)
        assertEquals(-0.4, mid.down, 1e-9)
        assertEquals(0.2, bins.single { it.label == "10–15¢" }.up, 1e-9)
    }

    @Test fun timeLeftAndHourUseEasternEntry() {
        assertEquals("10–12 min", RichTradeMath.timeLeftBand(600.0))
        assertEquals("<1 min", RichTradeMath.timeLeftBand(30.0))
        assertEquals("Unknown", RichTradeMath.timeLeftBand(null))
        assertEquals("Unknown", RichTradeMath.timeLeftBand(-5.0))
        val rows = listOf(
            trade("a", "2026-10-20T14:50:00", 1.2, entryLocal = "2026-10-20T10:15:00", secondsLeft = 600.0, sideUp = true),
            trade("b", "2026-01-15T22:40:00", -0.3, entryLocal = "2026-01-15T22:30:00", secondsLeft = 30.0, sideUp = false)
        )
        val report = RichTradeMath.report(rows, at("2026-01-15T23:00:00"))
        assertEquals(1.2, report.byTimeLeft.single { it.label == "10–12 min" }.up, 1e-9)
        assertEquals(-0.3, report.byTimeLeft.single { it.label == "<1 min" }.down, 1e-9)
        assertEquals("10 AM", report.byHour.single { it.n == 1 && it.up > 0 }.label)
        assertEquals("10 PM", report.byHour.single { it.down < 0 }.label)
        assertEquals(1.2, report.byHour.single { it.label == "10 AM" }.up, 1e-9)
    }

    @Test fun holdDistributionCountsEachSide() {
        val holds = listOf(10_000L to true, 45_000L to false, 200_000L to true, 400_000L to true, 700_000L to false, null to true)
        val rows = holds.mapIndexed { i, (hold, up) ->
            trade("h$i", plusMinutes("2026-10-20T10:00:00", i), 0.1, sideUp = up, holdMs = hold)
        }
        val bins = RichTradeMath.report(rows, at("2026-10-20T12:00:00")).hold.associateBy { it.label }
        assertEquals(1, bins.getValue("<30s").n)
        assertEquals(1.0, bins.getValue("<30s").up, 0.0)
        assertEquals(1.0, bins.getValue("30s–2m").down, 0.0)
        assertEquals(1, bins.getValue("2–5m").n)
        assertEquals(1, bins.getValue("5–10m").n)
        assertEquals(1, bins.getValue("≥10m").n)
        assertEquals(1.0, bins.getValue("Unknown").up, 0.0)
    }

    @Test fun exitReasonsFollowTheLastClause() {
        assertEquals(ExitBucket.TP, RichTradeMath.exitBucket(ScalpRule.ExitReason.PROFIT_TARGET.label))
        assertEquals(ExitBucket.TP, RichTradeMath.exitBucket(ScalpRule.ExitReason.GAP_CLOSED.label))
        assertEquals(ExitBucket.STOP, RichTradeMath.exitBucket(ScalpRule.ExitReason.STOP.label))
        assertEquals(ExitBucket.SETTLE, RichTradeMath.exitBucket(ScalpRule.ExitReason.SETTLED.label))
        assertEquals(ExitBucket.TIME, RichTradeMath.exitBucket(ScalpRule.ExitReason.TIME_STOP.label))
        assertEquals(ExitBucket.TIME, RichTradeMath.exitBucket(ScalpRule.ExitReason.CF_TIME.label))
        assertEquals(ExitBucket.TIME, RichTradeMath.exitBucket(ScalpRule.ExitReason.CF_EXIT_BY_CLOSE.label))
        assertEquals(ExitBucket.TIME, RichTradeMath.exitBucket(ScalpRule.ExitReason.FAIR_GAP_HOLD.label))
        assertEquals(ExitBucket.OTHER, RichTradeMath.exitBucket(ScalpRule.ExitReason.TURN_DOWN.label))
        assertEquals(ExitBucket.OTHER, RichTradeMath.exitBucket(ScalpRule.ExitReason.CF_GAP_GONE.label))
        assertEquals(ExitBucket.TIME, RichTradeMath.exitBucket("V2 user max hold reached"))
        assertEquals(ExitBucket.TIME, RichTradeMath.exitBucket("v2 exit before close: stale book"))
        assertEquals(ExitBucket.SETTLE, RichTradeMath.exitBucket("profit target hit → held to settlement (no exit liquidity)"))
        assertEquals(ExitBucket.OTHER, RichTradeMath.exitBucket(null))
        val rows = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0, exit = ExitBucket.TP, sideUp = true),
            trade("b", "2026-10-20T10:05:00", -0.5, exit = ExitBucket.STOP, sideUp = false),
            trade("c", "2026-10-20T10:10:00", 0.2, exit = ExitBucket.SETTLE),
            trade("d", "2026-10-20T10:15:00", -0.1, exit = ExitBucket.TIME, sideUp = false)
        )
        val bins = RichTradeMath.report(rows, at("2026-10-20T11:00:00")).exits.associateBy { it.label }
        assertEquals(1.0, bins.getValue("TP").up, 1e-9)
        assertEquals(-0.5, bins.getValue("Stop").down, 1e-9)
        assertEquals(0.2, bins.getValue("Settle").total, 1e-9)
        assertEquals(-0.1, bins.getValue("Time").down, 1e-9)
    }

    @Test fun dailyBarsAreEasternCloseDays() {
        val rows = listOf(
            trade("a", "2026-10-20T23:30:00", 1.0, sideUp = true),
            trade("b", "2026-10-22T00:30:00", -0.4, sideUp = false)
        )
        val daily = RichTradeMath.report(rows, at("2026-10-22T12:00:00")).daily
        assertEquals(listOf("10/20", "10/22"), daily.map { it.label })
        assertEquals(1.0, daily[0].up, 1e-9)
        assertEquals(-0.4, daily[1].down, 1e-9)
    }

    @Test fun calibrationBrierAndConfidenceRecompute() {
        val rows = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0, predicted = 0.62, confidence = 0.70, won = true),
            trade("b", "2026-10-20T10:05:00", -0.2, predicted = 0.62, confidence = 0.70, won = false, sideUp = false),
            trade("c", "2026-10-20T10:10:00", -0.1, predicted = 0.15, confidence = 0.15, won = false)
        )
        val report = RichTradeMath.report(rows, at("2026-10-20T11:00:00"))
        val hi = report.calibration.single { it.label == "60–70%" }
        assertEquals(2, hi.n)
        assertEquals(0.62, hi.predicted, 1e-9)
        assertEquals(0.5, hi.actual, 1e-9)
        val lo = report.calibration.single { it.label == "10–20%" }
        assertEquals(1, lo.n)
        assertEquals(0.0, lo.actual, 0.0)
        val b1 = (0.62 - 1.0) * (0.62 - 1.0)
        val b2 = b1 + (0.62 - 0.0) * (0.62 - 0.0)
        val b3 = b2 + (0.15 - 0.0) * (0.15 - 0.0)
        assertEquals(b1, report.brier[0].value, 1e-9)
        assertEquals(b2 / 2.0, report.brier[1].value, 1e-9)
        assertEquals(b3 / 3.0, report.brier[2].value, 1e-9)
        val conf = report.byConfidence.single { it.label == "70–80%" }
        assertEquals(1.0, conf.up, 1e-9)
        assertEquals(-0.2, conf.down, 1e-9)
    }

    @Test fun exposureAndBetSizeUseStoredStakeAndContracts() {
        val now = 5_000L
        val open = trade("open", "2026-10-20T10:00:00", 0.0, open = true, stake = 5.0, contracts = 3).copy(
            entryAtMs = 1_000L, closedAtMs = 1_000L
        )
        val settled = trade("done", "2026-10-20T09:00:00", 1.0, stake = 4.0, contracts = 10).copy(
            entryAtMs = 0L, closedAtMs = 2_000L
        )
        val report = RichTradeMath.report(listOf(open, settled), now)
        assertEquals(listOf(4.0, 9.0, 5.0, 5.0), report.exposure.map { it.value })
        assertEquals(listOf(0L, 1_000L, 2_000L, 5_000L), report.exposure.map { it.tMs })
        val size = report.sizeBins.associateBy { it.label }
        assertEquals(1, size.getValue("2–5").n)
        assertEquals(1, size.getValue("6–10").n)
        assertEquals(1, report.openCount)
        assertEquals(1, report.summary.n)
    }

    @Test fun compareFollowsRegistryOrderAndSkipsMissing() {
        val rows = listOf(
            trade("a1", "2026-10-20T10:00:00", 1.0, scalperId = "alpha"),
            trade("a2", "2026-10-20T10:05:00", 0.5, scalperId = "alpha"),
            trade("b1", "2026-10-20T10:02:00", -0.25, scalperId = "beta"),
            trade("z", "2026-10-20T10:03:00", 9.0, scalperId = "gone")
        )
        val registry = listOf("beta" to "Beta", "alpha" to "Alpha", "gamma" to "Gamma")
        val lines = RichTradeMath.compare(rows, registry)
        assertEquals(listOf("Beta", "Alpha"), lines.map { it.name })
        assertEquals(-0.25, lines[0].points.last().value, 1e-9)
        assertEquals(1.5, lines[1].points.last().value, 1e-9)
        val pack = RichChartLoader.scalp(rows, registry, focusId = null, max = 10)
        assertEquals(listOf("alpha", "beta"), pack.trades.mapNotNull { it.scalperId }.distinct().sorted())
        assertEquals(2, pack.counts.getValue("alpha"))
        assertTrue(pack.compare.none { it.name == "Gamma" })
        val focus = RichChartLoader.scalp(rows, registry, focusId = "alpha", max = 10)
        assertTrue(focus.compare.isEmpty())
        assertEquals(listOf("alpha"), focus.trades.map { it.scalperId }.distinct())
    }

    @Test fun duplicateRegistryLabelsKeepTheId() {
        val rows = listOf(
            trade("a", "2026-10-20T10:00:00", 1.0, scalperId = "a"),
            trade("b", "2026-10-20T10:05:00", 2.0, scalperId = "b")
        )
        val lines = RichTradeMath.compare(rows, listOf("a" to "Fair-gap", "b" to "Fair-gap"))
        assertEquals(listOf("Fair-gap (a)", "Fair-gap (b)"), lines.map { it.name })
    }

    @Test fun capKeepsOpensAndNewestClosed() {
        val open = listOf(
            trade("o1", "2026-10-20T10:00:00", 0.0, open = true).copy(entryAtMs = 10L),
            trade("o2", "2026-10-20T10:00:00", 0.0, open = true).copy(entryAtMs = 20L)
        )
        val closed = listOf(
            trade("c1", "2026-10-20T09:00:00", 1.0),
            trade("c2", "2026-10-20T11:00:00", 1.0),
            trade("c3", "2026-10-20T12:00:00", 1.0)
        )
        val (kept, capped) = RichTradeMath.cap(open + closed, max = 3)
        assertTrue(capped)
        assertEquals(setOf("o1", "o2", "c3"), kept.map { it.id }.toSet())
    }

    @Test fun thinKeepsEnds() {
        val pts = (0 until 10).map { RichTradeMath.Sample(it.toLong(), it.toDouble()) }
        val thin = RichTradeMath.thin(pts, 4)
        assertEquals(pts.first(), thin.first())
        assertEquals(pts.last(), thin.last())
        assertTrue(thin.size <= 4)
        assertEquals(pts, RichTradeMath.thin(pts, 20))
    }

    @Test fun queryIdsAreTheRegistryPlusItsAliases() {
        val codes = ScalpModels.ALL.map { it.code }
        val ids = RichChartLoader.scalpQueryIds(codes, null)
        assertEquals(codes.toSet(), ids.map { KeptScalpers.canonical(it) }.toSet())
        assertTrue(codes.all { it in ids })
        val focus = codes.first()
        assertEquals(KeptScalpers.storedIds(focus), RichChartLoader.scalpQueryIds(codes, focus))
        assertTrue(RichChartLoader.perQueryLimit(ids.size, focus = false) in 1..RichTradeMath.MAX_ROWS)
        assertEquals(RichTradeMath.MAX_ROWS, RichChartLoader.perQueryLimit(ids.size, focus = true))
    }

    @Test fun scalpRowUsesStoredFeesAndCanonicalAlias() {
        val ticker = "KXBTC15M-26OCT201500-00"
        val close = ScalpTicker.closeMs(ticker)!!
        val entryFee = fee(10, 0.40)
        val exitFee = fee(10, 0.55)
        val row = ScalpTrade(
            id = "s",
            ticker = ticker,
            side = "NO",
            state = ScalpState.CLOSED,
            signalAtMs = close - 700_000L,
            signalAsk = 0.40,
            fairAtSignal = 0.5,
            contracts = 10,
            entryPrice = 0.40,
            entryFeeUsd = entryFee,
            exitFeeUsd = exitFee,
            entryAtMs = close - 600_000L,
            closedAtMs = close - 100_000L,
            netUsd = 0.83,
            exitReason = ScalpRule.ExitReason.STOP.label,
            ruleVersion = "1|sf16|P"
        )
        val trade = RichTradeMath.fromScalp(row)!!
        assertEquals(entryFee + exitFee, trade.feeUsd, 0.0)
        assertEquals("sf16f", trade.scalperId)
        assertEquals(false, trade.sideUp)
        assertEquals(600.0, trade.secondsLeft!!, 1e-6)
        assertEquals("10–12 min", RichTradeMath.timeLeftBand(trade.secondsLeft))
        assertEquals(ExitBucket.STOP, trade.exit)
        assertEquals(0.83 + entryFee + exitFee, trade.netUsd + trade.feeUsd, 1e-9)
        assertNull(RichTradeMath.fromScalp(row.copy(state = ScalpState.OPEN, netUsd = null)))
        assertNull(RichTradeMath.fromScalp(row.copy(ruleVersion = "1|sf16|S")))
    }

    @Test fun paperFillRecomputesFeeUnlessTheNoteStoredOne() {
        val fill = PaperFill(
            id = "p",
            ticker = "KXBTC15M-26OCT201500-00",
            side = "YES",
            stakeUsd = 4.0,
            contracts = 10,
            limitPrice = 0.40,
            source = "autopilot",
            createdAtMs = 1_000L,
            settled = true,
            won = true,
            pnlUsd = 1.5,
            note = "",
            aiPct = 62.0,
            aiConfidence = 0.70,
            updatedAtMs = 5_000L
        )
        val trade = RichTradeMath.fromPaper(fill)!!
        assertEquals(fee(10, 0.40), trade.feeUsd, 0.0)
        assertEquals(1.5, trade.netUsd, 0.0)
        assertEquals(0.62, trade.predicted!!, 1e-9)
        assertEquals(0.70, trade.confidence!!, 1e-9)
        assertEquals(true, trade.won)
        assertEquals(false, trade.open)
        val noted = RichTradeMath.fromPaper(fill.copy(note = "Paper tile · fee $0.22"))!!
        assertEquals(0.22, noted.feeUsd, 0.0)
        val open = RichTradeMath.fromPaper(fill.copy(id = "o", settled = false, pnlUsd = null, won = null))!!
        assertTrue(open.open)
        assertEquals(0, RichTradeMath.report(listOf(open), 9_000L).summary.n)
        assertTrue(RichTradeMath.report(listOf(open), 9_000L).exposure.last().value > 0.0)
        assertNull(RichTradeMath.fromPaper(fill.copy(outcome = "void")))
        val pack = RichChartLoader.paper(listOf(fill, fill.copy(id = "o", settled = false, pnlUsd = null)))
        assertEquals(2, pack.trades.size)
        assertEquals(1.5 + fee(10, 0.40), RichTradeMath.report(pack.trades, 9_000L).summary.grossUsd, 1e-9)
    }

    private fun plusMinutes(local: String, minutes: Int): String {
        val t = LocalDateTime.parse(local).plusMinutes(minutes.toLong())
        return t.toString()
    }
}
