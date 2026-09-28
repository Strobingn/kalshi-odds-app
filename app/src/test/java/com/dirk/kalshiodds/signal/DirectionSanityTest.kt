package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.withSignalScore
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.DirectionSanity
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.external.AssetSpotFeatures
import com.dirk.kalshiodds.signal.external.ExternalSnapshot
import com.dirk.kalshiodds.signal.ml.EnsembleStack
import com.dirk.kalshiodds.signal.ml.ExtendedAiRuntime
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DirectionSanityTest {

    @Before
    fun setUp() {
        HeavyMlGuard.reset()
    }

    @After
    fun tearDown() {
        HeavyMlGuard.reset()
    }

    @Test
    fun aboveTargetAndRisingOverridesFadeToNo() {
        val out = DirectionSanity.apply(
            spotUsd = 100_241.0,
            strikeUsd = 100_000.0,
            spotReturn = 0.004,
            fairPp = 40.0,
            predictedSide = "NO"
        )
        assertTrue(out.applied)
        assertEquals("YES", out.side)
        assertTrue(out.fairPp > 50.0)
        assertEquals(241.0, out.spotVsTargetUsd!!, 1e-6)
        assertTrue(out.note!!.contains("UP/YES"))
        assertTrue(out.note!!.contains("above"))
    }

    @Test
    fun belowTargetAndFallingOverridesFadeToYes() {
        val out = DirectionSanity.apply(
            spotUsd = 99_759.0,
            strikeUsd = 100_000.0,
            spotReturn = -0.003,
            fairPp = 62.0,
            predictedSide = "YES"
        )
        assertTrue(out.applied)
        assertEquals("NO", out.side)
        assertTrue(out.fairPp < 50.0)
        assertTrue(out.note!!.contains("DOWN/NO"))
        assertTrue(out.note!!.contains("below"))
    }

    @Test
    fun edgeThresholdCannotFlipObviousDirection() {
        val out = DirectionSanity.apply(
            spotUsd = 110_241.0,
            strikeUsd = 110_000.0,
            spotReturn = 0.002,
            fairPp = 35.0,
            predictedSide = "NO"
        )
        assertEquals("YES", out.side)
        assertTrue(out.fairPp > 50.0)
        // Displayed UP% is fair; DOWN% is 100-fair. Both agree with YES.
        assertTrue(out.fairPp > 100.0 - out.fairPp)
    }

    @Test
    fun twoDigitTickerSuffixIsNotAStrike() {
        assertNull(DirectionSanity.parseStrike("KXBTC15M-26SEP231600-00"))
        assertNull(DirectionSanity.parseStrike("KXETH15M-26SEP231645-45"))
        assertEquals(109250.0, DirectionSanity.parseStrike("KXBTC15M-26SEP241530-109250")!!, 1e-6)
        assertEquals(
            109_123.45,
            DirectionSanity.parseStrike(
                "KXBTC15M-X",
                title = "Bitcoin price up?",
                subtitle = "Above $109,123.45"
            )!!,
            1e-6
        )
    }

    @Test
    fun yesMeansUpUnlessQuestionIsBelow() {
        assertTrue(DirectionSanity.yesMeansUp("Bitcoin price up?", null))
        assertTrue(DirectionSanity.yesMeansUp("BTC above 109000?", "Above target"))
        assertFalse(DirectionSanity.yesMeansUp("Bitcoin below 109000?", "Below the target"))
    }

    @Test
    fun scoringEngineAboveTargetRisingLeansUpYes() {
        val engine = ScoringEngine(idFactory = { "dir-up" })
        engine.rememberMeta("KXBTC15M-DIR", 1_800_000L, 20_000.0, 2_000.0, floorStrike = 100_000.0)
        engine.external = ExternalSnapshot(
            btc = AssetSpotFeatures(
                asset = "BTC",
                lastPrice = 100_241.0,
                spotReturn1m = 0.004,
                source = "coinbase",
                modelUsable = true,
                fetchedAtMs = 1L
            )
        )
        // Rich YES mid — old fade (fair − mid) would lean NO if the model is shy.
        val score = engine.score(dirTick(yesBid = 0.88, yesAsk = 0.92), dirSettings(), nowMs = 10_000L)
        assertNotNull(score)
        assertEquals("YES", score!!.predictedSide)
        assertTrue(score.fairValuePp > 50.0)
        assertTrue(score.directionalLock)
        assertTrue(score.spotVsTargetUsd!! > 200.0)
        assertTrue(score.reason.contains("UP/YES") || score.spotLabel!!.contains("UP/YES"))
        val ui = sampleMarket().withSignalScore(score, thresholdPp = 5.0)
        assertEquals("YES", ui.predictedSide)
        assertEquals("Lean UP / YES", ui.stance)
        assertTrue((ui.aiYesPercent ?: 0.0) > (ui.aiNoPercent ?: 100.0))
    }

    @Test
    fun tapeConflictDoesNotUndoSpotStrikeLock() {
        val engine = ScoringEngine(idFactory = { "tape" })
        engine.rememberMeta("KXBTC15M-DIR", 1_800_000L, 20_000.0, 2_000.0, floorStrike = 100_000.0)
        engine.external = ExternalSnapshot(
            btc = AssetSpotFeatures(
                asset = "BTC",
                lastPrice = 100_241.0,
                spotReturn1m = -0.003,
                spotReturn5m = -0.004,
                source = "coinbase",
                modelUsable = true,
                fetchedAtMs = 1L
            )
        )
        val score = engine.score(dirTick(yesBid = 0.88, yesAsk = 0.92), dirSettings(), nowMs = 10_000L)
        assertNotNull(score)
        assertTrue(score!!.directionalLock)
        assertEquals("YES", score.predictedSide)
        // Primary follows spot vs strike + market (64¢+ UP), not a falling sparkline.
        assertEquals("YES", score.primaryHeroSide)
        assertFalse(score.tapeConflict)
        assertEquals(null, score.tapeConflictNote)
        val ui = sampleMarket().withSignalScore(score, thresholdPp = 5.0)
        assertEquals("Lean UP / YES", ui.stance)
        assertEquals("YES", ui.primaryHeroSide)
        assertTrue((ui.aiYesPercent ?: 0.0) > 50.0)
    }

    @Test
    fun scoringEngineBelowTargetFallingLeansDownNo() {
        val engine = ScoringEngine(idFactory = { "dir-dn" })
        engine.rememberMeta("KXBTC15M-DIR", 1_800_000L, 20_000.0, 2_000.0, floorStrike = 100_000.0)
        engine.external = ExternalSnapshot(
            btc = AssetSpotFeatures(
                asset = "BTC",
                lastPrice = 99_759.0,
                spotReturn1m = -0.004,
                source = "coinbase",
                modelUsable = true,
                fetchedAtMs = 1L
            )
        )
        val score = engine.score(dirTick(yesBid = 0.08, yesAsk = 0.12), dirSettings(), nowMs = 10_000L)
        assertNotNull(score)
        assertEquals("NO", score!!.predictedSide)
        assertTrue(score.fairValuePp < 50.0)
        assertTrue(score.directionalLock)
        val ui = sampleMarket().withSignalScore(score, thresholdPp = 5.0)
        assertEquals("NO", ui.predictedSide)
        assertEquals("Lean DOWN / NO", ui.stance)
        assertTrue((ui.aiNoPercent ?: 0.0) > (ui.aiYesPercent ?: 100.0))
    }

    @Test
    fun lightModeExtendedDoesNotCmeWhileBookMutates() {
        val engine = ScoringEngine(idFactory = { "cme" })
        val ticker = "KXBTC15M-CME"
        engine.applySnapshot(
            ticker,
            yesLevels = listOf(0.50 to 20.0, 0.49 to 10.0),
            noLevels = listOf(0.48 to 15.0),
            seq = 1
        )
        val settings = dirSettings().copy(extendedAiEnabled = true, heavyMlEnabled = false)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val writers = (0 until 3).map { i ->
            Thread {
                try {
                    repeat(60) { n ->
                        engine.applyDelta(
                            ticker,
                            price = 0.50,
                            delta = if (n % 2 == 0) 2.0 else -2.0,
                            side = "yes",
                            seq = 2 + n + i * 60
                        )
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        val scorers = (0 until 3).map {
            Thread {
                try {
                    repeat(40) { n ->
                        engine.score(
                            dirTick(ticker = ticker, yesBid = 0.48, yesAsk = 0.52),
                            settings,
                            nowMs = 20_000L + n
                        )
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        (writers + scorers).forEach { it.start() }
        (writers + scorers).forEach { it.join() }
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
        val reason = HeavyMlGuard.lastReason
        assertTrue(
            "guard should not latch CME: $reason",
            reason == null || !reason.contains("ConcurrentModification")
        )
    }

    @Test
    fun extendedAiEvaluateIsSafeUnderConcurrentCalls() {
        val rt = ExtendedAiRuntime()
        val settings = SignalSettings(extendedAiEnabled = true)
        val stack = EnsembleStack.identity()
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (0 until 6).map { i ->
            Thread {
                try {
                    repeat(40) { n ->
                        rt.evaluate(
                            ExtendedAiRuntime.Input(
                                ticker = "KXBTC15M-T$i",
                                series = "KXBTC15M",
                                mid = 0.45 + n * 0.001,
                                fairYes = 0.58,
                                edgePp = 8.0,
                                confidence = 0.62,
                                uncertainty = 0.06,
                                tteFrac = 0.6,
                                tte = com.dirk.kalshiodds.signal.engine.TteRegime.EARLY,
                                tteSeconds = 400L,
                                vol = 0.03,
                                momentum = 0.04,
                                velocityPerSec = 0.004,
                                imbalance = 0.2,
                                aggressor = 0.3,
                                sizeNorm = 0.4,
                                midFollow = 0.3,
                                spread = 0.02,
                                depthNear = 40.0,
                                depthFar = 60.0,
                                depthQuality = 0.5,
                                cancelSpike = 0.0,
                                quotePull = 0.0,
                                bestBid = 0.44 + n * 0.001,
                                bestAsk = 0.46 + n * 0.001,
                                spot = 0.002,
                                micro = com.dirk.kalshiodds.signal.engine.RegimeTag.TREND,
                                nowMs = 1_000L + n * 50L,
                                nTicks = 12,
                                configuredStake = 5.0
                            ),
                            settings,
                            stack
                        )
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
        val reason = HeavyMlGuard.lastReason
        assertTrue(
            "extended evaluate raced: $reason",
            reason == null || !reason.contains("ConcurrentModification")
        )
    }

    private fun dirSettings() = SignalSettings(
        watchBtc = true,
        watchEth = true,
        watchSol = true,
        edgeThresholdPp = 5.0,
        debounceMs = 10_000L,
        minConfidence = 0.0,
        maxSpreadCents = 50.0,
        hideWeakOpportunities = false,
        heavyMlEnabled = false,
        extendedAiEnabled = false
    )

    private fun dirTick(
        ticker: String = "KXBTC15M-DIR",
        yesBid: Double,
        yesAsk: Double
    ) = MarketTick(
        ticker = ticker,
        series = "KXBTC15M",
        yesBid = yesBid,
        yesAsk = yesAsk,
        lastPrice = (yesBid + yesAsk) / 2.0,
        volume = 20_000.0,
        openInterest = 2_000.0,
        closeTimeEpochMs = 1_800_000L,
        source = TickSource.REST,
        receiveElapsedNanos = 1L,
        floorStrike = 100_000.0
    )

    private fun sampleMarket() = MarketUiModel(
        ticker = "KXBTC15M-DIR",
        title = "Bitcoin price up?",
        subtitle = "Above 100000",
        floorStrike = 100_000.0,
        yesBid = 0.88,
        yesAsk = 0.92,
        noBid = 0.08,
        noAsk = 0.12,
        lastPrice = 0.90,
        yesProbabilityPercent = 90.0,
        noProbabilityPercent = 10.0,
        volume = 20_000.0,
        volume24h = 20_000.0,
        openInterest = 2_000.0,
        liquidityDollars = 5_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = 1_800_000L,
        status = "open",
        seriesLabel = "Bitcoin"
    )
}
