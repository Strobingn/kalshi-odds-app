package com.dirk.kalshiodds.data.local.history

import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRestoreTest {
    @Test
    fun roundTripHunterAndWinTarget() {
        val s = SignalSettings(
            hunterValueStakeUsd = 1.0,
            hunterValuePayoutUsd = 5.0,
            winTargetEnabled = true,
            winTargetUsd = 50.0,
            winTargetBankrollPct = 10.0,
            winTargetAbsCapUsd = 25.0,
            ticketStakeUsd = 5.0,
            bankrollUsd = 1000.0
        )
        val parsed = SettingsRestore.parse(SettingsRestore.snapshot(s))
        assertEquals(1.0, parsed.hunterValueStakeUsd!!, 1e-9)
        assertEquals(5.0, parsed.hunterValuePayoutUsd!!, 1e-9)
        assertEquals(true, parsed.winTargetEnabled)
        assertEquals(50.0, parsed.winTargetUsd!!, 1e-9)
        assertEquals(10.0, parsed.winTargetBankrollPct!!, 1e-9)
        assertEquals(25.0, parsed.winTargetAbsCapUsd!!, 1e-9)
        assertEquals(5.0, parsed.ticketStakeUsd!!, 1e-9)
        assertEquals(1000.0, parsed.bankrollUsd!!, 1e-9)
        assertFalse(parsed.isEmpty)
    }

    @Test
    fun emptyJsonIsEmptyRestore() {
        val parsed = SettingsRestore.parse("{}")
        assertTrue(parsed.isEmpty)
        assertNull(parsed.winTargetUsd)
    }

    @Test
    fun labelMentionsHunterAndWinTarget() {
        val label = SettingsRestore.label(
            SignalSettings(winTargetEnabled = true, winTargetUsd = 50.0, hunterValuePayoutUsd = 5.0)
        )
        assertTrue(label.contains("$1→$5"))
        assertTrue(label.contains("$50"))
    }
}
