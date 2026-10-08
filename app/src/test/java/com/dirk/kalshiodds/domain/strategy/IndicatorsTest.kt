package com.dirk.kalshiodds.domain.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {

    private fun candles(closes: List<Double>, volume: Double = 100.0): List<Candle> {
        var prev = closes.first()
        return closes.mapIndexed { i, c ->
            Candle(
                openTimeMs = i * 900_000L,
                open = prev,
                high = maxOf(prev, c) + 0.01,
                low = minOf(prev, c) - 0.01,
                close = c,
                volume = volume
            ).also { prev = c }
        }
    }

    @Test
    fun smaKnownValuesAndWarmup() {
        val out = Indicators.sma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), out)
    }

    @Test
    fun emaSeedsWithSma() {
        val values = (1..20).map { it.toDouble() }
        val out = Indicators.ema(values, 10)
        assertNull(out[8])
        assertEquals(5.5, out[9]!!, 1e-9)
        // k = 2/11; next = 11 * k + 5.5 * (1 - k)
        val k = 2.0 / 11.0
        assertEquals(11.0 * k + 5.5 * (1 - k), out[10]!!, 1e-9)
    }

    @Test
    fun rsiMonotonicRiseIs100() {
        val closes = (0..30).map { 100.0 + it }
        val out = Indicators.rsi(closes)
        assertNull(out[13])
        assertEquals(100.0, out[14]!!, 1e-9)
        assertEquals(100.0, out.last()!!, 1e-9)
    }

    @Test
    fun rsiFlatSeriesIs50() {
        val out = Indicators.rsi(List(30) { 50.0 })
        assertEquals(50.0, out.last()!!, 1e-9)
    }

    @Test
    fun bollingerConstantSeriesHasZeroWidth() {
        val closes = List(30) { 42.0 }
        val bands = Indicators.bollinger(closes)
        assertNull(bands[18])
        val band = bands[19]!!
        assertEquals(42.0, band.first!!, 1e-9)
        assertEquals(42.0, band.second!!, 1e-9)
        assertEquals(42.0, band.third!!, 1e-9)
        assertEquals(0.0, Indicators.bollingerBandwidth(closes)[19]!!, 1e-9)
    }

    @Test
    fun bollingerKnownValues() {
        // 1..20: mean 10.5, population sd = sqrt(33.25)
        val closes = (1..20).map { it.toDouble() }
        val band = Indicators.bollinger(closes).last()!!
        val sd = kotlin.math.sqrt(33.25)
        assertEquals(10.5, band.second!!, 1e-9)
        assertEquals(10.5 + 2 * sd, band.first!!, 1e-9)
        assertEquals(10.5 - 2 * sd, band.third!!, 1e-9)
    }

    @Test
    fun macdWarmupNullsAndFlip() {
        val closes = (0..60).map { 100.0 + it }
        val out = Indicators.macd(closes)
        assertNull(out[32])
        assertNotNull(out[33])
        // Monotonic rise: line above zero, histogram = line - signal present.
        val last = out.last()!!
        assertTrue(last.line > 0.0)
        assertEquals(last.line - last.signal, last.histogram, 1e-9)
    }

    @Test
    fun adxFlatSeriesHandled() {
        val flat = candles(List(40) { 100.0 })
        val out = Indicators.adx(flat)
        // Warm-up nulls, then ADX defined and ~0 (no directional movement).
        assertNull(out[26])
        val last = out.last()
        assertNotNull(last)
        assertEquals(0.0, last!!.adx, 1e-9)
    }

    @Test
    fun adxStrongTrendIsHigh() {
        val up = candles((0..60).map { 100.0 + it })
        val last = Indicators.adx(up).last()!!
        assertTrue("adx=${last.adx}", last.adx > 25.0)
        assertTrue(last.plusDI > last.minusDI)
    }

    @Test
    fun atrConstantRange() {
        // Each candle moves +1; high-low span dominates after the first bars.
        val up = candles((0..30).map { 100.0 + it })
        val out = Indicators.atr(up)
        assertNull(out[13])
        assertNotNull(out[14])
        assertTrue(out.last()!! > 0.0)
    }

    @Test
    fun obvFollowsCloseDirection() {
        val cd = candles(listOf(10.0, 11.0, 10.5, 12.0), volume = 5.0)
        val out = Indicators.obv(cd)
        assertEquals(listOf(0.0, 5.0, 0.0, 5.0), out)
    }

    @Test
    fun vwapConstantSeriesEqualsPrice() {
        val cd = candles(List(30) { 77.0 })
        val out = Indicators.vwap(cd)
        assertNull(out[18])
        assertEquals(77.0, out[19]!!, 1e-9)
    }
}
