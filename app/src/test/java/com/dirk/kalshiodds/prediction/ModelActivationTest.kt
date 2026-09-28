package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelActivationTest {

    private fun honestPass() = EdgeModelManifest(
        version = "2",
        trainedAt = "2026-09-25T00:00:00Z",
        nSamples = 12000,
        nRows = 12000,
        nMarkets = 2500,
        nHoldout = 800,
        modelBrier = 0.160,
        marketBrier = 0.186,
        modelLogLoss = 0.480,
        marketLogLoss = 0.520,
        countsPresent = true
    )

    @Test
    fun parseManifestAndBeatsMarket() {
        val raw = """
            {
              "version": "2",
              "trained_at": "2026-09-25T00:00:00Z",
              "n_samples": 12000,
              "n_rows": 12000,
              "n_markets": 2500,
              "n_holdout": 800,
              "model_brier": 0.16,
              "market_brier": 0.186,
              "model_logloss": 0.48,
              "market_logloss": 0.52,
              "synthetic": false
            }
        """.trimIndent()
        val m = EdgeModelManifest.parse(raw)
        assertEquals(2500, m.nMarkets)
        assertTrue(m.countsPresent)
        assertTrue(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertTrue(d.activate)
        assertTrue(d.reason.contains("Activated"))
    }

    @Test
    fun refusesMissingCounts() {
        val raw = """
            {
              "version": "1",
              "trained_at": "2026-09-25T00:00:00Z",
              "n_samples": 120,
              "model_brier": 0.10,
              "market_brier": 0.22,
              "model_logloss": 0.40,
              "market_logloss": 0.58
            }
        """.trimIndent()
        val m = EdgeModelManifest.parse(raw)
        assertFalse(m.countsPresent)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("lacks") || d.reason.contains("market-only"))
    }

    @Test
    fun refusesSynthetic() {
        val m = honestPass().copy(synthetic = true)
        assertFalse(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("synthetic"))
    }

    @Test
    fun refusesBelowMinimumMarkets() {
        val m = honestPass().copy(nMarkets = 60, nRows = 360, nHoldout = 270, nSamples = 360)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("too small") || d.reason.contains("market-only"))
    }

    @Test
    fun refusesTinyMargin() {
        val m = honestPass().copy(
            modelBrier = 0.185,
            marketBrier = 0.186,
            modelLogLoss = 0.519,
            marketLogLoss = 0.520,
            brierMargin = 0.001,
            logLossMargin = 0.001
        )
        assertFalse(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
    }

    @Test
    fun doesNotActivateWhenWorseThanMarket() {
        val m = honestPass().copy(
            modelBrier = 0.30,
            marketBrier = 0.22,
            modelLogLoss = 0.70,
            marketLogLoss = 0.55,
            brierMargin = 0.22 - 0.30,
            logLossMargin = 0.55 - 0.70
        )
        assertFalse(m.beatsMarket)
        val d = ModelActivation.decide(m, modelValid = true)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("does not beat") || d.reason.contains("market-only"))
    }

    @Test
    fun invalidModelKeepsPrevious() {
        val d = ModelActivation.decide(honestPass(), modelValid = false)
        assertFalse(d.activate)
        assertTrue(d.reason.contains("failed validation"))
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
        val m = honestPass()
        val again = EdgeModelManifest.parse(m.toJson())
        assertEquals(m.nMarkets, again.nMarkets)
        assertEquals(m.nRows, again.nRows)
        assertEquals(m.beatsMarket, again.beatsMarket)
        assertFalse(again.synthetic)
    }

    @Test
    fun missingManifestFallsBackToMarketOnly() {
        val d = ModelActivation.decideMissingManifest()
        assertFalse(d.activate)
        assertTrue(d.reason.contains("market-only"))
    }
}
