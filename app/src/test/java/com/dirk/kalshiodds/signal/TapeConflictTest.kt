package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.engine.TapeConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapeConflictTest {

    @Test
    fun primaryFollowsSpotAndMarketNotSparkline() {
        val out = TapeConflict.evaluate(
            spotReturn1m = -0.05,
            spotReturn5m = -0.08,
            modelSide = "NO",
            yesAsk = 0.64,
            noAsk = 0.37,
            spotUsd = 84_311.0,
            strikeUsd = 84_278.0,
            fairYes = 0.62,
            previousPrimary = "YES",
            priorStreak = 0
        )
        assertEquals("YES", out.primarySide)
        assertFalse(out.conflict)
        assertEquals(null, out.banner)
    }

    @Test
    fun singleDisagreementDoesNotBanner() {
        val out = TapeConflict.evaluate(
            spotReturn1m = 0.002,
            spotReturn5m = 0.004,
            modelSide = "NO",
            yesAsk = 0.64,
            noAsk = 0.37,
            spotUsd = 84_311.0,
            strikeUsd = 84_278.0,
            priorStreak = 0
        )
        assertEquals("YES", out.primarySide)
        assertEquals("NO", out.modelSide)
        assertFalse(out.conflict)
        assertEquals(1, out.disagreementStreak)
    }

    @Test
    fun sustainedDisagreementShowsBanner() {
        val out = TapeConflict.evaluate(
            spotReturn1m = 0.002,
            spotReturn5m = 0.004,
            modelSide = "NO",
            yesAsk = 0.64,
            noAsk = 0.37,
            spotUsd = 84_311.0,
            strikeUsd = 84_278.0,
            priorStreak = TapeConflict.SUSTAINED_STREAK - 1
        )
        assertTrue(out.conflict)
        assertTrue(out.banner!!.contains("AI says DOWN"))
        assertTrue(out.banner!!.contains("UP"))
    }

    @Test
    fun zeroAskDoesNotFlipPreviousPrimary() {
        val out = TapeConflict.evaluate(
            spotReturn1m = -0.01,
            spotReturn5m = -0.02,
            modelSide = "YES",
            yesAsk = 0.0,
            noAsk = 1.0,
            previousPrimary = "YES"
        )
        assertEquals("YES", out.primarySide)
    }

    @Test
    fun singleOneSidedDumpDoesNotFlipPreviousPrimary() {
        val out = TapeConflict.evaluate(
            spotReturn1m = -0.01,
            spotReturn5m = -0.02,
            modelSide = "YES",
            yesAsk = 0.05,
            noAsk = 0.96,
            fairYes = 0.71,
            previousPrimary = "YES"
        )
        assertEquals("YES", out.primarySide)
        assertFalse(out.conflict)
    }

    @Test
    fun screenshotMarketIsYesNotSparklineDown() {
        val primary = TapeConflict.primaryFromMarket(
            yesAsk = 0.64,
            noAsk = 0.37,
            spotUsd = 84_311.58,
            strikeUsd = 84_278.84,
            fairYes = 0.73,
            previousPrimary = "NO"
        )
        assertEquals("YES", primary)
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
