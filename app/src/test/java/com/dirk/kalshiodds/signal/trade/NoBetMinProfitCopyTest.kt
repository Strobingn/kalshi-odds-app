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
    fun screenshotWindowUsesTwentyWhenThatIsTheSetting() {
        val market = HomeFixtures.screenshotPhoneBtc()
        // Small bankroll keeps edge sizing at the $5 floor, so the $20
        // min-profit gate still blocks this 34¢ window.
        val settings = SignalSettings(minProfitIfWinUsd = 20.0, bankrollUsd = 20.0)
        val decision = BetCall.decide(market, settings, HomeFixtures.NOW_MS)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        val line = HomeCopy.thisWindowHeadline(decision, market, HomeFixtures.NOW_MS)
        assertTrue(line, line.startsWith("NO BET this window"))
        val reason = decision.noBetReason.orEmpty()
        assertTrue(reason, reason.contains("$20") || line.contains("$20"))
        assertFalse(reason, reason.contains("$10 minimum"))
    }
}
