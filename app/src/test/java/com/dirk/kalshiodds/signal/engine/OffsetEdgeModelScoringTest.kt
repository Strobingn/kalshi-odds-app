package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.prediction.EdgeModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * docs/ml-review-2026-09-27.md #2: a market-anchored (offset) edge model's
 * output is the fair. The engine must not blend it with the mid again.
 */
class OffsetEdgeModelScoringTest {

    @Before
    fun setUp() {
        HeavyMlGuard.reset()
    }

    @Test
    fun zeroWeightOffsetModelFairIsTheMid() {
        val engine = engine(model(kind = "offset_logistic"))
        val score = engine.score(tick(yesBid = 0.40, yesAsk = 0.42), settings(), nowMs = 10_000L)
        assertNotNull(score)
        score!!
        assertEquals("anchored output is recorded", 41.0, score.importedModelPp!!, 1e-4)
        // No holdout win: the decision fair stays on the market, which this
        // zero-weight model also reproduces.
        assertEquals("unproven model does not leave the mid", 41.0, score.fairValuePp, 1e-4)
        assertEquals(0.0, score.deltaPp, 1e-4)
        assertEquals(1.0, score.blendWeight!!, 0.0)
        assertFalse("fair == mid is never an edge", score.modelEdgeQualified)
        // Net EV follows the anchored fair: paying half the spread + fee loses.
        assertTrue(score.netEdgePp!! < 0.0)
    }

    @Test
    fun offsetModelDoesNotMoveTheFairUntilItBeatsTheMarket() {
        val engine = engine(model(kind = "offset_logistic", bias = 0.4))
        val score = engine.score(tick(yesBid = 0.40, yesAsk = 0.42), settings(), nowMs = 10_000L)!!
        assertEquals(41.0, score.fairValuePp, 1e-3)
        assertEquals("NO", score.predictedSide)
    }

    @Test
    fun provenOffsetModelPullsAQuarterOfTheWay() {
        val engine = engine(model(kind = "offset_logistic", bias = 0.4, beats = true))
        val score = engine.score(tick(yesBid = 0.40, yesAsk = 0.42), settings(), nowMs = 10_000L)!!
        val modelPp = 100.0 / (1.0 + kotlin.math.exp(-(kotlin.math.ln(0.41 / 0.59) + 0.4)))
        val expected = 0.75 * 41.0 + 0.25 * modelPp
        assertEquals(expected, score.fairValuePp, 1e-2)
        // The side is the likely winner: a 25% pull from 41 lands near 43.5,
        // still under 50, so NO stays the pick.
        assertTrue(expected < 50.0)
        assertEquals("NO", score.predictedSide)
    }

    @Test
    fun legacyLogisticWithoutAHoldoutWinStaysOnTheMid() {
        val legacy = engine(model(kind = "logistic")).score(tick(yesBid = 0.40, yesAsk = 0.42), settings(), nowMs = 10_000L)!!
        assertEquals(50.0, legacy.importedModelPp!!, 1e-4)
        assertEquals(41.0, legacy.fairValuePp, 1e-3)
        assertEquals("NO", legacy.predictedSide)
    }

    private fun engine(edge: EdgeModel?): ScoringEngine {
        val engine = ScoringEngine(idFactory = { "offset-test" })
        engine.rememberMeta(TICKER, 1_800_000L, 20_000.0, 2_000.0, floorStrike = 100_000.0)
        engine.edgeModel = edge
        return engine
    }

    private fun model(kind: String, bias: Double = 0.0, beats: Boolean = false): EdgeModel = EdgeModel.parse(
        """
        {
          "version": 2,
          "kind": "$kind",
          "feature_names": [
            "dist_to_strike_vol","tte_frac","market_mid","imbalance","spread",
            "momentum","realized_vol","cross_asset","time_of_day","digital_fair","prev_window_return"
          ],
          "weights": [0,0,0,0,0,0,0,0,0,0,0],
          "bias": $bias,
          "mean": [0,0,0,0,0,0,0,0,0,0,0],
          "std":  [1,1,1,1,1,1,1,1,1,1,1],
          "platt_a": 1.0,
          "platt_b": 0.0,
          "blend_weight": ${if (kind == "logistic") 0.35 else 1.0},
          "fee_margin": 0.07,
          "confidence_margin": 0.03,
          "metrics": ${if (beats) """{"model_brier":0.10,"market_brier":0.14,"model_logloss":0.30,"market_logloss":0.40,"synthetic":0}""" else "{}"}
        }
        """.trimIndent()
    )

    private fun settings() = SignalSettings(
        edgeThresholdPp = 5.0,
        minConfidence = 0.0,
        maxSpreadCents = 50.0,
        hideWeakOpportunities = false,
        heavyMlEnabled = false,
        extendedAiEnabled = false
    )

    private fun tick(yesBid: Double, yesAsk: Double) = MarketTick(
        ticker = TICKER,
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

    private companion object {
        const val TICKER = "KXBTC15M-OFFSET"
    }
}
