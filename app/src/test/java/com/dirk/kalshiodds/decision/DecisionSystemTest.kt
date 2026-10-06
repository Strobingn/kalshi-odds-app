package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionSystemTest {

    @Test
    fun zDistanceMatchesTheFormula() {
        val z = DecisionMath.zDistance(spot = Math.E, target = 1.0, realizedVol = 2.0, timeRemaining = 4.0)
        assertEquals(1.0 / 4.0, z!!, 1e-9)
        assertNull(DecisionMath.zDistance(100.0, 100.0, 0.0, 10.0))
        val tail = 0.001
        assertEquals(tail, DecisionMath.sigmoid(DecisionMath.logit(tail)), 1e-9)
    }

    @Test
    fun pavKeepsTailsAndIsMonotone() {
        val knots = PavIsotonic.fit(listOf(0.01 to 0.0, 0.2 to 0.0, 0.4 to 1.0, 0.9 to 1.0))
        assertEquals(0.0, knots.first().y, 1e-9)
        assertTrue(PavIsotonic.apply(0.001, knots) < 0.02)
        val ys = knots.map { it.y }
        assertEquals(ys, ys.sorted())
    }

    @Test
    fun hierarchicalFallbackNeedsTwoHundred() {
        val fine = RegimeCalibration.Key(
            time = RegimeCalibration.TimeLeft.M3_PLUS,
            distance = RegimeCalibration.Distance.NEAR,
            liquidity = RegimeCalibration.Liquidity.LIQUID,
            vol = RegimeCalibration.Vol.CALM
        )
        val broad = fine.copy(vol = null, liquidity = null, distance = null)
        val samples = (0 until 200).map {
            RegimeCalibration.Sample(0.7, 0.6, it % 3 != 0, broad)
        } + (0 until 10).map {
            RegimeCalibration.Sample(0.2, 0.2, false, fine)
        }
        val model = RegimeCalibration.fit(samples)
        assertNull(model.apply(0.4, RegimeCalibration.Key(time = RegimeCalibration.TimeLeft.S0_30)))
        assertNotNull(model.apply(0.4, broad))
        val finalKey = RegimeCalibration.Key(time = RegimeCalibration.TimeLeft.S0_30)
        assertFalse(model.finalWindowReady(finalKey))
    }

    @Test
    fun eachGateFailureIsNoBet() {
        val ok = passing()
        assertTrue(TradeEligibility.evaluate(ok).allow)
        assertEquals(
            "NO BET — calibrated probability unavailable",
            TradeEligibility.evaluate(ok.copy(calibratedProbability = null)).reason
        )
        assertEquals(
            "NO BET — settlement source is stale",
            TradeEligibility.evaluate(ok.copy(settlementSourceFresh = false)).reason
        )
        assertTrue(TradeEligibility.evaluate(ok.copy(bookFresh = false)).reason!!.contains("order book"))
        assertTrue(TradeEligibility.evaluate(ok.copy(pFill = 0.1)).reason!!.contains("fill probability"))
        assertEquals(
            "NO BET — model uncertainty too high for this final-window regime",
            TradeEligibility.evaluate(
                ok.copy(uncertainty = UncertaintyInterval(0.2, 0.8, true), secondsRemaining = 20.0)
            ).reason
        )
        assertTrue(TradeEligibility.evaluate(ok.copy(regimeApproved = false)).reason!!.contains("regime"))
        assertTrue(TradeEligibility.evaluate(ok.copy(expectedNet = -0.01)).reason!!.contains("expected net"))
        assertEquals(
            "NO BET — tiny quote needs verified depth and a validated residual",
            TradeEligibility.evaluate(ok.copy(ask = 0.001, verifiedDepth = false)).reason
        )
        assertEquals(
            "NO BET — longshot not validated",
            TradeEligibility.evaluate(ok.copy(ask = 0.10, longshotResidualPositive = false)).reason
        )
        assertEquals(
            "NO BET — balance unavailable",
            TradeEligibility.evaluate(ok.copy(balanceRequired = true, balanceAvailable = false)).reason
        )
        assertEquals(
            AutopilotMinStake.REASON,
            TradeEligibility.evaluate(ok.copy(enforceMinStake = true, stakeUsd = 4.99)).reason
        )
        assertTrue(
            TradeEligibility.evaluate(
                ok.copy(secondsRemaining = 30.0, finalWindowReady = false)
            ).reason!!.contains("final 60")
        )
    }

    @Test
    fun expectedNetUsesFillProbability() {
        val net = ExpectedNet.of(
            pFill = 0.5,
            pWin = 0.6,
            filledSize = 10.0,
            price = 0.40,
            feePerContract = 0.02,
            adversePerContract = 0.01
        )
        assertEquals(0.5 * 10.0 * (0.6 - 0.40 - 0.02 - 0.01), net, 1e-9)
    }

    @Test
    fun marketPriorIsZeroUnlessValidated() {
        val mid = 0.2
        assertEquals(mid, MarketPrior.probability(mid), 1e-9)
        val spec = MarketPrior.Spec(
            validated = true,
            featureCoefficients = doubleArrayOf(0.2),
            bucketBias = mapOf("KXBTC15M|5-25c|M3_PLUS" to 0.1)
        )
        val p = MarketPrior.probability(mid, spec, doubleArrayOf(1.0), "KXBTC15M|5-25c|M3_PLUS")
        assertTrue(p > mid)
        val capped = MarketPrior.probability(
            mid,
            spec.copy(cap = 0.5),
            doubleArrayOf(100.0),
            "KXBTC15M|5-25c|M3_PLUS"
        )
        val maxLogit = DecisionMath.logit(mid) + 0.5
        assertEquals(DecisionMath.sigmoid(maxLogit), capped, 1e-6)
    }

    @Test
    fun queueReplayIsConservative() {
        val prints = listOf(
            QueueReplay.Print(0.40, 5.0, 1_000L),
            QueueReplay.Print(0.39, 10.0, 2_000L)
        )
        val conservative = QueueReplay.judge(0.40, 4.0, 10.0, 0.45, 0L, prints, 0.42, QueueReplay.Mode.CONSERVATIVE)
        assertFalse(conservative.filled)
        val optimistic = QueueReplay.judge(0.40, 4.0, 10.0, 0.45, 0L, prints, 0.42, QueueReplay.Mode.OPTIMISTIC)
        assertTrue(optimistic.filled)
        val cleared = QueueReplay.judge(
            0.40, 4.0, 10.0, 0.45, 0L,
            prints + QueueReplay.Print(0.40, 20.0, 3_000L),
            0.30,
            QueueReplay.Mode.CONSERVATIVE
        )
        assertTrue(cleared.filled)
        assertEquals(0.15, cleared.adverseMove!!, 1e-9)
    }

    @Test
    fun honestScorecardDropsSubTenCentFromHeadline() {
        val view = HonestScorecard.of(
            listOf(
                HonestScorecard.Row(0.001, 9330.0, 10.0),
                HonestScorecard.Row(0.40, 2.0, 10.0),
                HonestScorecard.Row(0.55, -1.0, 5.0)
            )
        )
        assertEquals(1.0, view.scoredPnlUsd, 1e-9)
        assertEquals(9330.0, view.longshotUnscoredUsd, 1e-9)
        assertEquals(-1.0, view.excludingBiggestWinUsd, 1e-9)
        assertEquals(5, view.byPrice.size)
        assertTrue(HonestScorecard.lines(view).any { it.contains("Longshot, unscored") })
    }

    @Test
    fun liveBalanceAndMinStakeAndDailyCap() {
        assertFalse(LiveBalancePolicy.fresh(null, 1L, 2L))
        assertFalse(LiveBalancePolicy.fresh(10.0, 0L, LiveBalancePolicy.FRESH_MS + 1))
        assertTrue(LiveBalancePolicy.fresh(10.0, 1_000L, 1_000L + 1_000L))
        assertTrue(AutopilotMinStake.below(4.99))
        assertFalse(AutopilotMinStake.below(5.0))
        assertFalse(DailyCapPolicy.BLOCKS_ORDERS)
        assertFalse(DailyCapPolicy.SHOWN_ON_SCREEN)
        assertFalse(CoinRaceStudy.ENABLED)
    }

    @Test
    fun ladderPromotesOnlyWhenTheIntervalClearsZero() {
        val thin = StrategyLadder.Stats(10, 10, 0.2, 0.01)
        assertEquals(StrategyLadder.Stage.PAPER, StrategyLadder.v060(thin).stage)
        val ready = StrategyLadder.Stats(100, 100, 0.05, 0.01)
        assertEquals(StrategyLadder.Stage.LIMITED_LIVE_ELIGIBLE, StrategyLadder.v060(ready).stage)
        assertEquals(StrategyLadder.Stage.PAPER, StrategyLadder.fav15(ready).stage)
        val fav = StrategyLadder.Stats(300, 300, 0.02, 0.001)
        assertEquals(StrategyLadder.Stage.LIMITED_LIVE_ELIGIBLE, StrategyLadder.fav15(fav).stage)
        assertTrue(StrategyLadder.directional(false).reason.contains("filter"))
    }

    @Test
    fun cfMessageMatchesTheDocumentedSchema() {
        val raw = """
            {
              "type": "cfbenchmarks_value",
              "sending_ts_ms": 1669149841234,
              "sid": 1,
              "seq": 42,
              "msg": {
                "index_id": "BRTI",
                "received_at": 1710000000123,
                "data": "{\"type\":\"value\",\"id\":\"BRTI\",\"time\":1710000000123,\"value\":\"68000.12\"}",
                "avg_60s_data": {
                  "value": "68000.12000000",
                  "window_size": 3,
                  "window_start_ts_ms": 1709999940123,
                  "window_end_ts_exclusive": 1710000000123
                },
                "last_60s_windowed_average_15min": {
                  "value": "68000.23000000",
                  "window_size": 14,
                  "window_start_ts_ms": 1709999980000,
                  "window_end_ts_exclusive": 1710000000123
                }
              }
            }
        """.trimIndent()
        val tick = com.dirk.kalshiodds.signal.ws.CfBenchmarks.parse(raw, 1710000000500L)!!
        assertEquals("BRTI", tick.indexId)
        assertEquals(68000.12, tick.value, 1e-6)
        assertEquals(68000.23, tick.finalMinuteAverage!!.value, 1e-6)
        val sub = com.dirk.kalshiodds.signal.ws.CfBenchmarks.subscribeJson(3)
        assertTrue(sub.contains("\"cfbenchmarks_value\""))
        assertTrue(sub.contains("BRTI"))
        assertTrue(sub.contains("ETHUSD_RTI"))
        assertTrue(sub.contains("SOLUSD_RTI"))
        assertFalse(sub.contains("market_tickers"))
        val store = com.dirk.kalshiodds.signal.ws.CfBenchmarkStore()
        store.accept(tick)
        assertEquals(68000.23, store.settlementPrice("BRTI", 1710000000500L, 30.0)!!, 1e-6)
        assertNull(store.settlementPrice("BRTI", 1710000000500L + 60_000L, 30.0))
    }

    private fun passing() = TradeEligibility.Input(
        calibratedProbability = 0.72,
        settlementSourceFresh = true,
        bookFresh = true,
        bookNonEmpty = true,
        visibleDepth = 20.0,
        orderSize = 5.0,
        pFill = 0.8,
        uncertainty = UncertaintyInterval(0.60, 0.84, true),
        regimeApproved = true,
        expectedNet = 0.4,
        secondsRemaining = 180.0,
        finalWindowReady = true,
        ask = 0.40,
        verifiedDepth = true,
        validatedResidual = true,
        longshotResidualPositive = true,
        stakeUsd = 12.0
    )
}
