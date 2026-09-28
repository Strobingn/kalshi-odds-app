package com.dirk.kalshiodds.signal.latefav

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LateFavoriteRuleTest {

    private val strike = 100_000.0
    private val sigma = 0.001

    /** Spot that sits exactly [z] σ-units from strike with [tte] seconds left. */
    private fun spotAtZ(z: Double, tte: Long = 120L): Double =
        strike * exp(z * sigma * sqrt(tte / 60.0))

    private fun inputs(
        ticker: String = "KXBTC15M-26SEP281215-15",
        tte: Long? = 120L,
        z: Double = 2.0,
        spot: Double? = spotAtZ(z, tte ?: 120L),
        strikeUsd: Double? = strike,
        sigma1m: Double? = sigma,
        yesAsk: Double? = 0.95,
        noAsk: Double? = 0.06
    ) = LateFavoriteRule.Inputs(
        ticker = ticker,
        nowMs = 1_000L,
        tteSeconds = tte,
        spotUsd = spot,
        strikeUsd = strikeUsd,
        sigma1m = sigma1m,
        yesAsk = yesAsk,
        noAsk = noAsk
    )

    @Test
    fun constantsMatchPreRegisteredRule() {
        assertEquals(300L, LateFavoriteRule.MAX_TTE_SECONDS)
        assertEquals(1.5, LateFavoriteRule.MIN_ABS_Z, 0.0)
        assertEquals(0.80, LateFavoriteRule.MIN_ASK, 0.0)
        assertEquals(0.97, LateFavoriteRule.MAX_ASK, 0.0)
        assertEquals(5.0, LateFavoriteRule.STAKE_USD, 0.0)
        assertEquals(0.01, LateFavoriteRule.WORSE_FILL_SLIPPAGE, 0.0)
    }

    @Test
    fun zUsesOneMinuteSigmaScaledByMinutesLeft() {
        val z = LateFavoriteRule.z(100_500.0, 100_000.0, 0.001, 300L)!!
        assertEquals(ln(1.005) / (0.001 * sqrt(5.0)), z, 1e-12)
        assertTrue(LateFavoriteRule.z(99_500.0, 100_000.0, 0.001, 300L)!! < 0.0)
    }

    @Test
    fun missingOrBadInputsGiveNoZ() {
        assertNull(LateFavoriteRule.z(null, strike, sigma, 60L))
        assertNull(LateFavoriteRule.z(100.0, null, sigma, 60L))
        assertNull(LateFavoriteRule.z(100.0, strike, null, 60L))
        assertNull(LateFavoriteRule.z(100.0, strike, sigma, null))
        assertNull(LateFavoriteRule.z(100.0, strike, 0.0, 60L))
        assertNull(LateFavoriteRule.z(100.0, strike, Double.NaN, 60L))
        assertNull(LateFavoriteRule.z(0.0, strike, sigma, 60L))
        assertNull(LateFavoriteRule.z(100.0, strike, sigma, 0L))
    }

    @Test
    fun spotAboveStrikeBuysYesAtYesAsk() {
        val d = LateFavoriteRule.evaluate(inputs(z = 2.0), alreadyEntered = false)!!
        assertEquals("YES", d.side)
        assertEquals(0.95, d.ask, 1e-12)
        assertEquals(2.0, d.z, 1e-9)
        assertEquals(120L, d.tteSeconds)
    }

    @Test
    fun spotBelowStrikeBuysNoAtNoAsk() {
        val d = LateFavoriteRule.evaluate(
            inputs(z = -2.0, yesAsk = 0.06, noAsk = 0.93),
            alreadyEntered = false
        )!!
        assertEquals("NO", d.side)
        assertEquals(0.93, d.ask, 1e-12)
        assertTrue(d.z < 0.0)
    }

    @Test
    fun timeLeftBoundaryIsInclusiveAt300Seconds() {
        assertNotNull(LateFavoriteRule.evaluate(inputs(tte = 300L), false))
        assertNull(LateFavoriteRule.evaluate(inputs(tte = 301L), false))
        assertNotNull(LateFavoriteRule.evaluate(inputs(tte = 1L), false))
        assertNull(LateFavoriteRule.evaluate(inputs(tte = 0L, spot = spotAtZ(2.0)), false))
        assertNull(LateFavoriteRule.evaluate(inputs(tte = null, spot = spotAtZ(2.0)), false))
    }

    @Test
    fun zBoundaryIsInclusiveAtOnePointFive() {
        assertNotNull(LateFavoriteRule.evaluate(inputs(z = 1.5), false))
        assertNull(LateFavoriteRule.evaluate(inputs(z = 1.49), false))
        assertNotNull(LateFavoriteRule.evaluate(inputs(z = -1.5, yesAsk = 0.1, noAsk = 0.9), false))
        assertNull(LateFavoriteRule.evaluate(inputs(z = -1.49, yesAsk = 0.1, noAsk = 0.9), false))
        assertNull(LateFavoriteRule.evaluate(inputs(z = 0.0), false))
    }

    @Test
    fun askBandIsInclusiveAt80And97Cents() {
        assertNotNull(LateFavoriteRule.evaluate(inputs(yesAsk = 0.80), false))
        assertNotNull(LateFavoriteRule.evaluate(inputs(yesAsk = 0.97), false))
        assertNull(LateFavoriteRule.evaluate(inputs(yesAsk = 0.79), false))
        assertNull(LateFavoriteRule.evaluate(inputs(yesAsk = 0.98), false))
        assertNull(LateFavoriteRule.evaluate(inputs(yesAsk = null), false))
        assertNull(LateFavoriteRule.evaluate(inputs(yesAsk = Double.NaN), false))
    }

    @Test
    fun usesTheSpotSideAskNotTheOtherSide() {
        // YES side qualifies on z, but only the NO ask is in the band.
        assertNull(LateFavoriteRule.evaluate(inputs(z = 2.0, yesAsk = 0.99, noAsk = 0.90), false))
    }

    @Test
    fun missingSpotSigmaOrStrikeMeansNoEntry() {
        assertNull(LateFavoriteRule.evaluate(inputs(spot = null), false))
        assertNull(LateFavoriteRule.evaluate(inputs(sigma1m = null), false))
        assertNull(LateFavoriteRule.evaluate(inputs(sigma1m = 0.0), false))
        assertNull(LateFavoriteRule.evaluate(inputs(strikeUsd = null), false))
    }

    @Test
    fun onlyLiveBitcoinSeriesAndOnePerMarket() {
        assertNull(LateFavoriteRule.evaluate(inputs(ticker = "KXETH15M-26SEP281215-15"), false))
        assertNull(LateFavoriteRule.evaluate(inputs(), alreadyEntered = true))
    }

    @Test
    fun sizingMirrorsPipelineSizeAllIn() {
        // Expected values from tools/backtest/pipeline.py size_all_in(p, 5.0).
        fun check(p: Double, c: Int, cost: Double) {
            val s = LateFavoriteRule.sizeAllIn(p)!!
            assertEquals("contracts @ $p", c, s.contracts)
            assertEquals("cost @ $p", cost, s.costUsd, 1e-9)
            assertTrue(s.costUsd <= 5.0 + 1e-9)
        }
        check(0.80, 6, 4.87)
        check(0.81, 6, 4.93)
        check(0.90, 5, 4.54)
        check(0.95, 5, 4.77)
        check(0.96, 5, 4.82)
        check(0.97, 5, 4.87)
        check(0.98, 5, 4.91)
        assertNull(LateFavoriteRule.sizeAllIn(null))
        assertNull(LateFavoriteRule.sizeAllIn(1.5))
    }

    @Test
    fun worseFillIsSizedOneCentHigher() {
        val d = LateFavoriteRule.evaluate(inputs(yesAsk = 0.95), false)!!
        assertEquals(0.96, d.worseAsk, 1e-9)
        assertEquals(5, d.sized.contracts)
        assertEquals(4.77, d.sized.costUsd, 1e-9)
        assertEquals(5, d.worse!!.contracts)
        assertEquals(4.82, d.worse!!.costUsd, 1e-9)
    }

    @Test
    fun pnlWinLossVoid() {
        val s = LateFavoriteRule.Sized(5, 4.77, 0.02)
        assertEquals(0.23, LateFavoriteRule.pnl(s, true)!!, 1e-9)
        assertEquals(-4.77, LateFavoriteRule.pnl(s, false)!!, 1e-9)
        assertEquals(0.0, LateFavoriteRule.pnl(s, null)!!, 1e-9)
        assertNull(LateFavoriteRule.pnl(null, true))
    }

    @Test
    fun askBandHelper() {
        assertTrue(LateFavoriteRule.inAskBand(0.80))
        assertTrue(LateFavoriteRule.inAskBand(0.97))
        assertFalse(LateFavoriteRule.inAskBand(0.7999))
        assertFalse(LateFavoriteRule.inAskBand(0.9701))
        assertFalse(LateFavoriteRule.inAskBand(null))
    }
}
