package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.ws.KalshiWsMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spot features from Kalshi's CF index stream alone: bars, returns, σ, and the poll that uses them. */
class KalshiSpotTest {

    private fun tick(id: String, v: Double) = KalshiWsMessages.Parsed.IndexValue(id, v, null, null, null, null, null, null)

    /** [minutes] of one print a second; each minute's price is 100,000 × 1.001^minute. */
    private fun fed(minutes: Int): CfIndexBook {
        val cf = CfIndexBook()
        for (s in 0 until minutes * 60) cf.onUpdate(tick("BRTI", 100_000.0 * Math.pow(1.001, (s / 60).toDouble())), s * 1000L)
        return cf
    }

    @Test
    fun minuteBarsAreTheLastPrintOfEachFinishedMinuteThenTheLatest() {
        val cf = fed(5)
        val closes = cf.minuteCloses("BTC", 5 * 60_000L - 500L)
        // Minutes 0–3 finished, minute 4 forming (its latest value appended).
        assertEquals(5, closes.size)
        assertEquals(100_000.0, closes[0], 1e-6)
        assertEquals(100_000.0 * Math.pow(1.001, 3.0), closes[3], 1e-6)
        assertEquals(closes[4], 100_000.0 * Math.pow(1.001, 4.0), 1e-6)
        assertTrue(cf.minuteCloses("ETH", 0L).isEmpty())
    }

    @Test
    fun featuresMatchThePollsUnitsAndSayTheyCameFromKalshi() {
        val now = 70 * 60_000L - 500L
        val cf = fed(70)
        val f = KalshiSpot.features(cf, "BTC", now)!!
        assertEquals(KalshiSpot.SOURCE, f.source)
        assertEquals(100_000.0 * Math.pow(1.001, 69.0), f.lastPrice!!, 1e-6)
        assertEquals("one bar up 0.1%", 0.001, f.spotReturn1m!!, 1e-9)
        assertEquals(Math.pow(1.001, 5.0) - 1.0, f.spotReturn5m!!, 1e-9)
        // Every 1-minute log return is the same, so the EWMA σ is ~0.
        assertTrue(f.trainerVol1m!! < 1e-9)
        assertEquals(f.trainerVol1m, f.realizedVol15m)
        assertNull("Kalshi has no funding rate", f.fundingRate)
        assertNull("stale index: nothing", KalshiSpot.features(cf, "BTC", now + 60_000L))
        val snap = KalshiSpot.snapshot(cf, now)
        assertNotNull(snap.btc)
        assertNull(snap.eth)
    }

    @Test
    fun thePollTakesKalshisSnapshotWhenItAnswers() {
        val cf = fed(10)
        val now = 10 * 60_000L - 500L
        val cache = ExternalMarketCache(kalshi = { t -> KalshiSpot.snapshot(cf, t) })
        assertEquals(KalshiSpot.SOURCE, cache.refreshIfStale(now).btc!!.source)
        assertEquals(KalshiSpot.SOURCE, cache.latest().btc!!.source)
    }
}
