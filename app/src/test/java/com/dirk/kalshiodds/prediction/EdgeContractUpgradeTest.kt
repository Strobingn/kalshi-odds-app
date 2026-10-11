package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Upgrades keep older models, and the funding flag matches the trainer on the phone's clock. */
class EdgeContractUpgradeTest {

    private val legacy = """
        {
          "version": 1, "kind": "logistic",
          "feature_names": ["dist_to_strike_vol","tte_frac","market_mid","imbalance","spread",
                            "momentum","realized_vol","cross_asset","time_of_day","digital_fair"],
          "weights": [0.20, -0.10, 0.80, 0.05, -0.15, 0.10, -0.05, 0.00, 0.02, 0.40],
          "bias": -0.25,
          "mean": [0,0,0,0,0,0,0,0,0,0],
          "std":  [1,1,1,1,1,1,1,1,1,1]
        }
    """.trimIndent()

    @Test
    fun aTenFeatureModelStillLoadsAndPredictsTheSame() {
        val m = EdgeModel.parse(legacy)
        assertEquals(EdgeFeatures.NAMES, m.featureNames)
        assertEquals(EdgeFeatures.SIZE, m.weights.size)
        assertEquals(0f, m.weights[12], 0f)
        assertEquals(1f, m.std[12], 0f)
        val x = floatArrayOf(0.4f, 0.5f, 0.42f, 0.1f, 0.02f, 0.01f, 0.04f, 0.0f, 0.3f, 0.48f, 0.02f, 1f, 0.18f)
        val z = -0.25 + 0.20 * 0.4 + -0.10 * 0.5 + 0.80 * 0.42 + 0.05 * 0.1 +
            -0.15 * 0.02 + 0.10 * 0.01 + -0.05 * 0.04 + 0.02 * 0.3 + 0.40 * 0.48
        assertEquals("the three new inputs carry no weight", 1.0 / (1.0 + kotlin.math.exp(-z)), m.predictYes(x), 1e-6)
        // And it round-trips as a 13-feature model.
        assertEquals(EdgeFeatures.SIZE, EdgeModel.parse(m.toJson()).weights.size)
    }

    @Test
    fun aModelWithOtherFeaturesIsStillRefused() {
        val other = legacy.replace("\"dist_to_strike_vol\"", "\"something_else\"")
        assertTrue(runCatching { EdgeModel.parse(other) }.isFailure)
    }

    @Test
    fun fundingFlagSurvivesTheTruncatedTimeLeft() {
        val close = 1_791_561_600_000L  // 2026-10-09 16:00:00 UTC, a funding settlement
        assertEquals(0L, close % (8 * 3_600_000L))
        // 5 min 12.456 s before the close the app sees 312 whole seconds left.
        val now = close - 312_456L
        assertTrue(EdgeFeatures.isFundingHour(now, 312.0))
        assertFalse("the window closing at 16:15 is not", EdgeFeatures.isFundingHour(now + 900_000L, 312.0))
        assertFalse("nor the one closing at 15:45", EdgeFeatures.isFundingHour(now - 900_000L, 312.0))
    }
}
