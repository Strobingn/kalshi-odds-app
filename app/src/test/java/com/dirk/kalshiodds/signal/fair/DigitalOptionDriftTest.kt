package com.dirk.kalshiodds.signal.fair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalOptionDriftTest {

    private val upTrend = List(30) { 0.001 }       // steady +0.1% per minute
    private val downTrend = List(30) { -0.001 }

    @Test
    fun driftRaisesProbabilityInUptrend() {
        val plain = DigitalOptionFairValue.pFinishAbove(100.0, 100.5, 600.0, 0.6)!!
        val drifted = DigitalOptionFairValue.pFinishAboveWithDrift(
            100.0, 100.5, 600.0, 0.6, upTrend
        )!!
        assertTrue(drifted > plain)
    }

    @Test
    fun driftLowersProbabilityInDowntrend() {
        val plain = DigitalOptionFairValue.pFinishAbove(100.0, 100.5, 600.0, 0.6)!!
        val drifted = DigitalOptionFairValue.pFinishAboveWithDrift(
            100.0, 100.5, 600.0, 0.6, downTrend
        )!!
        assertTrue(drifted < plain)
    }

    @Test
    fun driftFallsBackWithTooFewReturns() {
        val plain = DigitalOptionFairValue.pFinishAbove(100.0, 100.5, 600.0, 0.6)
        val drifted = DigitalOptionFairValue.pFinishAboveWithDrift(
            100.0, 100.5, 600.0, 0.6, listOf(0.01, -0.01)
        )
        assertEquals(plain!!, drifted!!, 1e-12)
    }

    @Test
    fun driftRejectsDegenerateInputs() {
        assertNull(DigitalOptionFairValue.pFinishAboveWithDrift(0.0, 100.0, 600.0, 0.6, upTrend))
        assertNull(DigitalOptionFairValue.pFinishAboveWithDrift(100.0, 100.0, 600.0, 0.0, upTrend))
    }

    @Test
    fun ewmaReactsFasterToVolSpike() {
        val quiet = List(20) { 0.0005 }
        val spiked = quiet + List(5) { 0.01 }
        val flat = DigitalOptionFairValue.realizedVolAnnual(spiked, 60.0)!!
        val ewma = DigitalOptionFairValue.ewmaVolAnnual(spiked, 60.0)!!
        // EWMA weights the recent spike far more than the flat window.
        assertTrue(ewma > flat)
    }

    @Test
    fun ewmaMatchesFlatVolInSteadyState() {
        // Constant |returns| → EWMA converges to the same variance.
        val steady = List(60) { if (it % 2 == 0) 0.001 else -0.001 }
        val flat = DigitalOptionFairValue.realizedVolAnnual(steady, 60.0)!!
        val ewma = DigitalOptionFairValue.ewmaVolAnnual(steady, 60.0)!!
        assertEquals(flat, ewma, flat * 0.15)
    }

    @Test
    fun ewmaRejectsShortSamples() {
        assertNull(DigitalOptionFairValue.ewmaVolAnnual(listOf(0.01, -0.01)))
    }

    @Test
    fun ewmaResultIsUsable() {
        val v = DigitalOptionFairValue.ewmaVolAnnual(upTrend + downTrend)
        assertNotNull(v)
        assertTrue(v!! > 0.0)
    }
}
