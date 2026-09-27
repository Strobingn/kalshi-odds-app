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
        assertEquals(41.0, score!!.importedModelPp!!, 1e-4)
        assertEquals("anchored output is the fair, not a 0.55/0.45 mix", 41.0, score.fairValuePp, 1e-4)
        assertEquals(0.0, score.deltaPp, 1e-4)
        assertEquals(1.0, score.blendWeight!!, 0.0)
        assertFalse("fair == mid is never an edge", score.modelEdgeQualified)
        // Net EV follows the anchored fair: paying half the spread + fee loses.
        assertTrue(score.netEdgePp!! < 0.0)
    }

    @Test
    fun offsetModelMovesTheFairOnlyByItsWeights() {
        // bias +0.4 in logit space on a 41¢ mid → sigmoid(logit(0.41) + 0.4) ≈ 50.9%.
        val engine = engine(model(kind = "offset_logistic", bias = 0.4))
        val score = engine.score(tick(yesBid = 0.40, yesAsk = 0.42), settings(), nowMs = 10_000L)!!
        val expected = 100.0 / (1.0 + kotlin.math.exp(-(kotlin.math.ln(0.41 / 0.59) + 0.4)))
        assertEquals(expected, score.fairValuePp, 1e-3)
        assertEquals("YES", score.predictedSide)
        assertTrue(score.netEdgePp!! > 0.0)
    }

    @Test
    fun legacyLogisticStillBlendsWithTheEngineFair() {
        val tickArgs = tick(yesBid = 0.40, yesAsk = 0.42)
        val base = engine(null).score(tickArgs, settings(), nowMs = 10_000L)!!
        val legacy = engine(model(kind = "logistic")).score(tickArgs, settings(), nowMs = 10_000L)!!
        // Zero-weight legacy model → sigmoid(0) = 0.5, blended 0.35 with the mid,
        // then 0.45 of that mixed into the engine fair (unchanged behavior).
        assertEquals(50.0, legacy.importedModelPp!!, 1e-4)
        assertEquals(0.35, legacy.blendWeight!!, 1e-6)
        val blended = 0.65 * 0.41 + 0.35 * 0.5
        val expected = (0.55 * (base.fairValuePp / 100.0) + 0.45 * blended) * 100.0
        assertEquals(expected, legacy.fairValuePp, 1e-4)
    }

    private fun engine(edge: EdgeModel?): ScoringEngine {
        val engine = ScoringEngine(idFactory = { "offset-test" })
        engine.rememberMeta(TICKER, 1_800_000L, 20_000.0, 2_000.0, floorStrike = 100_000.0)
        engine.edgeModel = edge
        return engine
    }

    private fun model(kind: String, bias: Double = 0.0): EdgeModel = EdgeModel.parse(
        """
        {
          "version": 2,
          "kind": "$kind",
          "feature_names": [
            "dist_to_strike_vol","tte_frac","market_mid","imbalance","spread",
            "momentum","realized_vol","cross_asset","time_of_day","digital_fair"
          ],
          "weights": [0,0,0,0,0,0,0,0,0,0],
          "bias": $bias,
          "mean": [0,0,0,0,0,0,0,0,0,0],
          "std":  [1,1,1,1,1,1,1,1,1,1],
          "platt_a": 1.0,
          "platt_b": 0.0,
          "blend_weight": ${if (kind == "logistic") 0.35 else 1.0},
          "fee_margin": 0.07,
          "confidence_margin": 0.03
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
