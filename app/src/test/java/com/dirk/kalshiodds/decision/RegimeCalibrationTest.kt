package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.DecisionTestData.BTC_FINAL
import com.dirk.kalshiodds.decision.DecisionTestData.BTC_MID
import com.dirk.kalshiodds.decision.DecisionTestData.BTC_MID2
import com.dirk.kalshiodds.decision.DecisionTestData.ETH_MID
import com.dirk.kalshiodds.decision.DecisionTestData.samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegimeCalibrationTest {

    @Test
    fun pavIsMonotoneAndKeepsEmpiricalRates() {
        val pairs = listOf(0.1 to 0.0, 0.2 to 1.0, 0.3 to 0.0, 0.4 to 1.0, 0.9 to 1.0)
        val knots = PavIsotonic.fit(pairs)
        for (i in 1 until knots.size) assertTrue(knots[i].y >= knots[i - 1].y)
        assertEquals(0.0, knots.first().y, 1e-12)
        assertEquals(1.0, knots.last().y, 1e-12)
        assertEquals(0.0, PavIsotonic.apply(0.01, knots), 1e-12)
    }

    @Test
    fun hierarchicalFallbackRegimeThenCoinThenGlobal() {
        val rows = samples(BTC_MID, 160, seed = 1) + samples(BTC_MID2, 250, seed = 2, startMs = 500_000L)
        val model = RegimeCalibration.fit(rows, nowMs = 1L)
        // Regime has ≥150 rows → regime level.
        assertEquals(RegimeCalibration.Level.REGIME, model.apply(0.6, BTC_MID)!!.level)
        // Unseen BTC regime (not final window) → coin (410 ≥ 200).
        val unseen = BTC_MID.copy(distance = RegimeCalibration.Distance.BELOW)
        assertEquals(RegimeCalibration.Level.COIN, model.apply(0.6, unseen)!!.level)
        // ETH has no rows → global (410 ≥ 300).
        assertEquals(RegimeCalibration.Level.GLOBAL, model.apply(0.6, ETH_MID)!!.level)
    }

    @Test
    fun finalWindowNeverFallsBack() {
        val rows = samples(BTC_MID, 400, seed = 3)
        val model = RegimeCalibration.fit(rows)
        assertNull("final window must not use coin/global", model.apply(0.6, BTC_FINAL))
        assertFalse(model.finalWindowReady(BTC_FINAL))
        val withFinal = RegimeCalibration.fit(rows + samples(BTC_FINAL, 200, seed = 4, startMs = 900_000L))
        assertNotNull(withFinal.apply(0.6, BTC_FINAL))
        assertTrue(withFinal.finalWindowReady(BTC_FINAL))
    }

    @Test
    fun coldStartHasNoCalibration() {
        val model = RegimeCalibration.fit(samples(BTC_MID, 20))
        assertNull(model.apply(0.6, BTC_MID))
        assertEquals(UncertaintyGate.COLD_START_HALF_WIDTH, UncertaintyGate.halfWidth(Double.NaN, 0), 1e-12)
    }

    @Test
    fun reportIsOutOfFoldAndComparesToMarket() {
        val model = RegimeCalibration.fit(samples(BTC_MID, 600, seed = 5))
        val r = model.report(BTC_MID.regimeId)!!
        assertNotNull(r.modelBrier)
        assertNotNull(r.marketBrier)
        assertNotNull(r.modelLogLoss)
        assertNotNull(r.marketLogLoss)
        assertTrue(r.reliability.isNotEmpty())
        assertTrue("informative model should beat a 50% market", r.modelBrier!! < r.marketBrier!!)
        assertTrue(r.approved)
        // Market as good as the truth → model is not better → not approved.
        val sharpMarket = RegimeCalibration.fit(samples(BTC_MID, 600, seed = 5, marketP = null))
        val r2 = sharpMarket.report(BTC_MID.regimeId)!!
        assertTrue(r2.ready)
        assertFalse(r2.approved)
    }

    @Test
    fun fitIsDeterministic() {
        val rows = samples(BTC_MID, 300, seed = 9)
        val a = RegimeCalibration.fit(rows, 5L)
        val b = RegimeCalibration.fit(rows.reversed(), 5L)
        assertEquals(a.maps, b.maps)
        assertEquals(a.report(BTC_MID.regimeId), b.report(BTC_MID.regimeId))
    }

    @Test
    fun keysFollowSeriesTimeAndDistance() {
        val k = RegimeCalibration.keyOf("KXETHD", 45.0, 2.0)
        assertEquals("ETH", k.coin)
        assertEquals("daily", k.horizon)
        assertTrue(k.finalWindow)
        assertEquals(RegimeCalibration.Distance.DEEP_ABOVE, k.distance)
        assertEquals("15m", RegimeCalibration.keyOf("KXSOL15M", 400.0, 0.1).horizon)
    }

    @Test
    fun marketPriorCorrectionIsBounded() {
        assertEquals(0.58, MarketPrior.finalProbability(0.50, 0.90, MarketPrior.CAP), 1e-12)
        assertEquals(0.48, MarketPrior.finalProbability(0.50, 0.10, MarketPrior.CAP_UNAPPROVED), 1e-12)
        assertEquals(0.53, MarketPrior.finalProbability(0.50, 0.53, MarketPrior.CAP), 1e-12)
        assertEquals(0.50, MarketPrior.finalProbability(0.50, null, MarketPrior.CAP), 1e-12)
    }
}
