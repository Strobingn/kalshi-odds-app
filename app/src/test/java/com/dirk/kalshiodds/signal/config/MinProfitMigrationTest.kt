package com.dirk.kalshiodds.signal.config

import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinProfitMigrationTest {

    @Test
    fun defaultIsTen() {
        assertEquals(10.0, SignalConstants.DEFAULT_MIN_PROFIT_IF_WIN_USD, 1e-9)
        assertEquals(10.0, SignalSettings().minProfitIfWinUsd, 1e-9)
        assertEquals(10.0, MinProfitMigration.resolve(null, userExplicitlySet = false), 1e-9)
    }

    @Test
    fun storedTwentyWithoutUserFlagResetsToTen() {
        assertTrue(MinProfitMigration.isLegacyUnsetDefault(20.0))
        assertEquals(10.0, MinProfitMigration.resolve(20.0, userExplicitlySet = false), 1e-9)
        assertEquals(10.0, MinProfitMigration.resolve(20.0, userExplicitlySet = false), 1e-9)
    }

    @Test
    fun explicitTwentyIsKept() {
        assertEquals(20.0, MinProfitMigration.resolve(20.0, userExplicitlySet = true), 1e-9)
        assertEquals(15.0, MinProfitMigration.resolve(15.0, userExplicitlySet = false), 1e-9)
        assertEquals(8.0, MinProfitMigration.resolve(8.0, userExplicitlySet = true), 1e-9)
    }

    @Test
    fun noBetCopyReadsTheResolvedSetting() {
        val settings = SignalSettings(
            minProfitIfWinUsd = MinProfitMigration.resolve(20.0, userExplicitlySet = false)
        )
        assertEquals(10.0, settings.minProfitIfWinUsd, 1e-9)
        val clip = LiveOrderSizer.size(0.34)
        assertTrue(clip.ok)
        assertTrue(LiveOrderSizer.belowMinProfit(clip.profitIfWinUsd, settings.minProfitIfWinUsd))
        val msg = LiveOrderSizer.belowMinProfitMessage(clip.profitIfWinUsd, settings.minProfitIfWinUsd)
        assertTrue(msg, msg.contains("$10"))
        assertFalse(msg, msg.contains("$20"))
        val line = HomeCopy.noBetHeadline(msg)
        assertTrue(line, line.startsWith("NO BET this window"))
        assertTrue(line, line.contains("$10"))
        assertFalse(line, line.contains("$20"))
    }

    @Test
    fun screenshotWindowUsesTenNotTwenty() {
        val market = HomeFixtures.screenshotPhoneBtc()
        val decision = BetCall.decide(market, SignalSettings(), HomeFixtures.NOW_MS)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        val reason = decision.noBetReason.orEmpty()
        val line = HomeCopy.thisWindowHeadline(decision, market, HomeFixtures.NOW_MS)
        assertTrue(line, line.startsWith("NO BET this window"))
        if (reason.contains("minimum")) {
            assertTrue(reason, reason.contains("$10") || reason.contains("$10.00") || reason.contains("10"))
            assertFalse(reason, reason.contains("$20"))
        }
        assertFalse(line, line.contains("$20 minimum"))
    }
}
