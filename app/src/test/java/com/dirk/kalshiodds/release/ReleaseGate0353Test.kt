package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ForecastScalp
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ShortHorizonForecaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0353Test {
    @Test fun eightModelsEighthSlices() {
        assertEquals(8, ScalpModels.ALL.size)
        assertEquals(2_500.0, ScalpModels.sliceUsd(20_000.0), 1e-9)
    }

    @Test fun forecasterDeterministicAndReadyAtOpen() {
        val s = ShortHorizonForecaster.Sample(1_000L, 0.5, 100.0, 50.0)
        val x = ShortHorizonForecaster.features(s, emptyList(), 900_000L)
        assertEquals(0.0, x[1], 0.0); assertEquals(0.0, x[2], 0.0) // no history → momentum 0, still predicts
        for (c in ScalpParams.COINS) {
            val h = ShortHorizonForecaster.horizonFor(c)
            assertTrue(h in ShortHorizonForecaster.HORIZONS_S)
            assertNotNull(ShortHorizonForecaster.predict(c, h, x))
            assertEquals(ShortHorizonForecaster.predict(c, h, x), ShortHorizonForecaster.predict(c, h, x))
        }
    }

    @Test fun entryAndExitRules() {
        assertTrue(ForecastScalp.wantsEntry(0.50, 0.01, 0.51, 0.08, 600.0))
        assertTrue(!ForecastScalp.wantsEntry(0.70, 0.01, 0.71, 0.20, 600.0)) // outside band
        assertTrue(!ForecastScalp.wantsEntry(0.50, 0.01, 0.51, 0.03, 600.0)) // edge below spread + both fees
        assertEquals("take profit ≥ 90¢", ForecastScalp.wantsExit(0.05, 0.91, 10.0, 300, 600.0))
        assertEquals("forecast flipped hard", ForecastScalp.wantsExit(-0.05, 0.55, 10.0, 300, 600.0))
        assertEquals("horizon reached", ForecastScalp.wantsExit(0.05, 0.55, 300.0, 300, 600.0))
        assertEquals("exit before close", ForecastScalp.wantsExit(0.05, 0.55, 10.0, 300, 20.0))
        assertNull(ForecastScalp.wantsExit(0.05, 0.55, 10.0, 300, 600.0))
    }

    @Test fun rideAndFadeMirrorInTheBook() {
        val b = ScalpBook(bankrollUsd = { 20_000.0 })
        val close = 1_791_000_900_000L
        val t0 = close - 800_000L
        var n = 0
        // strong imbalance + upward drift so the forecast clears the cost bar for at least one coin/horizon
        for (i in 0 until 200) {
            val now = t0 + i * 3_000L
            val mid = 0.45 + i * 0.0005
            val q = ScalpRule.Quote("KXBTC15M-26OCT101100-00", now, close, now - 10, mid - 0.005, 500.0, mid + 0.005, 500.0, 100.0, 100.0, 0.0005)
            b.onForecastQuote(q, 5_000.0, 10.0, enabled = true, nowHorizonS = 480)
            n++
        }
        val fc = b.snapshot().filter { ForecastScalp.isForecast(it) }
        val ride = fc.filter { ScalpModels.modelOf(it) == ScalpModels.Model.FORECAST_RIDE }
        val fade = fc.filter { ScalpModels.modelOf(it) == ScalpModels.Model.FORECAST_FADE }
        assertEquals(ride.size, fade.size) // same signals, mirrored
        ride.zip(fade.sortedBy { it.signalAtMs }.let { f -> f }).forEach { (r, f) -> assertTrue(r.side != f.side) }
        assertTrue(fc.all { it.state != ScalpState.CLOSED || it.entryFeeUsd > 0.0 }) // fees on every fill
        assertTrue(n == 200)
    }
}
