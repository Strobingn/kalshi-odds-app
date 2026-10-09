package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpGuardrailsTest {

    private fun settings(
        maxTradesPerHour: Int = 6,
        maxDailyLossUsd: Double = 10.0,
        killSwitch: Boolean = false
    ) = ScalpSettings(
        enabled = true,
        maxTradesPerHour = maxTradesPerHour,
        maxDailyLossUsd = maxDailyLossUsd,
        killSwitch = killSwitch
    )

    private fun state(
        openPositionCount: Int = 0,
        entriesLastHour: Int = 0,
        realizedPnlCentsToday: Long = 0L,
        killSwitch: Boolean = false
    ) = ScalpGuardrails.ScalpGuardState(
        openPositionCount = openPositionCount,
        entriesLastHour = entriesLastHour,
        realizedPnlCentsToday = realizedPnlCentsToday,
        killSwitch = killSwitch
    )

    @Test
    fun clearWhenNothingWrong() {
        assertNull(ScalpGuardrails.checkEnter(state(), settings()))
    }

    @Test
    fun blocksAfterMaxTradesPerHour() {
        val block = ScalpGuardrails.checkEnter(state(entriesLastHour = 6), settings(maxTradesPerHour = 6))
        assertNotNull(block)
        assertTrue(block!!.contains("cap"))
        // One under the cap still trades.
        assertNull(ScalpGuardrails.checkEnter(state(entriesLastHour = 5), settings(maxTradesPerHour = 6)))
    }

    @Test
    fun blocksWhenDailyLossExceeded() {
        // −$10.50 realized today against a $10 limit.
        val block = ScalpGuardrails.checkEnter(state(realizedPnlCentsToday = -1050), settings(maxDailyLossUsd = 10.0))
        assertNotNull(block)
        assertTrue(block!!.contains("loss limit"))
        // A small loss under the limit is fine.
        assertNull(ScalpGuardrails.checkEnter(state(realizedPnlCentsToday = -500), settings(maxDailyLossUsd = 10.0)))
        // Profit never blocks.
        assertNull(ScalpGuardrails.checkEnter(state(realizedPnlCentsToday = 2500), settings(maxDailyLossUsd = 10.0)))
    }

    @Test
    fun killSwitchBlocksEverything() {
        val block = ScalpGuardrails.checkEnter(state(killSwitch = true), settings())
        assertNotNull(block)
        assertTrue(block!!.contains("kill switch"))
        // Even with a perfectly clean state the switch wins.
        val block2 = ScalpGuardrails.checkEnter(
            state(openPositionCount = 0, entriesLastHour = 0, realizedPnlCentsToday = 0, killSwitch = true),
            settings(killSwitch = true)
        )
        assertNotNull(block2)
    }

    @Test
    fun blocksWhenAlreadyInPosition() {
        val block = ScalpGuardrails.checkEnter(state(openPositionCount = 1), settings())
        assertNotNull(block)
        assertTrue(block!!.contains("one at a time"))
    }

    @Test
    fun killSwitchTakesPrecedenceOverOtherBlocks() {
        val block = ScalpGuardrails.checkEnter(
            state(openPositionCount = 1, entriesLastHour = 99, realizedPnlCentsToday = -50000, killSwitch = true),
            settings()
        )
        assertEquals("kill switch tripped — clear it in Scalp settings to resume", block)
    }

    @Test
    fun settingsDefaultsAreSafe() {
        val s = ScalpSettings()
        assertTrue(!s.enabled)          // off by default
        assertTrue(!s.liveMode)         // paper by default
        assertTrue(!s.killSwitch)
        assertEquals(5.0, s.maxStakeUsd, 1e-9)
        // Least-bad combo from docs/scalping-params.md (all combos lose after
        // fees): 2 trades/hour caps the bleed rate if the feature is enabled
        // by accident.
        assertEquals(2, s.maxTradesPerHour)
        assertEquals(10.0, s.maxDailyLossUsd, 1e-9)
        assertEquals(6.0, s.takeProfitPp, 1e-9)
        assertEquals(5.0, s.stopLossPp, 1e-9)
        assertEquals(480_000L, s.maxHoldMs)
        assertEquals(5.0, s.dipMinDropPp, 1e-9)
        assertTrue(s.paper)
    }
}
