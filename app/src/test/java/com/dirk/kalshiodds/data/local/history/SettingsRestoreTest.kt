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
            longShotMaxAsk = 0.20,
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
        assertEquals(0.20, parsed.longShotMaxAsk!!, 1e-9)
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
        assertNull(parsed.longShotMaxAsk)
    }

    @Test
    fun legacyOneToFiveDerivesLongShotMaxAsk() {
        val parsed = SettingsRestore.parse("""{"hunterValueStakeUsd":1.0,"hunterValuePayoutUsd":5.0}""")
        assertEquals(0.20, parsed.longShotMaxAsk!!, 1e-9)
    }

    @Test
    fun labelMentionsLongShotAndWinTarget() {
        val label = SettingsRestore.label(
            SignalSettings(winTargetEnabled = true, winTargetUsd = 50.0, longShotMaxAsk = 0.20)
        )
        assertTrue(label.contains("long-shot"))
        assertTrue(label.contains("20¢") || label.contains("20"))
        assertTrue(label.contains("$50"))
        assertFalse(label.contains("$1→$5"))
        assertFalse(label.contains("$1 → $5"))
    }
}
