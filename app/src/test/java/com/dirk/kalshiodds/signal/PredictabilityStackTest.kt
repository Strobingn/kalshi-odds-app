package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.prediction.SettlementScorer
import com.dirk.kalshiodds.signal.config.DefaultSignalConfig
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.engine.MarketRegime
import com.dirk.kalshiodds.signal.engine.RegimeTag
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.engine.SkipFilter
import com.dirk.kalshiodds.signal.engine.TickBook
import com.dirk.kalshiodds.signal.engine.TteRegime
import com.dirk.kalshiodds.signal.feedback.Calibrator
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class CalibratorTest {
    @Test
    fun fitEntriesIgnoresEthAndSol() {
        fun row(series: String, yes: Boolean) = PredictionLogEntry(
            ticker = "$series-T",
            series = series,
            predictedYes = 0.8,
            predictedNo = 0.2,
            marketMid = 0.5,
            timestampMs = 1L,
            closeTimeMs = 1L,
            outcome = if (yes) "yes" else "no"
        )
        val btc = List(20) { row("KXBTC15M", true) }
        val mixed = btc + List(30) { row("KXETH15M", false) } + List(30) { row("KXSOL15M", false) }
        val only = Calibrator.fitEntries(btc)
        val withAlts = Calibrator.fitEntries(mixed)
        assertEquals(only.sampleCount, withAlts.sampleCount)
        assertEquals(only.temperature, withAlts.temperature, 1e-9)
        assertEquals(0, Calibrator.fitEntries(List(40) { row("KXETH15M", false) }).sampleCount)
    }

    @Test
    fun coldStartIsIdentity() {
        val cold = Calibrator.fit(List(5) { Calibrator.Sample(0.7, true) })
        assertFalse(cold.ready)
        assertEquals(0.70, Calibrator.apply(0.70, cold), 1e-9)
        assertEquals(70.0, Calibrator.applyPp(70.0, cold), 1e-9)
    }

    @Test
    fun temperatureGreaterThanOnePullsTowardHalfWhenOverconfident() {
        val samples = (0 until 24).map { i ->
            // Model always says 0.90; outcomes are 50/50 → T should rise.
            Calibrator.Sample(0.90, i % 2 == 0)
        }
        val state = Calibrator.fit(samples, minSamples = 20)
        assertTrue(state.ready)
        assertTrue(state.temperature > 1.0)
        val cal = Calibrator.apply(0.90, state)
        assertTrue(cal < 0.90)
        assertTrue(cal > 0.40)
    }

    @Test
    fun reliabilityBinsCoverUnitInterval() {
        val samples = (0 until 30).map { i ->
            Calibrator.Sample(0.1 + (i % 9) * 0.1, i % 3 == 0)
        }
        val state = Calibrator.fit(samples)
        assertEquals(SignalConstants.RELIABILITY_BINS, state.bins.size)
        assertEquals(0.0, state.bins.first().lo, 1e-9)
        assertEquals(1.0, state.bins.last().hi, 1e-9)
    }
}

class MarketRegimeTest {
    @Test
    fun lateWindowIsLastThreeMinutes() {
        val now = 1_000_000L
        assertEquals(TteRegime.EARLY, MarketRegime.tteRegime(now + 200_000L, now))
        assertEquals(TteRegime.LATE, MarketRegime.tteRegime(now + 120_000L, now))
        assertEquals(TteRegime.EARLY, MarketRegime.tteRegime(null, now))
        assertEquals(180L, MarketRegime.tteSeconds(now + 180_000L, now))
    }

    @Test
    fun quietTrendChopAndVolSpike() {
        val quiet = listOf(0.50, 0.501, 0.500, 0.502, 0.501)
        assertEquals(RegimeTag.QUIET, MarketRegime.classify(quiet, velocityPerSec = 0.0))

        val trend = listOf(0.40, 0.43, 0.46, 0.49, 0.53)
        assertEquals(RegimeTag.TREND, MarketRegime.classify(trend, velocityPerSec = 0.01))

        val chop = listOf(0.50, 0.515, 0.495, 0.518, 0.492, 0.505)
        assertEquals(RegimeTag.CHOP, MarketRegime.classify(chop, velocityPerSec = 0.0))

        val spike = listOf(0.30, 0.55, 0.25, 0.70)
        assertEquals(RegimeTag.VOL_SPIKE, MarketRegime.classify(spike, velocityPerSec = 0.05))
    }
}

class SkipFilterTest {
    private val settings = SignalSettings(
        minConfidence = 0.45,
        minLiquidity = 500.0,
        maxSpreadCents = 8.0,
        hideWeakOpportunities = true,
        edgeThresholdPp = 5.0
    )

    @Test
    fun passesWhenAllClear() {
        val r = SkipFilter.evaluate(0.60, 0.04, 2_000.0, 800.0, 100.0, settings)
        assertTrue(r.passed)
        assertNull(r.reason)
    }

    @Test
    fun failsLowConfidenceWideSpreadThinBook() {
        val r = SkipFilter.evaluate(0.20, 0.15, 10.0, 10.0, 5.0, settings)
        assertFalse(r.passed)
        assertTrue(r.reason!!.contains("confidence"))
        assertTrue(r.reason!!.contains("spread"))
        assertTrue(r.reason!!.contains("liquidity"))
    }

    @Test
    fun hidesWeakWhenToggleOn() {
        assertFalse(SkipFilter.shouldShowOpportunity(false, 8.0, settings))
        assertFalse(SkipFilter.shouldShowOpportunity(true, 1.0, settings))
        assertTrue(SkipFilter.shouldShowOpportunity(true, 6.0, settings))
        assertTrue(
            SkipFilter.shouldShowOpportunity(false, 8.0, settings.copy(hideWeakOpportunities = false))
        )
    }
}

class ScorecardMetricsTest {
    @Test
    fun hitRateBrierAndEdgeSplit() {
        val now = 1_700_000_000_000L
        val rows = listOf(
            entry("KXBTC15M-A", "KXBTC15M", 0.70, "yes", 1, 0.09, 8.0, now),
            entry("KXETH15M-B", "KXETH15M", 0.30, "yes", 0, 0.49, -6.0, now),
            entry("KXSOL15M-C", "KXSOL15M", 0.80, "void", null, null, 4.0, now)
        )
        val snap = ScorecardMetrics.compute(rows, nowMs = now, zoneId = ZoneOffset.UTC)
        assertEquals(2, snap.sampleCount)
        assertEquals(1, snap.voidCount)
        assertEquals(0.5, snap.allTime.hitRate!!, 1e-9)
        // Binary outcomes: picked-side Brier equals P(YES) Brier.
        // YES@0.70 hit → 0.09; NO pick at P(YES)=0.30 vs yes → 0.49; mean 0.29.
        assertEquals(0.29, snap.allTime.brier!!, 1e-9)
        assertEquals(0.29, snap.allTime.pUpBrier!!, 1e-9)
        assertEquals(8.0, snap.allTime.avgEdgeWhenRight!!, 1e-9)
        assertEquals(-6.0, snap.allTime.avgEdgeWhenWrong!!, 1e-9)
        assertEquals(2, snap.perSeries.size)
    }

    private fun entry(
        ticker: String,
        series: String,
        pYes: Double,
        outcome: String,
        score: Int?,
        brier: Double?,
        edge: Double,
        ts: Long
    ) = PredictionLogEntry(
        ticker = ticker,
        series = series,
        predictedYes = pYes,
        predictedNo = 1.0 - pYes,
        marketMid = 0.50,
        timestampMs = ts,
        closeTimeMs = ts,
        outcome = outcome,
        score = score,
        brier = brier,
        predictedSide = if (pYes >= 0.5) "YES" else "NO",
        edgePp = edge,
        settledAtMs = ts
    )
}

class LeadLagAndMicrostructureTest {
    @Test
    fun btcLeadRaisesEthScore() {
        val now = 50_000L
        val btcHist = listOf(
            (now - 3_000L) to 0.40,
            (now - 2_000L) to 0.50,
            (now - 400L) to 0.52
        )
        val ethHist = listOf(
            (now - 3_000L) to 0.45,
            (now - 400L) to 0.45,
            now to 0.45
        )
        val btcMove = TickBook.deltaBetween(btcHist, now - 3_000L, now - 400L)
        assertNotNull(btcMove)
        assertTrue(btcMove!! > 0.10)
        val ethFollow = TickBook.deltaBetween(ethHist, now - 400L, now) ?: 0.0
        assertEquals(0.0, ethFollow, 1e-9)
        assertEquals(listOf("KXBTC15M"), TickBook.leadersFor("KXETH15M"))
        assertEquals(listOf("KXETH15M", "KXSOL15M"), TickBook.leadersFor("KXBTC15M"))
    }

    @Test
    fun liveLeadLagOnTickBook() {
        val book = TickBook()
        val t0 = 10_000L
        for (i in 0 until 8) {
            book.push(
                MarketTick(
                    ticker = "KXBTC15M-L",
                    series = "KXBTC15M",
                    yesBid = 0.40 + i * 0.02,
                    yesAsk = 0.42 + i * 0.02,
                    lastPrice = 0.41 + i * 0.02,
                    volume = 5_000.0,
                    openInterest = 1_000.0,
                    closeTimeEpochMs = t0 + 600_000,
                    source = TickSource.WS_TICKER,
                    receiveElapsedNanos = 1L
                ),
                nowMs = t0 + i * 400L
            )
            book.push(
                MarketTick(
                    ticker = "KXETH15M-L",
                    series = "KXETH15M",
                    yesBid = 0.50,
                    yesAsk = 0.52,
                    lastPrice = 0.51,
                    volume = 5_000.0,
                    openInterest = 1_000.0,
                    closeTimeEpochMs = t0 + 600_000,
                    source = TickSource.WS_TICKER,
                    receiveElapsedNanos = 1L
                ),
                nowMs = t0 + i * 400L
            )
        }
        val ll = book.leadLagScore("KXETH15M", t0 + 2_800L)
        assertNotNull(ll)
        assertTrue(ll!! > 0.0)
    }

    @Test
    fun depthNearMidAndDecay() {
        val book = LocalOrderBook()
        book.replaceSnapshot(
            yesLevels = listOf(0.49 to 80.0, 0.40 to 200.0),
            noLevels = listOf(0.50 to 20.0, 0.40 to 200.0),
            seq = 1
        )
        val near = book.depthNearMid(3.0)
        assertTrue(near > 90.0)
        val decay = book.depthDecay(3.0, 15.0)
        assertNotNull(decay)
        assertTrue(decay!! < 1.0)
        assertTrue(decay > 0.0)
    }

    @Test
    fun largeYesCancelMovesPulseNegative() {
        val book = LocalOrderBook()
        book.replaceSnapshot(listOf(0.48 to 100.0), listOf(0.50 to 40.0), seq = 1)
        assertTrue(book.applyDelta(0.48, -40.0, "yes", seq = 2))
        assertTrue(book.pulse().cancelSpike < 0.0)
    }

    @Test
    fun bidPullIsNegativeQuotePulse() {
        val book = LocalOrderBook()
        book.replaceSnapshot(listOf(0.50 to 10.0), listOf(0.48 to 10.0), seq = 1)
        book.replaceSnapshot(listOf(0.40 to 10.0), listOf(0.48 to 10.0), seq = 2)
        assertTrue(book.pulse().quotePull < 0.0)
    }
}

class ScoringPredictabilityTest {
    private val settings = SignalSettings(
        watchBtc = true,
        watchEth = true,
        watchSol = true,
        edgeThresholdPp = 5.0,
        debounceMs = 10_000L,
        minConfidence = 0.05,
        minLiquidity = 0.0,
        maxSpreadCents = 50.0,
        hideWeakOpportunities = false
    )

    @Test
    fun lateWeightsShiftAwayFromAiTowardMicro() {
        val engine = ScoringEngine()
        val early = engine.blendWeights(
            tte = TteRegime.EARLY,
            regime = RegimeTag.QUIET,
            hasAi = true,
            hasRelated = true,
            hasVel = true,
            hasImb = true,
            hasLeadLag = true,
            hasDepth = true,
            hasCancel = true
        )!!
        val late = engine.blendWeights(
            tte = TteRegime.LATE,
            regime = RegimeTag.QUIET,
            hasAi = true,
            hasRelated = true,
            hasVel = true,
            hasImb = true,
            hasLeadLag = true,
            hasDepth = true,
            hasCancel = true
        )!!
        // The shipped MLP weight is pinned at 0 in every regime so it cannot tilt fair value.
        assertEquals(0.0, early.ai, 1e-9)
        assertEquals(0.0, late.ai, 1e-9)
        assertTrue(late.velocity > early.velocity)
        assertTrue(late.imbalance > early.imbalance)
        assertTrue(late.leadLag < early.leadLag)
    }

    @Test
    fun skipFilterBlocksAlert() {
        val engine = ScoringEngine(idFactory = { "f" })
        val now = System.currentTimeMillis()
        val tick = MarketTick(
            ticker = "KXBTC15M-SKIP",
            series = "KXBTC15M",
            yesBid = 0.10,
            yesAsk = 0.40,
            lastPrice = 0.25,
            volume = 1.0,
            openInterest = 1.0,
            closeTimeEpochMs = now + 600_000,
            source = TickSource.REST,
            receiveElapsedNanos = 1L
        )
        val tight = settings.copy(
            minConfidence = 0.99,
            minLiquidity = 50_000.0,
            maxSpreadCents = 2.0,
            edgeThresholdPp = 0.01
        )
        val scored = engine.score(tick, tight, now)
        assertNotNull(scored)
        assertFalse(scored!!.passedFilter)
        assertNull(engine.maybeAlert(tick, tight, now))
        assertTrue(scored.reason.contains("EARLY") || scored.reason.contains("LATE"))
    }

    @Test
    fun scoreSurfacesRegimeAndTte() {
        val engine = ScoringEngine(idFactory = { "r" })
        val now = 2_000_000L
        val tick = MarketTick(
            ticker = "KXBTC15M-REG",
            series = "KXBTC15M",
            yesBid = 0.44,
            yesAsk = 0.46,
            lastPrice = 0.45,
            volume = 8_000.0,
            openInterest = 2_000.0,
            closeTimeEpochMs = now + 90_000,
            source = TickSource.REST,
            receiveElapsedNanos = 1L
        )
        val scored = engine.score(tick, settings, now)
        assertNotNull(scored)
        assertEquals(TteRegime.LATE, scored!!.tteRegime)
        assertNotNull(scored.regime)
        assertTrue(scored.predictedSide == "YES" || scored.predictedSide == "NO")
        assertNotNull(scored.netEdgePp)
        assertNotNull(scored.suggestedContracts)
        assertTrue(scored.suggestedContracts!! >= 0)
        assertNotNull(scored.feePerContract)
    }
}

class SettlementNormalizeTest {
    @Test
    fun mapsVoidAliases() {
        assertEquals("yes", SettlementScorer.normalizeResult("YES"))
        assertEquals("no", SettlementScorer.normalizeResult("no"))
        assertEquals("void", SettlementScorer.normalizeResult("void"))
        assertEquals("void", SettlementScorer.normalizeResult("cancelled"))
        assertNull(SettlementScorer.normalizeResult("open"))
    }
}

class DefaultConfigV20Test {
    @Test
    fun parsesSkipFilterDefaults() {
        val json = """
            {"minConfidence":0.5,"minLiquidity":750,"maxSpreadCents":6.5,"hideWeakOpportunities":false}
        """.trimIndent()
        val cfg = DefaultSignalConfig.parse(json)
        assertEquals(0.5, cfg.minConfidence, 1e-9)
        assertEquals(750.0, cfg.minLiquidity, 1e-9)
        assertEquals(6.5, cfg.maxSpreadCents, 1e-9)
        assertFalse(cfg.hideWeakOpportunities)
    }
}
