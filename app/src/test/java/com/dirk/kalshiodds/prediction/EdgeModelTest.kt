package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EdgeModelTest {
    private val fixture = """
        {
          "version": 1,
          "kind": "logistic",
          "feature_names": [
            "dist_to_strike_vol","tte_frac","market_mid","imbalance","spread",
            "momentum","realized_vol","cross_asset","time_of_day","digital_fair"
          ],
          "weights": [0.20, -0.10, 0.80, 0.05, -0.15, 0.10, -0.05, 0.00, 0.02, 0.40],
          "bias": -0.25,
          "mean": [0,0,0,0,0,0,0,0,0,0],
          "std":  [1,1,1,1,1,1,1,1,1,1],
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
        val x = floatArrayOf(0.4f, 0.5f, 0.42f, 0.1f, 0.02f, 0.01f, 0.04f, 0.0f, 0.3f, 0.48f)
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
}
