package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeEligibilityTest {
    private fun ok() = TradeEligibility.Input(
        calibratedProbability = 0.70,
        calibrationLevel = RegimeCalibration.Level.REGIME,
        uncertaintyHalfWidth = 0.03,
        regimeApproved = true,
        finalWindow = false,
        finalWindowReady = true,
        settlementSourceFresh = true,
        bookFresh = true,
        visibleDepth = 100.0,
        orderSize = 10.0,
        ask = 0.60,
        longshotValidated = false,
        pFill = 0.9,
        expectedNetPerContract = 0.03
    )

    private fun reason(i: TradeEligibility.Input) = TradeEligibility.evaluate(i).reason

    @Test
    fun allowsWhenEveryCheckPasses() {
        val v = TradeEligibility.evaluate(ok())
        assertTrue(v.allow)
        assertEquals("BET", v.decision)
    }

    @Test
    fun finalSixtySecondsAreNoBetUntilRegimeCalibrated() {
        val v = TradeEligibility.evaluate(ok().copy(finalWindow = true, finalWindowReady = false))
        assertFalse(v.allow)
        assertEquals("NO BET — model uncertainty too high for this final-window regime", v.reason)
        assertTrue(TradeEligibility.evaluate(ok().copy(finalWindow = true, finalWindowReady = true)).allow)
    }

    @Test
    fun coldStartAndWideIntervalAbstain() {
        assertEquals(TradeEligibility.UNCALIBRATED_REASON, reason(ok().copy(calibratedProbability = null)))
        assertEquals(
            TradeEligibility.UNCERTAIN_REASON,
            reason(ok().copy(uncertaintyHalfWidth = UncertaintyGate.COLD_START_HALF_WIDTH))
        )
        assertEquals(TradeEligibility.UNCERTAIN_REASON, reason(ok().copy(uncertaintyHalfWidth = 0.081)))
        assertTrue(TradeEligibility.evaluate(ok().copy(uncertaintyHalfWidth = 0.08)).allow)
    }

    @Test
    fun conformalHalfWidthShrinksWithSamplesAndRespectsEnsembleSpread() {
        val small = UncertaintyGate.halfWidth(0.5, 50)
        val big = UncertaintyGate.halfWidth(0.5, 5000)
        assertTrue(small > big)
        assertTrue(UncertaintyGate.tooUncertain(small))
        assertFalse(UncertaintyGate.tooUncertain(big))
        assertEquals(0.12, UncertaintyGate.halfWidth(0.5, 5000, ensembleSpread = 0.12), 1e-12)
    }

    @Test
    fun regimeNotBetterThanMarketIsNoBet() {
        assertEquals(TradeEligibility.NOT_BETTER_REASON, reason(ok().copy(regimeApproved = false)))
    }

    @Test
    fun staleSettlementOrBookIsNoBet() {
        assertEquals(TradeEligibility.SETTLEMENT_STALE_REASON, reason(ok().copy(settlementSourceFresh = false)))
        assertEquals(TradeEligibility.BOOK_REASON, reason(ok().copy(bookFresh = false)))
        assertEquals(TradeEligibility.BOOK_REASON, reason(ok().copy(visibleDepth = 5.0)))
    }

    @Test
    fun tinyQuoteGuard() {
        assertEquals(TradeEligibility.TINY_REASON, reason(ok().copy(ask = 0.04)))
        assertEquals(TradeEligibility.TINY_REASON, reason(ok().copy(ask = 0.03)))
        assertEquals(TradeEligibility.TINY_REASON, reason(ok().copy(ask = 0.97)))
    }

    @Test
    fun longshotNeedsValidation() {
        assertEquals("NO BET — longshot not validated", reason(ok().copy(ask = 0.12)))
        assertTrue(TradeEligibility.evaluate(ok().copy(ask = 0.12, longshotValidated = true)).allow)
    }

    @Test
    fun usesExpectedNetAndPFillNotGrossPayout() {
        // Gross edge: 0.70 win prob at 0.60 is +10¢ gross, but the net after fees/fill/adverse is ≤ 0.
        val grossPositive = ok().copy(expectedNetPerContract = -0.001)
        assertEquals(TradeEligibility.NET_REASON, reason(grossPositive))
        assertEquals(TradeEligibility.PFILL_REASON, reason(ok().copy(pFill = 0.2)))
    }

    @Test
    fun balanceAndMinStake() {
        assertEquals(
            "NO BET — Kalshi balance unavailable",
            reason(ok().copy(balanceRequired = true, balanceAvailable = false))
        )
        assertEquals(AutopilotMinStake.REASON, reason(ok().copy(enforceMinStake = true, stakeUsd = 4.99)))
        assertTrue(TradeEligibility.evaluate(ok().copy(enforceMinStake = true, stakeUsd = 5.0)).allow)
    }

    @Test
    fun favouriteIsTheHigherPricedSide() {
        assertEquals("YES", FavouritePolicy.favouriteSide(0.88, 0.14))
        assertEquals("NO", FavouritePolicy.favouriteSide(0.10, 0.92))
        assertTrue(FavouritePolicy.isLongshot(0.15))
        assertFalse(FavouritePolicy.isLongshot(0.16))
    }
}
