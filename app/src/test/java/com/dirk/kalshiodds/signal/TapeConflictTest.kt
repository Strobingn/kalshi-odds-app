package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.engine.TapeConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapeConflictTest {

    @Test
    fun risingTapeConflictsWithDownCall() {
        val out = TapeConflict.evaluate(
            spotReturn1m = 0.002,
            spotReturn5m = 0.004,
            modelSide = "NO"
        )
        assertTrue(out.conflict)
        assertEquals(TapeConflict.Trend.UP, out.trend)
        assertEquals("YES", out.primarySide)
        assertEquals("NO", out.modelSide)
        assertEquals("AI says DOWN, but live chart shows UP", out.banner)
    }

    @Test
    fun fallingTapeConflictsWithUpCall() {
        val out = TapeConflict.evaluate(
            spotReturn1m = -0.002,
            spotReturn5m = -0.004,
            modelSide = "YES"
        )
        assertTrue(out.conflict)
        assertEquals(TapeConflict.Trend.DOWN, out.trend)
        assertEquals("NO", out.primarySide)
        assertEquals("AI says UP, but live chart shows DOWN", out.banner)
    }

    @Test
    fun alignedTapeIsNotAConflict() {
        val out = TapeConflict.evaluate(
            spotReturn1m = 0.002,
            spotReturn5m = 0.003,
            modelSide = "YES"
        )
        assertFalse(out.conflict)
        assertEquals("YES", out.primarySide)
        assertEquals(null, out.banner)
    }

    @Test
    fun tinyMoveIsFlat() {
        val out = TapeConflict.evaluate(
            spotReturn1m = 0.0001,
            spotReturn5m = 0.0002,
            modelSide = "NO"
        )
        assertEquals(TapeConflict.Trend.FLAT, out.trend)
        assertFalse(out.conflict)
        assertEquals("NO", out.primarySide)
    }

    @Test
    fun oneMinuteReversalIsFlat() {
        val out = TapeConflict.evaluate(
            spotReturn1m = -0.002,
            spotReturn5m = 0.003,
            modelSide = "NO"
        )
        assertEquals(TapeConflict.Trend.FLAT, out.trend)
        assertFalse(out.conflict)
    }
}
