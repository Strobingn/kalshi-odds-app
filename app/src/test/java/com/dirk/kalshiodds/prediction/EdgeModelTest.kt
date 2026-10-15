package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the schema-2 market-offset model to `ml/fixtures/edge_model.json` and
 * `ml/fixtures/parity_sample.json` (computed by tools/backtest/pipeline.py).
 */
class EdgeModelTest {
    private val fixture = """
        {
          "version": 2,
          "schema": 2,
          "kind": "market_offset",
          "fixture": false,
          "feature_names": ["digital_gap","dist_to_strike_vol","spot_ret_1m","spot_ret_5m",
            "tte_frac","spread","tod_sin","tod_cos","has_spot"],
          "weights": [0.30, 0.05, 0.10, -0.08, -0.02, -0.15, 0.01, -0.01, 0.02],
          "bias": -0.03,
          "mean": [0.1, 0.2, 0.0, 0.0, 0.5, 0.02, 0.0, 0.0, 0.9],
          "std":  [0.6, 1.5, 0.001, 0.003, 0.28, 0.01, 0.7, 0.7, 0.3],
          "ev_margin": 0.02
        }
    """.trimIndent()

    private fun sampleFeatures(): DoubleArray = EdgeFeatures.build(
        EdgeFeatures.Raw(
            marketMid = 0.42,
            spread = 0.02,
            spot = 100_050.0,
            strike = 100_000.0,
            tteSeconds = 420.0,
            sigmaAnnual = 0.55,
            spotReturn1m = 0.0004,
            spotReturn5m = -0.0011,
            nowMs = 1_790_000_123_000L
        )
    )

    @Test
    fun featuresMatchPython() {
        val expected = doubleArrayOf(
            0.7198363336317869, 0.24913004254110238, 0.0004, -0.0011, 0.4666666666666667,
            0.02, -0.556960177295542, -0.8305391988984736, 1.0
        )
        val got = sampleFeatures()
        assertEquals(EdgeFeatures.NAMES.size, got.size)
        for (i in expected.indices) assertEquals("feature ${EdgeFeatures.NAMES[i]}", expected[i], got[i], 1e-9)
    }

    @Test
    fun predictionMatchesPython() {
        val model = EdgeModel.parse(fixture)
        assertEquals(0.510266473535539, model.predictYes(sampleFeatures(), 0.42), 1e-9)
    }

    @Test
    fun zeroModelReturnsTheMarket() {
        val zero = EdgeModel.parse(
            fixture.replace("[0.30, 0.05, 0.10, -0.08, -0.02, -0.15, 0.01, -0.01, 0.02]", "[0,0,0,0,0,0,0,0,0]")
                .replace("\"bias\": -0.03", "\"bias\": 0.0")
        )
        assertEquals(0.42, zero.predictYes(sampleFeatures(), 0.42), 1e-12)
        assertEquals(0.93, zero.predictYes(sampleFeatures(), 0.93), 1e-12)
    }

    @Test
    fun missingSpotZeroesSpotFeatures() {
        val x = EdgeFeatures.build(EdgeFeatures.Raw(marketMid = 0.6, spread = 0.03, tteSeconds = 600.0, nowMs = 0L))
        assertEquals(0.0, x[EdgeFeatures.NAMES.indexOf("digital_gap")], 0.0)
        assertEquals(0.0, x[EdgeFeatures.NAMES.indexOf("has_spot")], 0.0)
        // UTC midnight → sin 0, cos 1.
        assertEquals(0.0, x[EdgeFeatures.NAMES.indexOf("tod_sin")], 1e-12)
        assertEquals(1.0, x[EdgeFeatures.NAMES.indexOf("tod_cos")], 1e-12)
    }

    @Test
    fun retiredSchemaOneIsRejected() {
        val v1 = """
            {"version":1,"kind":"logistic","feature_names":["dist_to_strike_vol","tte_frac","market_mid",
            "imbalance","spread","momentum","realized_vol","cross_asset","time_of_day","digital_fair"],
            "weights":[0,0,0,0,0,0,0,0,0,0],"bias":0,"mean":[0,0,0,0,0,0,0,0,0,0],"std":[1,1,1,1,1,1,1,1,1,1]}
        """.trimIndent()
        expectReject(v1, "retired")
    }

    @Test
    fun syntheticFixtureIsRejected() {
        expectReject(fixture.replace("\"fixture\": false", "\"fixture\": true"), "fixture")
    }

    @Test
    fun jsonRoundTripPreservesWeights() {
        val model = EdgeModel.parse(fixture)
        val again = EdgeModel.parse(model.toJson())
        assertEquals(model.weights.toList(), again.weights.toList())
        assertEquals(model.bias, again.bias, 1e-12)
        assertEquals(model, again)
    }

    private fun expectReject(raw: String, messagePart: String) {
        try {
            EdgeModel.parse(raw)
            fail("expected rejection containing '$messagePart'")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains(messagePart))
        }
    }
}
