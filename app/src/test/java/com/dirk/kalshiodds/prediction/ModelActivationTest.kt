package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelActivationTest {

    @Test
    fun parseManifestAndBeatsMarket() {
        val raw = """
            {
              "version": "1",
              "trained_at": "2026-09-25T00:00:00Z",
              "n_samples": 120,
              "model_brier": 0.18,
              "market_brier": 0.22,
              "model_logloss": 0.50,
              "market_logloss": 0.58,
              "data_source": "kalshi_settled_coinbase_spot_v1"
            }
        """.trimIndent()
        val m = EdgeModelManifest.parse(raw)
        assertEquals(120, m.nSamples)
        assertTrue(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertTrue(d.activate)
        assertTrue(d.reason.contains("Activated"))
    }

    @Test
    fun doesNotActivateWhenWorseThanMarket() {
        val m = EdgeModelManifest(
            version = "1",
            trainedAt = "2026-09-25T00:00:00Z",
            nSamples = 80,
            modelBrier = 0.30,
            marketBrier = 0.22,
            modelLogLoss = 0.70,
            marketLogLoss = 0.55,
            dataSource = "kalshi_settled_coinbase_spot_v1"
        )
        assertFalse(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("does not beat"))
    }

    @Test
    fun invalidModelKeepsPrevious() {
        val m = EdgeModelManifest(
            version = "1",
            trainedAt = "2026-09-25T00:00:00Z",
            nSamples = 80,
            modelBrier = 0.10,
            marketBrier = 0.22,
            modelLogLoss = 0.40,
            marketLogLoss = 0.55
        )
        val d = ModelActivation.decide(m, modelValid = false)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("failed validation"))
    }

    @Test
    fun syntheticOrLegacyManifestCannotActivate() {
        for (source in listOf("synthetic_fixture", "unknown")) {
            val m = EdgeModelManifest(
                version = "1", trainedAt = "2026-09-25T00:00:00Z", nSamples = 180,
                modelBrier = 0.02, marketBrier = 0.18,
                modelLogLoss = 0.10, marketLogLoss = 0.50, dataSource = source
            )
            assertFalse(m.beatsMarket)
            assertFalse(ModelActivation.decide(m, modelValid = true).activate)
        }
    }

    @Test
    fun missingTrainedAtRejected() {
        val raw = """{"version":"1","n_samples":10,"model_brier":0.1,"market_brier":0.2,"model_logloss":0.3,"market_logloss":0.4}"""
        try {
            EdgeModelManifest.parse(raw)
            throw AssertionError("expected missing trained_at")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("trained_at"))
        }
    }

    @Test
    fun jsonRoundTrip() {
        val m = EdgeModelManifest(
            version = "2",
            trainedAt = "2026-09-25T08:17:00Z",
            nSamples = 40,
            modelBrier = 0.19,
            marketBrier = 0.21,
            modelLogLoss = 0.51,
            marketLogLoss = 0.53
        )
        val again = EdgeModelManifest.parse(m.toJson())
        assertEquals(m.nSamples, again.nSamples)
        assertEquals(m.beatsMarket, again.beatsMarket)
    }
}
