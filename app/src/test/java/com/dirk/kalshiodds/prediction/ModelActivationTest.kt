package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelActivationTest {

    private fun manifest(
        modelBrier: Double = 0.158,
        marketBrier: Double = 0.160,
        modelLl: Double = 0.470,
        marketLl: Double = 0.476,
        ciLow: Double? = 0.001,
        markets: Int = 2_500,
        fixture: Boolean = false,
        schema: Int = 2
    ) = EdgeModelManifest(
        version = schema.toString(),
        trainedAt = "2026-09-27T00:00:00Z",
        nSamples = markets * 13,
        modelBrier = modelBrier,
        marketBrier = marketBrier,
        modelLogLoss = modelLl,
        marketLogLoss = marketLl,
        schema = schema,
        fixture = fixture,
        nMarketsHoldout = markets,
        logLossGainCiLow = ciLow
    )

    @Test
    fun parseManifestAndBeatsMarket() {
        val raw = """
            {
              "version": "2",
              "schema": 2,
              "fixture": false,
              "trained_at": "2026-09-27T00:00:00Z",
              "n_samples": 30000,
              "n_markets_holdout": 2400,
              "model_brier": 0.158,
              "market_brier": 0.160,
              "model_logloss": 0.470,
              "market_logloss": 0.476,
              "logloss_gain_ci_low": 0.002
            }
        """.trimIndent()
        val m = EdgeModelManifest.parse(raw)
        assertEquals(2400, m.nMarketsHoldout)
        assertTrue(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertTrue(d.activate)
        assertTrue(d.reason.contains("Activated"))
    }

    @Test
    fun doesNotActivateWhenWorseThanMarket() {
        val m = manifest(modelBrier = 0.30, modelLl = 0.70)
        assertFalse(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("does not beat"))
    }

    @Test
    fun ciTouchingZeroDoesNotActivate() {
        // Point estimate better, but the bootstrap CI of the gain includes zero.
        val d = ModelActivation.decide(manifest(ciLow = -0.0004), modelValid = true)
        assertFalse(d.activate)
    }

    @Test
    fun missingCiDoesNotActivate() {
        assertFalse(manifest(ciLow = null).beatsMarket)
    }

    @Test
    fun syntheticFixtureNeverActivates() {
        // The old trainer published its synthetic fallback with Brier 0.024 vs 0.186.
        val m = manifest(modelBrier = 0.024, marketBrier = 0.186, modelLl = 0.10, marketLl = 0.56, ciLow = 0.3, fixture = true)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("fixture"))
    }

    @Test
    fun smallHoldoutDoesNotActivate() {
        val d = ModelActivation.decide(manifest(markets = 120), modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("need ${EdgeModelManifest.MIN_HOLDOUT_MARKETS}"))
    }

    @Test
    fun retiredSchemaDoesNotActivate() {
        val d = ModelActivation.decide(manifest(schema = 1), modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("retired"))
    }

    @Test
    fun invalidModelKeepsPrevious() {
        val d = ModelActivation.decide(manifest(), modelValid = false)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("failed validation"))
    }

    @Test
    fun missingTrainedAtRejected() {
        val raw = """{"version":"2","n_samples":10,"model_brier":0.1,"market_brier":0.2,"model_logloss":0.3,"market_logloss":0.4}"""
        try {
            EdgeModelManifest.parse(raw)
            throw AssertionError("expected missing trained_at")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("trained_at"))
        }
    }

    @Test
    fun jsonRoundTrip() {
        val m = manifest()
        val again = EdgeModelManifest.parse(m.toJson())
        assertEquals(m.nSamples, again.nSamples)
        assertEquals(m.nMarketsHoldout, again.nMarketsHoldout)
        assertEquals(m.logLossGainCiLow!!, again.logLossGainCiLow!!, 1e-12)
        assertEquals(m.beatsMarket, again.beatsMarket)
    }
}
