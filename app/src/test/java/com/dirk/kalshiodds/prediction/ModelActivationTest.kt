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
              "data_source": "kalshi_settled_coinbase_spot_v1",
              "final_window_samples": 20,
              "final_window_model_brier": 0.17,
              "final_window_market_brier": 0.22,
              "calibration_error": 0.03,
              "market_calibration_error": 0.05,
              "promotion_eligible": true
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
    fun candidateThatMissesPromotionGateKeepsPreviousModel() {
        val m = EdgeModelManifest(
            version = "1",
            trainedAt = "2026-09-25T00:00:00Z",
            nSamples = 80,
            modelBrier = 0.10,
            marketBrier = 0.22,
            modelLogLoss = 0.40,
            marketLogLoss = 0.55,
            dataSource = "kalshi_settled_coinbase_spot_v1",
            finalWindowSamples = 20,
            finalWindowModelBrier = 0.10,
            finalWindowMarketBrier = 0.22,
            promotionEligible = false
        )
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("promotion gates"))
    }

    @Test
    fun historicalTierManifestIsAcceptedWhenItPassesPromotion() {
        val m = EdgeModelManifest(
            version = "2",
            trainedAt = "2026-10-06T00:00:00Z",
            nSamples = 120,
            modelBrier = 0.18,
            marketBrier = 0.22,
            modelLogLoss = 0.50,
            marketLogLoss = 0.58,
            dataSource = "kalshi_live_historical_coinbase_spot_v2",
            finalWindowSamples = 20,
            finalWindowModelBrier = 0.17,
            finalWindowMarketBrier = 0.22,
            calibrationError = 0.03,
            marketCalibrationError = 0.05,
            promotionEligible = true
        )

        assertTrue(ModelActivation.decide(m, modelValid = true).activate)
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
            val decision = ModelActivation.decide(m, modelValid = true)
            assertFalse(decision.activate)
            assertTrue(
                decision.reason.contains(
                    if (source == "unknown") "omits data_source" else "synthetic fixture"
                )
            )
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
