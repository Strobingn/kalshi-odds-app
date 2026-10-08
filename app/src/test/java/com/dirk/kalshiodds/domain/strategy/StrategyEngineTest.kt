package com.dirk.kalshiodds.domain.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {

    private fun signal(direction: Direction, confidence: Double = 0.6, strategy: String = "T") =
        StrategySignal(strategy, direction, confidence, "rationale", 0L)

    @Test
    fun majorityUpWins() {
        val engine = StrategyEngine()
        val signals = listOf(
            signal(Direction.UP, strategy = "A"),
            signal(Direction.UP, strategy = "B"),
            signal(Direction.DOWN, strategy = "C")
        )
        assertEquals(Direction.UP, engine.combinedDirection(signals))
    }

    @Test
    fun majorityDownWins() {
        val engine = StrategyEngine()
        val signals = listOf(
            signal(Direction.DOWN, strategy = "A"),
            signal(Direction.DOWN, strategy = "B"),
            signal(Direction.UP, strategy = "C")
        )
        assertEquals(Direction.DOWN, engine.combinedDirection(signals))
    }

    @Test
    fun tieIsNone() {
        val engine = StrategyEngine()
        val signals = listOf(
            signal(Direction.UP, strategy = "A"),
            signal(Direction.DOWN, strategy = "B")
        )
        assertEquals(Direction.NONE, engine.combinedDirection(signals))
    }

    @Test
    fun emptyAndAllNoneAreNone() {
        val engine = StrategyEngine()
        assertEquals(Direction.NONE, engine.combinedDirection(emptyList()))
        assertEquals(
            Direction.NONE,
            engine.combinedDirection(listOf(signal(Direction.NONE, strategy = "A")))
        )
    }

    @Test
    fun topSignalPicksHighestConfidence() {
        val engine = StrategyEngine()
        val top = engine.topSignal(
            listOf(
                signal(Direction.UP, confidence = 0.55, strategy = "A"),
                signal(Direction.DOWN, confidence = 0.7, strategy = "B")
            )
        )
        assertEquals("B", top?.strategy)
    }

    @Test
    fun topSignalNullWhenEmpty() {
        assertNull(StrategyEngine().topSignal(emptyList()))
    }

    @Test
    fun evaluateAllDropsSitOutsAndSurvivesBadData() {
        val engine = StrategyEngine()
        // Far below warm-up: every strategy sits out, nothing throws.
        val candles = listOf(Candle(0L, 1.0, 1.1, 0.9, 1.0, 10.0))
        val ctx = WindowContext(nowMs = 0L, windowCloseMs = Strategy.WINDOW_MS, marketUpPriceCents = 50)
        assertTrue(engine.evaluateAll(candles, ctx).isEmpty())
    }
}
