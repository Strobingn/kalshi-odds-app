package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

/** Gemini math invariants for the BTC paper scalpers, plus a YES/NO symmetry smoke. */
class BtcScalpMathFixTest {
    @Test fun leadForecasterUsesEffectiveTauAndKeepsRawTimeLeft() {
        val tau = 600.0
        val sigma = 2e-5
        val lead = 100_100.0
        val strike = 100_000.0
        val x = LeadForecaster.features(0.50, null, 0.0, lead, strike, sigma, tau) { null }!!
        val z = sigma * sqrt(ScalpRule.effectiveTauSeconds(tau))
        val d = (ln(lead / strike) / z).coerceIn(-4.0, 4.0)
        assertEquals(560.0, ScalpRule.effectiveTauSeconds(tau), 1e-9)
        assertEquals(d / 4.0, x[5], 1e-9)
        assertEquals(tau / 900.0, x[6], 1e-12)
        val raw = (ln(lead / strike) / (sigma * sqrt(tau))).coerceIn(-4.0, 4.0) / 4.0
        assertTrue(kotlin.math.abs(x[5] - raw) > 1e-4)
        val mirror = strike * strike / lead
        val y = LeadForecaster.features(0.50, null, 0.0, mirror, strike, sigma, tau) { null }!!
        assertEquals(-x[5], y[5], 1e-9)
    }

    @Test fun entryEdgeCoversRoundTripNotTheStaticSafetyMargin() {
        val spread = 0.01
        val ask = 0.50
        val fee = ScalpRule.orderFee(1, ask)
        assertEquals(0.02, fee, 1e-12)
        assertEquals(spread + fee + fee, ForecastScalp.minEntryEdgeUsd(spread, ask), 1e-12)
        assertTrue(ForecastScalp.minEntryEdgeUsd(spread, ask) > ForecastScalp.SAFETY)
        assertFalse(ForecastScalp.wantsEntry(0.50, spread, ask, 0.005, 600.0))
        val hurdle = ForecastScalp.minEntryEdgeUsd(spread, ask) + ForecastScalp.SAFETY
        assertFalse(ForecastScalp.wantsEntry(0.50, spread, ask, hurdle, 600.0))
        assertTrue(ForecastScalp.wantsEntry(0.50, spread, ask, hurdle + 0.001, 600.0))
    }

    @Test fun reversalDeadbandStopsASlightlyPositiveForecastFromExiting() {
        assertNull(ForecastScalp.wantsExit(0.005, 0.55, 10.0, 300, 600.0))
        assertNull(ForecastScalp.wantsExit(0.0, 0.55, 10.0, 300, 600.0))
        assertNull(ForecastScalp.wantsExit(-0.009, 0.55, 10.0, 300, 600.0))
        assertEquals("forecast turned (move < exit fee)", ForecastScalp.wantsExit(-ForecastScalp.REVERSAL_DEADBAND_USD, 0.55, 10.0, 300, 600.0))
        assertEquals("forecast flipped hard", ForecastScalp.wantsExit(-0.05, 0.55, 10.0, 300, 600.0))
        assertNull(ForecastScalp.wantsExit(Double.NaN, 0.55, 10.0, 300, 600.0))
        assertEquals("take profit ≥ 90¢", ForecastScalp.wantsExit(Double.NaN, 0.92, 10.0, 300, 600.0))
    }

    @Test fun stopFloorIgnoresAOneCentTickAndKeepsWiderStops() {
        assertEquals(0.03, ScalpV2.minStopDistanceUsd(0.01, 0.0), 1e-12)
        assertEquals(0.05, ScalpV2.minStopDistanceUsd(0.02, 0.02), 1e-12)
        val entry = 0.50
        val dist = ScalpV2.dipStopDistance(entry, 1, 1, 0.01)
        assertTrue(dist + 1e-12 >= 0.03)
        assertTrue(dist + 1e-12 >= 0.01 + ScalpRule.feePerContract(1, entry) * 2)
        assertNull(ScalpV2.dipExit(entry, entry - 0.01, entry, ScalpV2.TRAIL, dist, 1))
        val wide = ScalpV2.dipStopDistance(entry, 1, 12, 0.01)
        assertTrue(wide + 1e-12 >= 0.12)
        assertNull(ScalpV2.exit(entry, 0.02, entry, entry - 0.01, false))
        assertNotNullStop(ScalpV2.exit(entry, 0.06, entry, entry - 0.06, false))
    }

    @Test fun downsizeRoundsEightyPercentInsteadOfTruncatingFourFifths() {
        assertEquals(5, ScalpRule.downsizeContracts(6, 1))
        assertEquals(4, 6 * 4 / 5)
        assertEquals(2, ScalpRule.downsizeContracts(2, 1))
        assertEquals(1, 2 * 4 / 5)
        assertEquals(1, ScalpRule.downsizeContracts(1, 1))
        assertEquals(8, ScalpRule.downsizeContracts(10, 1))
    }

    @Test fun yesAndNoQuotesStaySymmetric() {
        val p = 0.62
        val ask = 0.55
        assertEquals(HftMathEngines.evYes(p, ask), HftMathEngines.evNo(1.0 - p, ask), 1e-12)
        assertEquals(ScalpRule.orderFee(10, p), ScalpRule.orderFee(10, 1.0 - p), 1e-12)
        val yes = HftSignals.studentTFair(100_200.0, 100_000.0, 2e-5, 120.0, "YES")!!
        val no = HftSignals.studentTFair(100_200.0, 100_000.0, 2e-5, 120.0, "NO")!!
        assertEquals(1.0, yes + no, 1e-9)
        val towardAsk = HftMathEngines.vamp(0.40, 0.42, 100.0, 10.0)
        val towardBid = HftMathEngines.vamp(0.40, 0.42, 10.0, 100.0)
        assertTrue(towardAsk > towardBid)
        assertTrue(towardAsk > 0.41)
        val flat = HftMathEngines.dosQuote(0.49, 0.51, 100.0, 100.0, 0.50, 0.0, 0.0, 450.0)
        val longYes = HftMathEngines.dosQuote(0.49, 0.51, 100.0, 100.0, 0.50, 10.0, 0.0, 450.0)
        assertTrue(longYes.reservation < flat.reservation)
        val ofi = HftSignals.ofi(0.49, 10.0, 0.51, 10.0, 0.49, 25.0, 0.51, 10.0)
        assertTrue(ofi > 0.0)
        val slip = HftMathEngines.bookWalkSlippage(listOf(0.40 to 5.0, 0.43 to 20.0), 10.0)
        assertTrue(slip > 0.0)
        assertTrue(HftMathEngines.evYes(0.50, 0.40) - slip < HftMathEngines.evYes(0.50, 0.40))
    }

    @Test fun unlimitedDefaultsDoNotInventAWindowCap() {
        assertEquals(0, ScalpStrategySettings().maxOpenPerSide)
        assertEquals(0, ScalpStrategySettings(maxOpenPerSide = 0).normalized().maxOpenPerSide)
        assertEquals(7, ScalpStrategySettings(maxOpenPerSide = 7).normalized().maxOpenPerSide)
        assertEquals(0.0, Research.Params().windowCapUsd, 0.0)
        assertEquals(0.0, Research.Params(windowCapUsd = 0.0).normalized().windowCapUsd, 0.0)
        assertEquals(250.0, Research.Params(windowCapUsd = 250.0).normalized().windowCapUsd, 0.0)
        val sized = Research.kellyContracts(0.70, 0.40, 0.25, 1_000.0, 1_000.0)
        assertTrue(sized > 0)
        assertEquals(0, Research.kellyContracts(0.70, 0.40, 0.25, 1_000.0, 0.0))
    }

    private fun assertNotNullStop(reason: String?) {
        assertTrue(reason!!.startsWith("stop"))
    }
}
