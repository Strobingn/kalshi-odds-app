package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EdgeModelTest {

    @Test
    fun gbdtRoundTripKeepsYesProbabilityAndRejectsBrokenTree() {
        val names = EdgeFeatures.NAMES
        val tree = EdgeTree(listOf(
            EdgeTreeNode(feature = 2, threshold = 0.5, left = 1, right = 2),
            EdgeTreeNode(value = -0.7), EdgeTreeNode(value = 0.9)
        ))
        val model = EdgeModel(
            version = 2, kind = "gbdt", featureNames = names,
            weights = FloatArray(names.size), bias = 0f,
            mean = FloatArray(names.size), std = FloatArray(names.size) { 1f },
            trees = listOf(tree), baseScore = -0.2, learningRate = 0.1
        )
        val restored = EdgeModel.parse(model.toJson())
        val below = FloatArray(names.size).also { it[2] = 0.4f }
        val above = FloatArray(names.size).also { it[2] = 0.6f }
        assertEquals(model.predictYes(below), restored.predictYes(below), 1e-6)
        assertEquals(model.predictYes(above), restored.predictYes(above), 1e-6)
        assertTrue(restored.predictYes(above) > restored.predictYes(below))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            model.copy(trees = listOf(EdgeTree(listOf(EdgeTreeNode(feature = 99, left = 1, right = 2)))))
        }
    }

    @Test
    fun treeSplitKeepsDoublePrecisionThreshold() {
        val v = 0.31415927f
        val threshold = v.toDouble() - 1e-10
        val tree = EdgeTree(listOf(
            EdgeTreeNode(feature = 0, threshold = threshold, left = 1, right = 2),
            EdgeTreeNode(value = -1.0), EdgeTreeNode(value = 1.0)
        ))
        val raw = FloatArray(EdgeFeatures.SIZE).also { it[0] = v }
        assertEquals(1.0, tree.predict(raw), 0.0)
        val model = EdgeModel(
            version = 2, kind = "gbdt", featureNames = EdgeFeatures.NAMES,
            weights = FloatArray(EdgeFeatures.SIZE), bias = 0f,
            mean = FloatArray(EdgeFeatures.SIZE), std = FloatArray(EdgeFeatures.SIZE) { 1f },
            trees = listOf(tree)
        )
        assertEquals(1.0, EdgeModel.parse(model.toJson()).trees[0].predict(raw), 0.0)
    }
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
