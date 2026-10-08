package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class EdgeModelTest {
    private val fixture = """
        {
          "version": 1,
          "kind": "logistic",
          "feature_names": [
            "dist_to_strike_vol","tte_frac","market_mid","imbalance","spread",
            "momentum","realized_vol","cross_asset","time_of_day","digital_fair","prev_window_return","is_funding_hour","mid_squared"
          ],
          "weights": [0.20, -0.10, 0.80, 0.05, -0.15, 0.10, -0.05, 0.00, 0.02, 0.40, 0, 0, 0],
          "bias": -0.25,
          "mean": [0,0,0,0,0,0,0,0,0,0,0,0,0],
          "std":  [1,1,1,1,1,1,1,1,1,1,1,1,1],
          "platt_a": 1.0,
          "platt_b": 0.0,
          "blend_weight": 0.35,
          "fee_margin": 0.07,
          "confidence_margin": 0.03
        }
    """.trimIndent()

    @Test
    fun loadAndParityWithPythonSigmoid() {
        val model = EdgeModel.parse(fixture)
        val x = floatArrayOf(0.4f, 0.5f, 0.42f, 0.1f, 0.02f, 0.01f, 0.04f, 0.0f, 0.3f, 0.48f, 0.0f, 0.0f, 0.0f)
        // Python: sigmoid(bias + w·x) with mean 0 / std 1
        val z = -0.25 + 0.20 * 0.4 + -0.10 * 0.5 + 0.80 * 0.42 + 0.05 * 0.1 +
            -0.15 * 0.02 + 0.10 * 0.01 + -0.05 * 0.04 + 0.00 * 0.0 + 0.02 * 0.3 + 0.40 * 0.48
        val expected = 1.0 / (1.0 + kotlin.math.exp(-z))
        val got = model.predictYes(x)
        assertTrue(abs(got - expected.coerceIn(0.02, 0.98)) < 1e-6)
        assertEquals(expected.coerceIn(0.02, 0.98), got, 1e-6)
    }

    @Test
    fun edgeGateRequiresFeePlusMargin() {
        val model = EdgeModel.parse(fixture)
        // market 0.50, model 0.51 → gap 1¢ < fee(0.0175)+0.03
        assertFalse(model.qualifiesEdge(0.51, 0.50, 0.07))
        assertTrue(model.qualifiesEdge(0.80, 0.40, 0.07))
    }

    @Test
    fun jsonRoundTripPreservesWeights() {
        val model = EdgeModel.parse(fixture)
        val again = EdgeModel.parse(model.toJson())
        assertEquals(model.weights.toList(), again.weights.toList())
        assertEquals(model.bias, again.bias, 1e-5f)
    }

    // --- offset_logistic (market-anchored) ---------------------------------

    @Test
    fun beatsMarketNeedsBrierAndLogLossOnRealData() {
        val base = EdgeModel.parse(offsetJson())
        val good = mapOf(
            "model_brier" to 0.15847,
            "market_brier" to 0.15852,
            "model_logloss" to 0.47333,
            "market_logloss" to 0.47368
        )
        assertTrue(base.copy(metrics = good).beatsMarket)
        // Brier alone is not enough (the fixture JSON has no log-loss).
        assertFalse(base.beatsMarket)
        assertFalse(base.copy(metrics = good + ("model_logloss" to 0.48)).beatsMarket)
        assertFalse(base.copy(metrics = good + ("model_brier" to 0.16)).beatsMarket)
        assertFalse(base.copy(metrics = good + ("synthetic" to 1.0)).beatsMarket)
        // A legacy logistic re-blends with the mid; never trusted to pick a side.
        assertFalse(base.copy(kind = "logistic", metrics = good).beatsMarket)
    }

    private fun offsetJson(
        weights: String = "[0,0,0,0,0,0,0,0,0,0,0,0,0]",
        bias: Double = 0.0,
        mean: String = "[0,0,0,0,0,0,0,0,0,0,0,0,0]",
        std: String = "[1,1,1,1,1,1,1,1,1,1,1,1,1]"
    ) = """
        {
          "version": 2,
          "kind": "offset_logistic",
          "feature_names": [
            "dist_to_strike_vol","tte_frac","market_mid","imbalance","spread",
            "momentum","realized_vol","cross_asset","time_of_day","digital_fair","prev_window_return","is_funding_hour","mid_squared"
          ],
          "weights": $weights,
          "bias": $bias,
          "mean": $mean,
          "std":  $std,
          "platt_a": 1.0,
          "platt_b": 0.0,
          "blend_weight": 1.0,
          "fee_margin": 0.07,
          "confidence_margin": 0.03,
          "mid_clip": 0.001,
          "design_features": ["dist_to_strike_vol","market_mid","cross_asset","digital_fair"],
          "metrics": {"n_holdout": 75000.0, "model_brier": 0.1590, "market_brier": 0.1592}
        }
    """.trimIndent()

    private fun featuresWithMid(mid: Double) =
        floatArrayOf(0.4f, 0.5f, mid.toFloat(), 0.1f, 0.02f, 0.01f, 0.04f, 0.001f, 0.3f, 0.48f, 0.0f, 0.0f, 0.0f)

    @Test
    fun offsetZeroWeightsReproduceTheMid() {
        // A scaler on market_mid must not touch the offset: logit(mid) is not a feature.
        val plain = EdgeModel.parse(offsetJson())
        val scaled = EdgeModel.parse(offsetJson(mean = "[0,0,0.5,0,0,0,0,0,0,0,0,0,0]", std = "[1,1,0.2,1,1,1,1,1,1,1,1,1,1]"))
        for (model in listOf(plain, scaled)) {
            assertTrue(model.isMarketAnchored)
            for (mid in listOf(0.001, 0.03, 0.31, 0.5, 0.77, 0.97, 0.999)) {
                assertEquals("mid $mid", mid, model.predictYes(featuresWithMid(mid), mid), 1e-6)
            }
        }
        // Clipped to Kalshi's usable tick range.
        assertEquals(0.001, plain.predictYes(featuresWithMid(0.0002), 0.0002), 1e-6)
        assertEquals(0.999, plain.predictYes(featuresWithMid(0.9999), 0.9999), 1e-6)
        // Without an explicit mid, the market_mid feature is the offset.
        assertEquals(0.62, plain.predictYes(featuresWithMid(0.62)), 1e-6)
    }

    @Test
    fun offsetParityWithPythonModelPredict() {
        // ml/train_edge.py model_predict: sigmoid(logit(clip(mid)) + b + Σ w·(x−mean)/std)
        val model = EdgeModel.parse(
            offsetJson(
                weights = "[0.30, 0, -0.10, 0, 0, 0, 0, 0.05, 0, 0.20, 0, 0, 0]",
                bias = 0.04,
                mean = "[0.1, 0, 0.5, 0, 0, 0, 0, 0.0, 0, 0.5, 0, 0, 0]",
                std = "[1.5, 1, 0.3, 1, 1, 1, 1, 0.002, 1, 0.3, 1, 1, 1]"
            )
        )
        val mid = 0.42
        val x = featuresWithMid(mid)
        val z = ln(mid / (1 - mid)) + 0.04 +
            0.30 * (0.4 - 0.1) / 1.5 +
            -0.10 * (mid - 0.5) / 0.3 +
            0.05 * (0.001 - 0.0) / 0.002 +
            0.20 * (0.48 - 0.5) / 0.3
        val expected = 1.0 / (1.0 + exp(-z))
        assertEquals(expected, model.predictYes(x, mid), 1e-6)
        // The anchored output is the fair: no second blend with the mid.
        val p = model.predictYes(x, mid)
        assertEquals(p, model.blendWithMarket(p, mid), 1e-12)
    }

    @Test
    fun offsetJsonRoundTrip() {
        val model = EdgeModel.parse(offsetJson(weights = "[0.3,0,-0.1,0,0,0,0,0.05,0,0.2,0,0,0]", bias = 0.04))
        val again = EdgeModel.parse(model.toJson())
        assertEquals("offset_logistic", again.kind)
        assertTrue(again.isMarketAnchored)
        assertEquals(model.midClip, again.midClip, 1e-9f)
        assertEquals(model.weights.toList(), again.weights.toList())
        assertEquals(model.bias, again.bias, 1e-6f)
        assertEquals(0.1590, again.metrics["model_brier"]!!, 1e-12)
        val x = featuresWithMid(0.37)
        assertEquals(model.predictYes(x, 0.37), again.predictYes(x, 0.37), 1e-12)
    }

    @Test
    fun legacyLogisticIgnoresTheMidAndStillBlends() {
        val model = EdgeModel.parse(fixture)
        assertFalse(model.isMarketAnchored)
        val x = floatArrayOf(0.4f, 0.5f, 0.42f, 0.1f, 0.02f, 0.01f, 0.04f, 0.0f, 0.3f, 0.48f, 0.0f, 0.0f, 0.0f)
        assertEquals(model.predictYes(x), model.predictYes(x, 0.90), 0.0)
        // 0.35 × model + 0.65 × mid, as before.
        assertEquals(0.35 * 0.80 + 0.65 * 0.40, model.blendWithMarket(0.80, 0.40), 1e-6)
        assertFalse(model.toJson().contains("mid_clip"))
    }

    @Test
    fun unknownKindIsRejected() {
        val bad = fixture.replace("\"kind\": \"logistic\"", "\"kind\": \"gbm\"")
        assertTrue(runCatching { EdgeModel.parse(bad) }.isFailure)
    }

    @Test
    fun noteStringsInMetricsDoNotBreakRoundTrip() {
        val withNote = fixture.replace(
            "\"confidence_margin\": 0.03",
            "\"confidence_margin\": 0.03, \"metrics\": {\"note\": \"hand-checked\", \"model_brier\": 0.2}"
        )
        val model = EdgeModel.parse(withNote)
        assertEquals(setOf("model_brier"), model.metrics.keys)
        assertEquals(0.2, EdgeModel.parse(model.toJson()).metrics["model_brier"]!!, 1e-12)
    }
}
