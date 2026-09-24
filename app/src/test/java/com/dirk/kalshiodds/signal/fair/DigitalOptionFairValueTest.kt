package com.dirk.kalshiodds.signal.fair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DigitalOptionFairValueTest {
    @Test
    fun atmIsNearHalf() {
        val p = DigitalOptionFairValue.pFinishAbove(
            spot = 100_000.0,
            strike = 100_000.0,
            tteSeconds = 900.0,
            sigmaAnnual = 0.60
        )
        assertTrue(p != null)
        assertTrue(abs(p!! - 0.5) < 0.04)
    }

    @Test
    fun deepItmApproachesOne() {
        val p = DigitalOptionFairValue.pFinishAbove(
            spot = 110_000.0,
            strike = 100_000.0,
            tteSeconds = 60.0,
            sigmaAnnual = 0.40
        )!!
        assertTrue(p > 0.95)
    }

    @Test
    fun deepOtmApproachesZero() {
        val p = DigitalOptionFairValue.pFinishAbove(
            spot = 90_000.0,
            strike = 100_000.0,
            tteSeconds = 60.0,
            sigmaAnnual = 0.40
        )!!
        assertTrue(p < 0.05)
    }

    @Test
    fun expiredUsesSpotVsStrike() {
        assertEquals(1.0, DigitalOptionFairValue.pFinishAbove(101.0, 100.0, 0.0, 0.5)!!, 1e-9)
        assertEquals(0.0, DigitalOptionFairValue.pFinishAbove(99.0, 100.0, 0.0, 0.5)!!, 1e-9)
    }

    @Test
    fun normCdfKnownValues() {
        assertEquals(0.5, DigitalOptionFairValue.normCdf(0.0), 1e-6)
        assertTrue(abs(DigitalOptionFairValue.normCdf(1.0) - 0.841344746) < 2e-4)
        assertTrue(abs(DigitalOptionFairValue.normCdf(-1.0) - 0.158655254) < 2e-4)
    }

    @Test
    fun realizedVolFromFlatReturnsIsNullOrTiny() {
        val closes = listOf(100.0, 100.0, 100.0, 100.0, 100.0)
        val vol = DigitalOptionFairValue.realizedVolAnnual(DigitalOptionFairValue.logReturns(closes))
        assertTrue(vol == null || vol <= 0.05)
    }
}
