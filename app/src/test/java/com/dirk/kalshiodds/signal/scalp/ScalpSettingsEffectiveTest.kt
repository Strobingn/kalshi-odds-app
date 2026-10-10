package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1 lock assertion (the flag is a compile-time constant on this
 * branch) and the Task 2 aggression resolver: aggressive-profile values
 * replace only fields still at their conservative defaults; an explicitly
 * changed stored value always wins; the daily-loss breaker is never relaxed.
 */
class ScalpSettingsEffectiveTest {

    @Test
    fun compileTimeLockIsOffOnThisBranch() {
        assertFalse(scalpLiveTradingEnabled)
    }

    @Test
    fun aggressiveDefaultsReplaceOnlyUntouchedFields() {
        val eff = ScalpSettings().effective() // aggressive = true, all defaults
        assertEquals(60, eff.maxTradesPerHour)
        assertEquals(30, eff.windowSeconds)
        assertEquals(4.0, eff.takeProfitPp, 1e-9)
        assertEquals(6.0, eff.stopLossPp, 1e-9)
        assertEquals(300_000L, eff.maxHoldMs)
        assertEquals(2.0, eff.dipMinDropPp, 1e-9)
        // Untouched fields that now join the aggressive profile.
        assertEquals(10.0, eff.maxStakeUsd, 1e-9)
        // Identical in both modes — never relaxed.
        assertEquals(10.0, eff.maxDailyLossUsd, 1e-9)
        assertEquals(12, eff.maxOpenPositions)
        // All 11 strategies on by default.
        assertTrue(eff.enabledStrategies.isEmpty())
        assertEquals(11, ScalpStrategy.values().size)
        ScalpStrategy.values().forEach { assertTrue(eff.strategyEnabled(it)) }
    }

    @Test
    fun explicitlyChangedValuesWinOverAggressiveProfile() {
        val s = ScalpSettings(
            takeProfitPp = 9.0,   // user changed it — not the 6.0 default
            maxTradesPerHour = 7  // user changed it — not the 2 default
        )
        val eff = s.effective()
        assertEquals(9.0, eff.takeProfitPp, 1e-9)
        assertEquals(7, eff.maxTradesPerHour)
        // Untouched fields still get the aggressive profile.
        assertEquals(30, eff.windowSeconds)
        assertEquals(2.0, eff.dipMinDropPp, 1e-9)
    }

    @Test
    fun aggressiveFalseKeepsConservativeProfile() {
        val eff = ScalpSettings(aggressive = false).effective()
        assertEquals(2, eff.maxTradesPerHour)
        assertEquals(60, eff.windowSeconds)
        assertEquals(6.0, eff.takeProfitPp, 1e-9)
        assertEquals(5.0, eff.stopLossPp, 1e-9)
        assertEquals(480_000L, eff.maxHoldMs)
        assertEquals(5.0, eff.dipMinDropPp, 1e-9)
    }

    @Test
    fun dailyLossBreakerNeverRaisedByAggression() {
        // Even with an extreme stored breaker value, effective() keeps it.
        val s = ScalpSettings(maxDailyLossUsd = 3.0)
        assertEquals(3.0, s.effective().maxDailyLossUsd, 1e-9)
        // Kill switch is not touched by the resolver either.
        assertTrue(ScalpSettings(killSwitch = true).effective().killSwitch)
    }
}
