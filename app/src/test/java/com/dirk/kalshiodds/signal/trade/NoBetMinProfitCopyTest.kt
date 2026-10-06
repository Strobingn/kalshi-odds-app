package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoBetMinProfitCopyTest {

    @Test
    fun noBetHeadlineReadsTheActualMinProfitSetting() {
        val clip = LiveOrderSizer.size(0.34)
        assertTrue(clip.ok)
        for (minProfit in listOf(10.0, 20.0, 15.0)) {
            val msg = LiveOrderSizer.belowMinProfitMessage(clip.profitIfWinUsd, minProfit)
            val expected = String.format(java.util.Locale.US, "$%.0f", minProfit)
            assertTrue(msg, msg.contains(expected))
            val line = HomeCopy.noBetHeadline(msg)
            assertTrue(line, line.startsWith("NO BET this window"))
            assertTrue(line, line.contains(expected))
        }
    }

    @Test
    fun screenshotWindowDoesNotCreateAnActionableTicketBelowTheConfiguredProfitFloor() {
        val market = HomeFixtures.screenshotPhoneBtc().copy(tapeConflict = false, tapeConflictNote = null)
        val settings = SignalSettings(minProfitIfWinUsd = 20.0)
        val decision = BetCall.decide(
            market,
            TicketBuilder.Context(
                settings = settings,
                alertsPaused = false,
                nowMs = HomeFixtures.NOW_MS,
                books = mapOf(market.ticker to HomeFixtures.verifiedBookFor(market))
            )
        )
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        val line = HomeCopy.thisWindowHeadline(decision, market, HomeFixtures.NOW_MS)
        assertTrue(line, line.startsWith("NO BET this window"))
        assertFalse(decision.isActionable)
    }
}
