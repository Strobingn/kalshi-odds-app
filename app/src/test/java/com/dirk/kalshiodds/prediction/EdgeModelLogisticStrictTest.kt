package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertThrows
import org.junit.Test

class EdgeModelLogisticStrictTest {

    private fun model(): EdgeModel = EdgeModel(
        version = 1, kind = "logistic",
        featureNames = listOf("a", "b", "c"),
        weights = floatArrayOf(1f, 1f, 1f), bias = 0f,
        mean = FloatArray(3), std = FloatArray(3) { 1f }
    )

    @Test
    fun logisticRejectsShortFeatureVector() {
        // A short vector used to silently drop trailing weights and return
        // a confident-looking wrong probability. Now it must fail loud.
        assertThrows(IllegalArgumentException::class.java) {
            model().predictYes(floatArrayOf(0.1f, 0.2f))
        }
    }

    @Test
    fun logisticRejectsNonFiniteFeature() {
        assertThrows(IllegalArgumentException::class.java) {
            model().predictYes(floatArrayOf(0.1f, Float.NaN, 0.3f))
        }
    }
}
