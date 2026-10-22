package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.data.local.history.HistoryAssembler
import com.dirk.kalshiodds.ui.components.TelemetryChartMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryChartMathTest {

    @Test
    fun quoteGaugeComputesBidAskMidSpreadAndAi() {
        val m = HomeFixtures.screenshotPhoneBtc()
        val g = TelemetryChartMath.quoteGauge(m)
        assertTrue(g.hasBook)
        assertEquals(33.0, g.yesBidCents!!, 1e-6)
        assertEquals(34.0, g.yesAskCents!!, 1e-6)
        assertEquals(33.5, g.midCents!!, 1e-6)
        assertEquals(1.0, g.spreadCents!!, 1e-6)
        assertEquals(39.0, g.aiYesCents!!, 1e-6)
        assertEquals(101.0, g.combinedAskCents!!, 1e-6)
    }

    @Test
    fun driverAndEnsembleBarsPreserveSignAndOrder() {
        val m = HomeFixtures.screenshotPhoneBtc()
        val drivers = TelemetryChartMath.driverBars(m.featureDevs)
        assertEquals(5, drivers.size)
        assertEquals("ai", drivers.first().name)
        assertEquals(5.5, drivers.first().deltaPp, 1e-6)
        assertEquals(1.0f, drivers.first().fractionOfMax, 1e-5f)
        assertTrue(drivers.any { it.name == "flow" && it.deltaPp < 0.0 })

        val ensemble = TelemetryChartMath.ensembleBars(m)
        assertTrue(ensemble.size >= 4)
        assertTrue(ensemble.any { it.label == "Mkt mid" && it.cents == 33.5 })
        assertTrue(ensemble.any { it.label == "FV blend" && it.cents == 39.0 })
        assertTrue(ensemble.any { it.label == "MLP" && it.cents == 41.0 })
        assertTrue(ensemble.any { it.label == "GBM" && it.cents == 38.0 })

        val gauges = TelemetryChartMath.microGauges(m)
        assertEquals(4, gauges.size)
        assertEquals("Confidence", gauges[0].label)
        assertEquals("62%", gauges[0].valueText)
    }

    @Test
    fun spreadDeltaSeriesDerivesCombinedBidsAndSpotDelta() {
        val pts = listOf(
            BidPoint(tMs = 1_000L, upBidCents = 42f, downBidCents = 56f, spotUsd = 84_150.0),
            BidPoint(tMs = 2_000L, upBidCents = 45f, downBidCents = 53f, spotUsd = 84_130.0)
        )
        val series = TelemetryChartMath.spreadDeltaSeries(pts, strikeUsd = 84_140.0)
        assertEquals(2, series.size)
        assertEquals(98f, series[0].combinedBidCents!!, 1e-4f)
        assertEquals(2f, series[0].impliedSpreadCents!!, 1e-4f)
        assertEquals(10.0, series[0].spotDeltaUsd!!, 1e-6)
        assertEquals(-10.0, series[1].spotDeltaUsd!!, 1e-6)
    }

    @Test
    fun equityCurveComputesPeakAndMaxDrawdown() {
        val pts = listOf(
            1L to 5.0,
            2L to 12.0,
            3L to 4.5,
            4L to 9.0
        )
        val eq = TelemetryChartMath.equityCurve(pts)
        assertEquals(12.0, eq.peakUsd, 1e-6)
        assertEquals(-7.5, eq.maxDrawdownUsd, 1e-6)
        assertEquals(9.0, eq.currentUsd, 1e-6)
        assertEquals(4, eq.drawdownPoints.size)
        assertEquals(-7.5, eq.drawdownPoints[2].second, 1e-6)
    }

    @Test
    fun calibrationAndBucketBarsReflectScorecardPicks() {
        val view = ScorecardCopy.of(HomeFixtures.sampleSettledEntries(), HomeFixtures.sampleSettledFills(), 0.0)
        val cal = TelemetryChartMath.calibrationCurve(view.ledger.picks)
        assertTrue(cal.isNotEmpty())
        cal.forEach { pt ->
            assertTrue(pt.count > 0)
            assertTrue(pt.predicted01 in 0.0..1.0)
            assertTrue(pt.actual01 in 0.0..1.0)
        }
        val manualOpposite = view.ledger.picks.first().copy(
            kind = com.dirk.kalshiodds.signal.feedback.ScorecardLedger.Kind.MANUAL,
            aiPct = 88.0,
            won = false
        )
        val calWithManual = TelemetryChartMath.calibrationCurve(listOf(manualOpposite))
        assertTrue("manual rows must not be bucketed into AI reliability curve", calWithManual.isEmpty())

        val brierPicks = (0 until 20).map { i ->
            view.ledger.picks.first().copy(
                kind = com.dirk.kalshiodds.signal.feedback.ScorecardLedger.Kind.AI,
                side = "YES",
                aiPct = 70.0,
                marketPct = 55.0,
                won = i < 14
            )
        }
        val brier = TelemetryChartMath.brierSummary(brierPicks)
        assertNotNull(brier)
        assertEquals(20, brier!!.n)
        assertTrue(brier.modelBrier < brier.marketBrier)

        val bars = TelemetryChartMath.bucketBars(view.bySide)
        assertTrue(bars.isNotEmpty())
        val upBar = bars.first { it.label.startsWith("UP") }
        assertTrue(upBar.settledCount > 0)
        assertTrue(upBar.winFraction in 0f..1f)
    }

    @Test
    fun signalEdgeDistributionsBucketSignalsByMagnitudeAndSide() {
        val cards = listOf(
            SignalCopy.card(HomeFixtures.sampleAlerts()[0]),
            SignalCopy.card(HomeFixtures.sampleAlerts()[1]),
            SignalCopy.card(
                ticker = "KXBTC15M-UNROUNDED",
                side = "YES",
                modelYes = 55.6,
                marketYes = 50.0,
                fairYes = 55.6
            )
        )
        val dist = TelemetryChartMath.signalCardDistribution(cards)
        assertEquals(4, dist.size)
        assertEquals(3, dist.sumOf { it.total })
        val band3To6 = dist.first { it.label == "3–6 pts" }
        assertEquals("5.6 pts edge must stay in 3-6 band before rounding", 2, band3To6.upCount)

        val lines = listOf(
            HistoryAssembler.SignalLine(
                id = "1",
                createdAtMs = 1L,
                ticker = "KXBTC15M-1",
                side = "YES",
                marketPp = 50.0,
                fairPp = 62.0,
                edgePp = 12.0,
                settled = "yes",
                note = "QUIET/MID · AI 55.6% vs mkt 50.0% · Δ +12.0pp"
            ),
            HistoryAssembler.SignalLine(
                id = "2",
                createdAtMs = 2L,
                ticker = "KXBTC15M-2",
                side = "NO",
                marketPp = 60.0,
                fairPp = 56.0,
                edgePp = -4.0,
                settled = "no"
            )
        )
        val histDist = TelemetryChartMath.historySignalDistribution(lines)
        assertEquals(4, histDist.size)
        assertEquals(2, histDist.sumOf { it.total })
        val hist3To6 = histDist.first { it.label == "3–6 pts" }
        assertEquals("history signal uses AI vs mkt edge from note (5.6 pts), not stored 12.0pp", 1, hist3To6.upCount)
        assertEquals(1, hist3To6.downCount)
    }
}
