package com.dirk.kalshiodds.domain.strategy

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportEdgesTest {

    private val closeMs = 1_000_000_000L

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

    private fun flat(n: Int = 80, price: Double = 100.0): List<Candle> =
        candles(List(n) { price })

    private fun atUtc(hour: Int, minute: Int = 0): Long =
        Instant.parse("2026-10-08T00:00:00Z").toEpochMilli() + (hour * 60 + minute) * 60_000L

    // ---- WindowRegime ----

    @Test
    fun `funding hours detected at 00 08 16 UTC`() {
        assertTrue(WindowRegime.isFundingHour(atUtc(0, 30)))
        assertTrue(WindowRegime.isFundingHour(atUtc(8, 15)))
        assertTrue(WindowRegime.isFundingHour(atUtc(16, 59)))
        assertTrue(!WindowRegime.isFundingHour(atUtc(12, 0)))
    }

    @Test
    fun `macro window covers 830 ET in both DST offsets`() {
        assertTrue(WindowRegime.isMacroWindow(atUtc(13, 30)))
        assertTrue(WindowRegime.isMacroWindow(atUtc(12, 35)))
        assertTrue(!WindowRegime.isMacroWindow(atUtc(6, 0)))
    }

    @Test
    fun `liquidity trough at 21 UTC and quality multiplier ordering`() {
        assertTrue(WindowRegime.isLiquidityTrough(atUtc(21, 10)))
        assertTrue(WindowRegime.qualityMultiplier(atUtc(8, 0)) <
            WindowRegime.qualityMultiplier(atUtc(12, 0)))
    }

    // ---- SignReversalTilt ----

    @Test
    fun `reversal fades prior up candle with size-scaled confidence`() {
        val base = flat().toMutableList()
        val last = base.last()
        base[base.size - 1] = last.copy(open = last.open - 5.0) // big up candle
        val ctx = WindowContext(nowMs = atUtc(12), windowCloseMs = closeMs)
        val sig = SignReversalTilt.evaluate(base, ctx)
        assertNotNull(sig)
        assertEquals(Direction.DOWN, sig!!.direction)
        assertTrue(sig.confidence in 0.50..0.535)
        assertEquals("Reversal", sig.strategy)
    }

    @Test
    fun `reversal suppressed during funding hours`() {
        val base = flat().toMutableList()
        val last = base.last()
        base[base.size - 1] = last.copy(open = last.open - 5.0)
        val ctx = WindowContext(nowMs = atUtc(8), windowCloseMs = closeMs)
        assertNull(SignReversalTilt.evaluate(base, ctx))
    }

    // ---- SettlementAverage ----

    @Test
    fun `locked settlement above reference makes down worthless`() {
        // 45 of 60 samples locked well above the reference; market still pays 40¢ for UP.
        val ctx = WindowContext(
            nowMs = closeMs - 30_000L,
            windowCloseMs = closeMs,
            marketUpPriceCents = 40,
            settlement = SettlementContext(
                openReference = 100_000.0,
                samples = List(45) { 100_050.0 }
            )
        )
        val sig = SettlementAverage.evaluate(flat(), ctx)
        assertNotNull(sig)
        assertEquals(Direction.UP, sig!!.direction)
        assertTrue(sig.rationale.contains("75%"))
    }

    @Test
    fun `locked settlement below reference makes up worthless`() {
        val ctx = WindowContext(
            nowMs = closeMs - 10_000L,
            windowCloseMs = closeMs,
            marketUpPriceCents = 60,
            settlement = SettlementContext(
                openReference = 100_000.0,
                samples = List(50) { 99_950.0 }
            )
        )
        val sig = SettlementAverage.evaluate(flat(), ctx)
        assertNotNull(sig)
        assertEquals(Direction.DOWN, sig!!.direction)
    }

    @Test
    fun `settlement strategy inactive without samples or outside final window`() {
        val noFeed = WindowContext(nowMs = closeMs - 30_000L, windowCloseMs = closeMs)
        assertNull(SettlementAverage.evaluate(flat(), noFeed))
        val early = WindowContext(
            nowMs = closeMs - 5 * 60_000L,
            windowCloseMs = closeMs,
            marketUpPriceCents = 40,
            settlement = SettlementContext(100_000.0, List(45) { 100_050.0 })
        )
        assertNull(SettlementAverage.evaluate(flat(), early))
    }

    // ---- BoundaryFade ----

    @Test
    fun `opening burst at boundary is faded`() {
        val base = flat(price = 100.0).toMutableList()
        val last = base.last()
        // ATR on flat candles is ~0.02; make an opening burst far larger.
        base[base.size - 1] = last.copy(close = last.open + 1.0, high = last.open + 1.01)
        val windowStart = closeMs - Strategy.WINDOW_MS
        val ctx = WindowContext(nowMs = windowStart + 60_000L, windowCloseMs = closeMs)
        val sig = BoundaryFade.evaluate(base, ctx)
        assertNotNull(sig)
        assertEquals(Direction.DOWN, sig!!.direction)
    }

    @Test
    fun `boundary fade inactive after early window`() {
        val base = flat(price = 100.0).toMutableList()
        val last = base.last()
        base[base.size - 1] = last.copy(close = last.open + 1.0, high = last.open + 1.01)
        val ctx = WindowContext(nowMs = closeMs - 5 * 60_000L, windowCloseMs = closeMs)
        assertNull(BoundaryFade.evaluate(base, ctx))
    }

    // ---- Engine registration ----

    @Test
    fun `engine default set includes report-edge strategies`() {
        val names = StrategyEngine().strategies.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Reversal", "Settlement", "Boundary")))
        assertEquals(7, names.size)
    }
}
