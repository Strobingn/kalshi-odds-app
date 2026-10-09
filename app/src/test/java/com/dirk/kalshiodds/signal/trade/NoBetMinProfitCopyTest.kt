package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoBetMinProfitCopyTest {

    /** 0.3.39: last-minute play is retired; these tests cover its dormant code. */
    @get:org.junit.Rule
    val legacyLastMinute = com.dirk.kalshiodds.signal.lastminute.LegacyLastMinuteRule()

    @Test
    fun minProfitGateIsOff() {
        val clip = LiveOrderSizer.size(0.63)
        assertTrue(clip.ok)
        assertFalse(LiveOrderSizer.belowMinProfit(clip.profitIfWinUsd, 0.0))
        assertFalse(LiveOrderSizer.belowMinProfit(0.10, 0.0))
    }

    @Test
    fun screenshotWindowIsLastMinuteWaitingNotMinProfit() {
        val market = HomeFixtures.screenshotPhoneBtc()
        val settings = SignalSettings(minProfitIfWinUsd = 20.0)
        val decision = BetCall.decide(market, settings, HomeFixtures.NOW_MS)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        val line = HomeCopy.thisWindowHeadline(decision, market, HomeFixtures.NOW_MS)
        assertTrue(line, line.startsWith("NO BET this window"))
        val reason = decision.noBetReason.orEmpty()
        assertTrue(reason, reason.contains("waiting") || reason.contains("Last-minute"))
        assertFalse(reason, reason.contains("$20"))
        assertFalse(reason, reason.contains("minimum"))
    }
}
