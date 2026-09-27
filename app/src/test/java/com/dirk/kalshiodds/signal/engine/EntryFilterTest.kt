package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryFilterTest {

    private val defaults = SignalSettings()

    /** Spot 24bp above the strike — rule (b) never fires. */
    private fun farFromStrike(tte: Long?, settings: SignalSettings = defaults, edge: Double? = 1.0) =
        EntryFilter.evaluate(
            tteSeconds = tte,
            spotUsd = 100_241.0,
            strikeUsd = 100_000.0,
            netEdgePp = edge,
            settings = settings
        )

    @Test
    fun defaultsMatchTheBacktestGuard() {
        assertTrue(defaults.entryFilterEnabled)
        assertEquals(3, defaults.entryMinElapsedMinutes)
        assertEquals(5.0, defaults.entryMinStrikeDistanceBp, 1e-12)
        assertEquals(10.0, defaults.entryNearStrikeOverridePp, 1e-12)
        assertEquals(900L, EntryFilter.WINDOW_SECONDS)
    }

    @Test
    fun earlyInTheWindowIsBlockedWithACountdown() {
        val r = farFromStrike(tte = 820L)
        assertFalse(r.passed)
        assertEquals("too early: 13:40 left (wait until 12:00)", r.reason)
        val first = farFromStrike(tte = 899L)
        assertFalse(first.passed)
        assertEquals("too early: 14:59 left (wait until 12:00)", first.reason)
    }

    @Test
    fun exactlyTwelveMinutesLeftPasses() {
        val atBoundary = farFromStrike(tte = 720L)
        assertTrue(atBoundary.passed)
        assertNull(atBoundary.reason)
        val oneSecondEarly = farFromStrike(tte = 721L)
        assertFalse(oneSecondEarly.passed)
        assertEquals("too early: 12:01 left (wait until 12:00)", oneSecondEarly.reason)
        assertTrue(farFromStrike(tte = 600L).passed)
        assertTrue(farFromStrike(tte = 30L).passed)
    }

    @Test
    fun customElapsedMinutesMovesTheBoundary() {
        val five = defaults.copy(entryMinElapsedMinutes = 5)
        assertEquals("too early: 11:00 left (wait until 10:00)", farFromStrike(660L, five).reason)
        assertTrue(farFromStrike(600L, five).passed)
        val off = defaults.copy(entryMinElapsedMinutes = 0)
        assertTrue(farFromStrike(899L, off).passed)
        assertNull(farFromStrike(899L, off).reason)
    }

    @Test
    fun nearStrikeIsBlocked() {
        val r = EntryFilter.evaluate(
            tteSeconds = 600L,
            spotUsd = 100_021.0,
            strikeUsd = 100_000.0,
            netEdgePp = 3.0,
            settings = defaults
        )
        assertFalse(r.passed)
        assertEquals("near strike: 2.1bp < 5bp", r.reason)
        assertEquals(2.1, r.distanceBp!!, 1e-9)
        // Below the strike is symmetric.
        val below = EntryFilter.evaluate(600L, 99_979.0, 100_000.0, 3.0, defaults)
        assertFalse(below.passed)
        assertEquals("near strike: 2.1bp < 5bp", below.reason)
        // 6bp clears the 5bp floor.
        val clear = EntryFilter.evaluate(600L, 100_060.0, 100_000.0, 3.0, defaults)
        assertTrue(clear.passed)
        assertNull(clear.reason)
    }

    @Test
    fun largePositiveNetEdgeOverridesNearStrike() {
        val big = EntryFilter.evaluate(600L, 100_021.0, 100_000.0, 12.0, defaults)
        assertTrue(big.passed)
        assertEquals("near strike 2.1bp < 5bp allowed: net edge +12.0pp ≥ 10pp", big.reason)
        val atOverride = EntryFilter.evaluate(600L, 100_021.0, 100_000.0, 10.0, defaults)
        assertTrue(atOverride.passed)
        val justUnder = EntryFilter.evaluate(600L, 100_021.0, 100_000.0, 9.9, defaults)
        assertFalse(justUnder.passed)
        // Signed: a big *negative* edge on the chosen side never unlocks a coin flip.
        val negative = EntryFilter.evaluate(600L, 100_021.0, 100_000.0, -15.0, defaults)
        assertFalse(negative.passed)
        assertEquals("near strike: 2.1bp < 5bp", negative.reason)
        val noEdge = EntryFilter.evaluate(600L, 100_021.0, 100_000.0, null, defaults)
        assertFalse(noEdge.passed)
    }

    @Test
    fun overrideDoesNotBypassTheTimeRule() {
        val r = EntryFilter.evaluate(820L, 100_021.0, 100_000.0, 25.0, defaults)
        assertFalse(r.passed)
        assertEquals("too early: 13:40 left (wait until 12:00)", r.reason)
    }

    @Test
    fun bothRulesJoinReasons() {
        val r = EntryFilter.evaluate(820L, 100_000.0, 100_000.0, 1.0, defaults)
        assertFalse(r.passed)
        assertEquals("too early: 13:40 left (wait until 12:00) · near strike: 0.0bp < 5bp", r.reason)
        assertEquals(0.0, r.distanceBp!!, 1e-12)
    }

    @Test
    fun missingSpotOrStrikeDoesNotBlockButIsNoted() {
        val noSpot = EntryFilter.evaluate(600L, null, 100_000.0, 1.0, defaults)
        assertTrue(noSpot.passed)
        assertEquals("near-strike check skipped: no spot", noSpot.reason)
        assertNull(noSpot.distanceBp)
        val noStrike = EntryFilter.evaluate(600L, 100_000.0, null, 1.0, defaults)
        assertTrue(noStrike.passed)
        assertEquals("near-strike check skipped: no strike", noStrike.reason)
        val neither = EntryFilter.evaluate(600L, null, null, null, defaults)
        assertTrue(neither.passed)
        assertEquals("near-strike check skipped: no spot or strike", neither.reason)
        val badSpot = EntryFilter.evaluate(600L, Double.NaN, 100_000.0, 1.0, defaults)
        assertTrue(badSpot.passed)
        assertEquals("near-strike check skipped: no spot", badSpot.reason)
        // Missing data never unblocks the time rule.
        val earlyNoSpot = EntryFilter.evaluate(820L, null, null, null, defaults)
        assertFalse(earlyNoSpot.passed)
        assertEquals("too early: 13:40 left (wait until 12:00)", earlyNoSpot.reason)
    }

    @Test
    fun missingOrOversizedTimeLeftSkipsTheTimeRule() {
        val noClose = farFromStrike(tte = null)
        assertTrue(noClose.passed)
        assertEquals("entry-time check skipped: no close time", noClose.reason)
        val longer = farFromStrike(tte = 1_790L)
        assertTrue(longer.passed)
        assertEquals("entry-time check skipped: 29:50 left is longer than the 15:00 window", longer.reason)
        assertTrue(farFromStrike(tte = 900L).let { !it.passed && it.reason == "too early: 15:00 left (wait until 12:00)" })
    }

    @Test
    fun zeroStrikeDistanceTurnsTheNearStrikeRuleOff() {
        val off = defaults.copy(entryMinStrikeDistanceBp = 0.0)
        val r = EntryFilter.evaluate(600L, 100_000.0, 100_000.0, 0.0, off)
        assertTrue(r.passed)
        assertNull(r.reason)
        assertNotNull(r.distanceBp)
        val noSpot = EntryFilter.evaluate(600L, null, 100_000.0, 0.0, off)
        assertNull("no note when the rule is off", noSpot.reason)
    }

    @Test
    fun disabledPassesEverything() {
        val off = defaults.copy(entryFilterEnabled = false)
        val r = EntryFilter.evaluate(880L, 100_000.0, 100_000.0, -20.0, off)
        assertTrue(r.passed)
        assertNull(r.reason)
    }

    @Test
    fun clockLabels() {
        assertEquals("13:40", EntryFilter.clock(820L))
        assertEquals("12:00", EntryFilter.clock(720L))
        assertEquals("0:05", EntryFilter.clock(5L))
        assertEquals("12:00", EntryFilter.waitUntilLabel(3))
        assertEquals("15:00", EntryFilter.waitUntilLabel(0))
        assertEquals("10:00", EntryFilter.waitUntilLabel(5))
    }

    /**
     * Same hand-computed cases as tools/backtest/test_parity.py
     * `entry_filter_cases` — keep the two in lockstep.
     */
    @Test
    fun matchesBacktestPortHandCases() {
        data class Case(
            val tte: Long?,
            val spot: Double?,
            val strike: Double?,
            val edge: Double?,
            val passed: Boolean,
            val reason: String?
        )
        val cases = listOf(
            Case(820L, 84_311.0, 84_144.0, 2.0, false, "too early: 13:40 left (wait until 12:00)"),
            Case(720L, 84_311.0, 84_144.0, 2.0, true, null),
            Case(600L, 84_160.0, 84_144.0, 2.0, false, "near strike: 1.9bp < 5bp"),
            Case(600L, 84_160.0, 84_144.0, 11.0, true, "near strike 1.9bp < 5bp allowed: net edge +11.0pp ≥ 10pp"),
            Case(600L, null, 84_144.0, 2.0, true, "near-strike check skipped: no spot"),
            Case(840L, 84_144.0, 84_144.0, 2.0, false, "too early: 14:00 left (wait until 12:00) · near strike: 0.0bp < 5bp")
        )
        for (c in cases) {
            val r = EntryFilter.evaluate(c.tte, c.spot, c.strike, c.edge, defaults)
            assertEquals("$c", c.passed, r.passed)
            assertEquals("$c", c.reason, r.reason)
        }
        val dist = EntryFilter.evaluate(600L, 84_160.0, 84_144.0, 2.0, defaults).distanceBp!!
        assertEquals(16.0 / 84_144.0 * 10_000.0, dist, 1e-12)
    }
}
