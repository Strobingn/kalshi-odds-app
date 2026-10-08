package com.dirk.kalshiodds.domain.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategiesTest {

    private val closeMs = 1_000_000_000L
    private val ctx = WindowContext(nowMs = closeMs - 10 * 60_000L, windowCloseMs = closeMs)

    private fun candles(closes: List<Double>, volume: Double = 100.0): List<Candle> {
        var prev = closes.first()
        val startMs = closeMs - closes.size * Strategy.WINDOW_MS
        return closes.mapIndexed { i, c ->
            Candle(
                openTimeMs = startMs + i * Strategy.WINDOW_MS,
                open = prev,
                high = maxOf(prev, c) + 0.01,
                low = minOf(prev, c) - 0.01,
                close = c,
                volume = volume
            ).also { prev = c }
        }
    }

    /** Accelerating 80-bar rise: every trend gauge bullish, ADX ~100. */
    private fun strongUptrend(): List<Candle> =
        candles((0 until 80).map { 100.0 * Math.pow(1.01, it.toDouble()) })

    /** Aimless sine chop: split votes and weak ADX, no consensus. */
    private fun choppy(): List<Candle> =
        candles((0 until 80).map { 100.0 + 2.0 * kotlin.math.sin(it.toDouble()) })

    /** Flat base then a sharp spike: RSI 100, close far above the upper band. */
    private fun overboughtSpike(): List<Candle> =
        candles(List(65) { 100.0 } + (1..7).map { 100.0 + 2.0 * it })

    /**
     * Wide chop then a tight flat drift: bandwidth at its 20-period minimum on
     * the last bar while the MACD histogram flips negative and OBV rises.
     */
    private fun squeezeThenFlipDown(): List<Candle> {
        val closes = ArrayList<Double>()
        closes.add(100.0)
        for (i in 0 until 39) closes.add(closes.last() + if (i % 2 == 0) 0.8 else -0.8)
        for (i in 0 until 23) closes.add(closes.last() + 0.02)
        return candles(closes)
    }

    @Test
    fun warmupNullsBelow60Candles() {
        val few = strongUptrend().take(40)
        assertNull(ConsensusTrend.evaluate(few, ctx))
        assertNull(MeanReversionFade.evaluate(few, ctx))
        assertNull(SqueezeBreakout.evaluate(few, ctx))
        assertNull(
            LateWindowDislocation.evaluate(
                few,
                ctx.copy(nowMs = closeMs - 60_000L, marketUpPriceCents = 50)
            )
        )
    }

    @Test
    fun consensusTrendFiresOnStrongUptrend() {
        val signal = ConsensusTrend.evaluate(strongUptrend(), ctx)
        assertEquals(Direction.UP, signal?.direction)
        assertEquals("Trend", signal?.strategy)
        assertTrue("confidence=${signal?.confidence}", (signal?.confidence ?: 0.0) >= 5.0 / 6.0)
    }

    @Test
    fun consensusTrendSitsOutOnChop() {
        assertNull(ConsensusTrend.evaluate(choppy(), ctx))
    }

    @Test
    fun meanReversionFadeFadesSpike() {
        val signal = MeanReversionFade.evaluate(overboughtSpike(), ctx)
        assertEquals(Direction.DOWN, signal?.direction)
        assertEquals("Fade", signal?.strategy)
        assertTrue("confidence=${signal?.confidence}", (signal?.confidence ?: 0.0) >= 0.55)
        assertTrue(signal?.rationale?.contains("upper band") == true)
    }

    @Test
    fun meanReversionFadeSitsOutAtRsi50() {
        assertNull(MeanReversionFade.evaluate(candles(List(70) { 100.0 }), ctx))
    }

    @Test
    fun squeezeBreakoutFiresOnFlipBar() {
        val signal = SqueezeBreakout.evaluate(squeezeThenFlipDown(), ctx)
        assertEquals(Direction.DOWN, signal?.direction)
        assertEquals("Squeeze", signal?.strategy)
    }

    @Test
    fun squeezeBreakoutIsOneBarOnly() {
        // One more candle in the same drift: the flip is no longer on the
        // latest bar, so the strategy sits out.
        val base = squeezeThenFlipDown()
        val extended = base + Candle(
            openTimeMs = base.last().openTimeMs + Strategy.WINDOW_MS,
            open = base.last().close,
            high = base.last().close + 0.03,
            low = base.last().close - 0.01,
            close = base.last().close + 0.02,
            volume = 100.0
        )
        assertNull(SqueezeBreakout.evaluate(extended, ctx))
    }

    @Test
    fun dislocationSitsOutBeforeLast3Minutes() {
        // > 3 minutes left (ctx default has 10 minutes left).
        assertNull(
            LateWindowDislocation.evaluate(
                strongUptrend(),
                ctx.copy(marketUpPriceCents = 50)
            )
        )
    }

    @Test
    fun dislocationNullWithoutMarketPrice() {
        assertNull(
            LateWindowDislocation.evaluate(
                strongUptrend(),
                ctx.copy(nowMs = closeMs - 60_000L, marketUpPriceCents = null)
            )
        )
    }

    @Test
    fun dislocationBuysUnderpricedUp() {
        // Spot rallied within the window: fair P(UP) near 100c, market at 50c.
        val signal = LateWindowDislocation.evaluate(
            strongUptrend(),
            ctx.copy(nowMs = closeMs - 60_000L, marketUpPriceCents = 50)
        )
        assertEquals(Direction.UP, signal?.direction)
        assertEquals("Dislocation", signal?.strategy)
        assertTrue(signal?.rationale?.contains("gap") == true)
    }

    @Test
    fun dislocationSitsOutWhenMarketMatchesFair() {
        // Flat series: spot == window open, fair = 50c, market = 50c, no gap.
        assertNull(
            LateWindowDislocation.evaluate(
                candles(List(70) { 100.0 }),
                ctx.copy(nowMs = closeMs - 60_000L, marketUpPriceCents = 50)
            )
        )
    }
}
