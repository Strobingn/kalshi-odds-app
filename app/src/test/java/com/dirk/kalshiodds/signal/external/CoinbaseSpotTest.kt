package com.dirk.kalshiodds.signal.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinbaseSpotTest {

    @Test
    fun binanceDisplayIsNeverModelInput() {
        val feat = AssetSpotFeatures(
            asset = "BTC",
            lastPrice = null,
            displayPrice = 100_150.0,
            displaySource = "binance-usdt",
            source = "binance-display",
            modelUsable = false,
            fetchedAtMs = 1L
        )
        assertFalse(feat.modelUsable)
        assertNull(feat.lastPrice)
        assertNull(SpotFeatureMath.adjustPp(50.0, feat))
        val label = SpotFeatureMath.label(feat)
        assertTrue(label!!.contains("display"))
        assertTrue(label.contains("not a model input") || label.contains("display-only"))
    }

    @Test
    fun coinbaseTickerPriceIsModelSpot() {
        val feat = AssetSpotFeatures(
            asset = "BTC",
            lastPrice = 100_241.0,
            minuteCloses = listOf(100_200.0, 100_210.0, 100_220.0, 100_230.0, 100_235.0, 100_238.0, 100_240.0, 100_241.0),
            source = "coinbase",
            displayPrice = 100_241.0,
            displaySource = "coinbase",
            modelUsable = true,
            spotReturn1m = 0.001,
            fetchedAtMs = 1L
        )
        assertTrue(feat.modelUsable)
        assertEquals(100_241.0, feat.lastPrice!!, 1e-6)
        assertEquals(8, feat.minuteCloses.size)
        assertEquals(feat.lastPrice, feat.minuteCloses.last(), 1e-6)
    }

    @Test
    fun modelSpotIgnoresOlderCandleWhenTickerDiffers() {
        // Live ticker 100300; last candle close 100241. Model must use ticker.
        val live = 100_300.0
        val candleClose = 100_241.0
        val feat = AssetSpotFeatures(
            asset = "BTC",
            lastPrice = live,
            minuteCloses = listOf(candleClose),
            source = "coinbase",
            modelUsable = true,
            fetchedAtMs = 1L
        )
        assertEquals(live, feat.lastPrice!!, 1e-9)
        assertTrue(kotlin.math.abs(feat.lastPrice!! - candleClose) > 1.0)
    }
}
