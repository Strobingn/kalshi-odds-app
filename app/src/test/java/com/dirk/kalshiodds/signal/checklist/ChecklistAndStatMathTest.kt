package com.dirk.kalshiodds.signal.checklist

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.AiDirection
import com.dirk.kalshiodds.decision.DecisionMath
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStats
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.decision.WindowCalibration
import com.dirk.kalshiodds.domain.EtTime
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.ml.ConformalSets
import com.dirk.kalshiodds.signal.ml.ExtendedAiRuntime
import com.dirk.kalshiodds.signal.ml.ReplaySample
import com.dirk.kalshiodds.signal.sizing.NetExpectedValue
import com.dirk.kalshiodds.signal.sizing.PositionSizer
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.ui.ScalpCopy
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.ChartShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Recomputes checklist rows and the stats the charts print, from fixture trades.
 */
class ChecklistAndStatMathTest {

    @Test
    fun blankRowsFillFromTheQuoteWhenModelsAreCold() {
        val rows = PreTradeChecklist.items(quote()).associate { it.label to it.value }
        assertTrue(rows.getValue("Uncertainty").contains("warming up"))
        assertTrue(rows.getValue("Survival P(YES)").endsWith("%"))
        assertTrue(rows.getValue("Conformal").contains("warming up"))
        assertTrue(rows.getValue("Conformal").contains("/${ConformalSets.MIN_SAMPLES}"))
        assertTrue(rows.getValue("P(edge survives)").endsWith("%"))
        assertTrue(rows.getValue("RL stake").contains("warming up"))
        assertTrue(rows.getValue("RL stake").contains("/8"))
        rows.values.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun likelyValueNetAndSizeNameTheirOwnNumbers() {
        val fair = 0.86
        val bid = 0.944
        val ask = 0.945
        val mid = (bid + ask) / 2.0
        val spread = ask - bid
        val yesEv = NetExpectedValue.compute(fair, mid, spread, preferSide = "YES")
        val noEv = NetExpectedValue.compute(fair, mid, spread, preferSide = "NO")
        assertTrue("buying the expensive UP side is negative", yesEv.netEv < 0.0)
        assertTrue("DOWN is the underpriced side", noEv.netEv > 0.0)
        val yesSize = PositionSizer.suggest(
            fairSide = fair,
            contractPrice = yesEv.contractPrice,
            bankrollUsd = 1000.0,
            netEvPositive = yesEv.netEv > 0.0
        )
        assertEquals(0, yesSize.contracts)

        val market = quote().copy(
            yesBid = bid,
            yesAsk = ask,
            noBid = 1.0 - ask,
            noAsk = 1.0 - bid,
            yesProbabilityPercent = mid * 100.0,
            noProbabilityPercent = (1.0 - mid) * 100.0,
            aiYesPercent = fair * 100.0,
            aiConfidence = 0.90,
            predictedSide = "YES",
            netEdgePp = yesEv.netEdgePp,
            netEvDollars = yesEv.netEv,
            suggestedContracts = yesSize.contracts,
            spreadDollars = spread
        )
        val rows = PreTradeChecklist.items(market).associate { it.label to it.value }
        assertEquals("UP 86% (model)", rows.getValue("Likely side"))
        assertTrue(rows.getValue("Value side").startsWith("DOWN underpriced"))
        assertTrue(rows.getValue("Value side").contains("pp vs ask"))
        assertTrue(rows.getValue("Net EV").startsWith("-") && rows.getValue("Net EV").contains("on UP"))
        assertEquals("0 contracts (UP net ≤ 0)", rows.getValue("Size"))
        assertEquals("90% (signal)", rows.getValue("Confidence"))
        val gap = PreTradeChecklist.valueGapPp(market)
        assertNotNull(gap)
        assertTrue(gap!! > 0.0)
    }

    @Test
    fun bannerPercentIsGbmNotSignalConfidence() {
        val up = AiDirection.Forecast(AiDirection.Call.UP, 0.92, 394, "KXBTC15M")
        assertEquals("92% GBM P(UP)", AiDirection.confidenceLabel(up))
        val down = up.copy(call = AiDirection.Call.DOWN, confidence = 0.61)
        assertEquals("61% GBM P(DOWN)", AiDirection.confidenceLabel(down))
    }

    @Test
    fun takerFeeMatchesCeilOfTheKalshiFormula() {
        for (contracts in listOf(1, 2, 5, 10, 25, 100)) {
            for (cents in 1..99) {
                val price = cents / 100.0
                val expected = ChecklistAdvisors.kalshiTakerFee(contracts, price)
                assertEquals("KalshiFee $contracts @ $cents¢", expected, KalshiFee.total(contracts, price), 1e-9)
                assertEquals("ScalpRule $contracts @ $cents¢", expected, ScalpRule.orderFee(contracts, price), 1e-9)
            }
        }
    }

    @Test
    fun closedTradesRecomputePnlWinRateCentsAndEquity() {
        val trades = listOf(
            closed("a", contracts = 10, entry = 0.40, exit = 0.55, at = ET_HOUR_MS),
            closed("b", contracts = 4, entry = 0.60, exit = 0.45, at = ET_HOUR_MS + 60_000),
            closed("c", contracts = 8, entry = 0.30, exit = 0.48, at = ET_HOUR_MS + 120_000)
        )
        val summary = ScalpStats.summary(trades)
        val nets = trades.map { it.netUsd!! }
        assertEquals(nets.sum(), summary.netUsd, 1e-9)
        assertEquals(2.0 / 3.0, summary.winRate!!, 1e-12)
        val cents = trades.map { it.netUsd!! / it.contracts * 100.0 }.average()
        assertEquals(cents, summary.avgNetPerContractCents!!, 1e-9)
        val perBet = nets.sum() / trades.size
        assertEquals(perBet, summary.netUsd / summary.roundTrips, 1e-9)

        val equity = ChartShapes.rollup(trades).equity
        assertEquals(nets.sum(), equity.last().value, 1e-9)
        assertEquals(3, equity.size)

        val fees = ChartGallery.feeLegFromTrades("Book", trades)!!
        val feeSum = trades.sumOf { it.entryFeeUsd + it.exitFeeUsd }
        assertEquals(feeSum, fees.fees, 1e-9)
        assertEquals(nets.sum(), fees.net, 1e-9)
        assertEquals(nets.sum() + feeSum, fees.gross, 1e-9)
    }

    @Test
    fun partialExitPrintsTheSoldAverageNotTheEntrySize() {
        val sold = 6
        val exit = 0.55
        val trade = closed("partial", contracts = 10, entry = 0.40, exit = exit, at = ET_HOUR_MS).copy(
            soldContracts = sold,
            proceedsUsd = sold * exit,
            exitFeeUsd = ChecklistAdvisors.kalshiTakerFee(sold, exit)
        )
        val line = ScalpCopy.historyLines(listOf(trade)).single().text
        assertTrue(line, line.contains("55.0¢"))
        assertFalse(line.contains("33.0¢"))
    }

    @Test
    fun chartsKeepBothSidesAndEasternHours() {
        assertEquals("1:28 PM ET", EtTime.clock(ET_HOUR_MS))
        assertTrue(EtTime.clock(ET_HOUR_MS).contains("PM"))
        assertEquals("12-16", ScorecardMetrics.etBucketKey(ET_HOUR_MS))

        val win = closed("up", contracts = 5, entry = 0.40, exit = 0.60, at = ET_HOUR_MS)
        val loss = win.copy(id = "dn", side = "NO", netUsd = -0.20)
        val pnl = ChartGallery.windowSidePnl(
            listOf(win, loss),
            ticker = win.ticker,
            startMs = ET_HOUR_MS - 60_000,
            closeMs = ET_HOUR_MS + 800_000,
            nowMs = ET_HOUR_MS + 10_000,
            upBid = 0.60,
            downBid = 0.40
        )
        assertEquals(listOf("UP", "DOWN"), pnl.map { it.name })

        val depth = ChartGallery.cumulativeDepth(listOf(0.94 to 12.0), listOf(0.06 to 9.0))
        assertTrue(depth.any { it.name == "UP bid" && it.up })
        assertTrue(depth.any { it.name == "DOWN bid" && !it.up })
        val upBid = depth.first { it.name == "UP bid" }
        assertEquals(12.0, upBid.size.last(), 1e-9)

        val distance = ChartGallery.distanceToStrike(
            listOf(BidPoint(ET_HOUR_MS, 94.4f, 5.5f, spotUsd = 84_459.0)),
            84_359.0
        )
        assertEquals(100.0, distance.single().value, 1e-6)

        val heat = ChartGallery.winRateByHourCoin(listOf(win))!!
        val cell = heat.cells.first { it.first().row == "BTC" }.first { it.column == ChartShapes.hourLabel(13) }
        assertEquals(1, cell.n)
        assertEquals(1.0, cell.rate!!, 1e-12)
    }

    @Test
    fun brierAndKellyMatchTheDefinitions() {
        val pairs = listOf(0.80 to true, 0.20 to false)
        val brier = DecisionMath.brier(pairs)!!
        assertEquals(0.04, brier, 1e-12)
        val report = WindowCalibration.fromPairs(pairs)
        assertEquals(brier, report.brier!!, 1e-12)
        val buckets = ChartGallery.calibration(report.pairs())
        assertTrue(buckets.any { it.n == 1 && it.predicted in 79.0..81.0 })
        assertTrue(buckets.any { it.n == 1 && it.predicted in 19.0..21.0 })

        val full = PositionSizer.fullKelly(0.60, 0.40)
        assertEquals((0.60 - 0.40) / (1.0 - 0.40), full, 1e-12)
        val sized = PositionSizer.suggest(
            fairSide = 0.60,
            contractPrice = 0.40,
            bankrollUsd = 1_000.0,
            kellyFraction = 0.25,
            maxFraction = 0.05,
            netEvPositive = true
        )
        assertEquals(125, sized.contracts)
    }

    @Test
    fun conformalAndRlFitFromStoredSettlements() {
        val runtime = ExtendedAiRuntime()
        val samples = (1..12).map { i ->
            ReplaySample(
                ticker = "KXBTC15M-$i",
                series = "KXBTC15M",
                tte = "EARLY",
                pYes = 0.70,
                outcomeYes = i % 3 != 0,
                settledAtMs = i.toLong(),
                edgePp = 4.0
            )
        }
        runtime.learnFromSettlements(samples, enabled = true)
        assertTrue(runtime.conformal.ready)
        assertTrue(runtime.rl.sampleCount >= 8)
        val label = ChecklistAdvisors.conformalLabel(
            com.dirk.kalshiodds.signal.ml.ConformalSets.predict(0.70, runtime.conformal),
            runtime.conformal.scores.size
        )
        assertFalse(label.contains("warming up"))
        val cold = runtime.rl.suggest(4.0, 0.7, 0.05, 0.5, 10.0)
        assertTrue(cold.ready)
        assertTrue(cold.note.contains("advisory"))
    }

    private fun quote() = MarketUiModel(
        ticker = "KXBTC15M-X",
        title = "BTC",
        subtitle = null,
        floorStrike = 84_359.0,
        yesBid = 0.40,
        yesAsk = 0.44,
        noBid = 0.56,
        noAsk = 0.60,
        lastPrice = 0.42,
        yesProbabilityPercent = 42.0,
        noProbabilityPercent = 58.0,
        volume = 1000.0,
        volume24h = 1000.0,
        openInterest = 200.0,
        liquidityDollars = 50.0,
        closeTimeLocal = null,
        closeTimeEpochMs = null,
        status = "open",
        seriesLabel = "Bitcoin"
    )

    private fun closed(
        id: String,
        contracts: Int,
        entry: Double,
        exit: Double,
        at: Long
    ): ScalpTrade {
        val entryFee = ChecklistAdvisors.kalshiTakerFee(contracts, entry)
        val exitFee = ChecklistAdvisors.kalshiTakerFee(contracts, exit)
        val proceeds = contracts * exit
        val net = proceeds - exitFee - (contracts * entry + entryFee)
        return ScalpTrade(
            id = id,
            ticker = "KXBTC15M-26OCT211330-45",
            side = "YES",
            state = ScalpState.CLOSED,
            signalAtMs = at - 30_000,
            signalAsk = entry,
            fairAtSignal = 0.55,
            contracts = contracts,
            entryPrice = entry,
            entryFeeUsd = entryFee,
            entryAtMs = at,
            soldContracts = contracts,
            proceedsUsd = proceeds,
            exitFeeUsd = exitFee,
            closedAtMs = at + 20_000,
            netUsd = net
        )
    }

    private companion object {
        val ET_HOUR_MS: Long = ZonedDateTime.of(2026, 10, 21, 17, 28, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
}
