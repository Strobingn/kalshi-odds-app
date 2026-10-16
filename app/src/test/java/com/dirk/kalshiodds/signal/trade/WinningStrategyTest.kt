package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.trade.WinningStrategy.passesWinningStrategy
import com.dirk.kalshiodds.signal.trade.WinningStrategy.winningStrategyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the simplified winning strategy based on backtest results.
 */
class WinningStrategyTest {

    @Test
    fun `config defaults are correct`() {
        val config = WinningStrategy.Config()
        assertEquals(0.40, config.maxAskFilter, 0.001)
        assertEquals(360_000L, config.timeWindowMs)
        assertEquals(300_000L, config.minTimeToCloseMs)
        assertEquals(10.0, config.minProfitIfWinUsd, 0.001)
    }

    @Test
    fun `isCheapEnough returns true for cheap ask`() {
        val config = WinningStrategy.Config(enabled = true, maxAskFilter = 0.40)
        assertTrue(config.isCheapEnough(0.30))
        assertTrue(config.isCheapEnough(0.40))
    }

    @Test
    fun `isCheapEnough returns false for expensive ask`() {
        val config = WinningStrategy.Config(enabled = true, maxAskFilter = 0.40)
        assertFalse(config.isCheapEnough(0.41))
        assertFalse(config.isCheapEnough(0.50))
        assertFalse(config.isCheapEnough(0.75))
    }

    @Test
    fun `isInTimeWindow returns true within window`() {
        val config = WinningStrategy.Config(
            timeWindowMs = 360_000L, // 6 minutes
            minTimeToCloseMs = 300_000L  // 5 minutes
        )
        val closeTime = 10_000_000L
        
        // 5.5 minutes before close - should be in window
        assertTrue(config.isInTimeWindow(closeTime, closeTime - 330_000L))
        
        // Exactly at min time
        assertTrue(config.isInTimeWindow(closeTime, closeTime - 300_000L))
        
        // Exactly at max window
        assertTrue(config.isInTimeWindow(closeTime, closeTime - 360_000L))
    }

    @Test
    fun `isInTimeWindow returns false outside window`() {
        val config = WinningStrategy.Config(
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val closeTime = 10_000_000L
        
        // Too early (7 minutes before close)
        assertFalse(config.isInTimeWindow(closeTime, closeTime - 420_000L))
        
        // Too close (4 minutes before close)
        assertFalse(config.isInTimeWindow(closeTime, closeTime - 240_000L))
        
        // After close
        assertFalse(config.isInTimeWindow(closeTime, closeTime + 1000L))
    }

    @Test
    fun `passes returns true for valid market`() {
        val config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val nowMs = System.currentTimeMillis()
        val market = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            noAsk = 0.65,
            yesBid = 0.34,
            noBid = 0.64,
            closeTimeEpochMs = nowMs + 330_000L // 5.5 minutes from now
        )
        
        assertTrue(config.passes(market, nowMs))
    }

    @Test
    fun `passes returns false for expensive ask`() {
        val config = WinningStrategy.Config(enabled = true, maxAskFilter = 0.40)
        val nowMs = System.currentTimeMillis()
        val market = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.75,
            noAsk = 0.25,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        assertFalse(config.passes(market, nowMs))
    }

    @Test
    fun `passes returns false for wrong time window`() {
        val config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val nowMs = System.currentTimeMillis()
        
        // Too early
        val earlyMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            closeTimeEpochMs = nowMs + 420_000L // 7 minutes from now
        )
        assertFalse(config.passes(earlyMarket, nowMs))
        
        // Too close
        val closeMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            closeTimeEpochMs = nowMs + 240_000L // 4 minutes from now
        )
        assertFalse(config.passes(closeMarket, nowMs))
    }

    @Test
    fun `filterMarket returns true for valid market`() {
        val config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val nowMs = System.currentTimeMillis()
        val market = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        assertTrue(WinningStrategy.filterMarket(market, config, nowMs))
    }

    @Test
    fun `filterMarket returns false for invalid market`() {
        val config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val nowMs = System.currentTimeMillis()
        
        // No close time
        val noCloseMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35
        )
        assertFalse(WinningStrategy.filterMarket(noCloseMarket, config, nowMs))
        
        // Expensive ask
        val expensiveMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.75,
            closeTimeEpochMs = nowMs + 330_000L
        )
        assertFalse(WinningStrategy.filterMarket(expensiveMarket, config, nowMs))
    }

    @Test
    fun `filterReason returns null for valid market`() {
        val config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val nowMs = System.currentTimeMillis()
        val market = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        assertNull(WinningStrategy.filterReason(market, config, nowMs))
    }

    @Test
    fun `filterReason returns reason for expensive ask`() {
        val config = WinningStrategy.Config(enabled = true, maxAskFilter = 0.40)
        val nowMs = System.currentTimeMillis()
        val market = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.75,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        val reason = WinningStrategy.filterReason(market, config, nowMs)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Ask"))
        assertTrue(reason.contains("75"))
        assertTrue(reason.contains("40"))
    }

    @Test
    fun `filterReason returns reason for wrong time`() {
        val config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val nowMs = System.currentTimeMillis()
        
        // Too early
        val earlyMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            closeTimeEpochMs = nowMs + 420_000L
        )
        val reason = WinningStrategy.filterReason(earlyMarket, config, nowMs)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Too early") || reason.contains("420"))
        
        // Too close
        val closeMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            closeTimeEpochMs = nowMs + 240_000L
        )
        val closeReason = WinningStrategy.filterReason(closeMarket, config, nowMs)
        assertNotNull(closeReason)
        assertTrue(closeReason!!.contains("Too close") || closeReason.contains("240"))
    }

    @Test
    fun `presets are correctly configured`() {
        // Backtest winner preset
        val backtestWinner = WinningStrategy.Presets.BACKTEST_WINNER
        assertTrue(backtestWinner.enabled)
        assertEquals(0.40, backtestWinner.maxAskFilter, 0.001)
        assertEquals(360_000L, backtestWinner.timeWindowMs)
        assertEquals(300_000L, backtestWinner.minTimeToCloseMs)
        
        // Cheap side only preset
        val cheapSideOnly = WinningStrategy.Presets.CHEAP_SIDE_ONLY
        assertTrue(cheapSideOnly.enabled)
        assertEquals(0.40, cheapSideOnly.maxAskFilter, 0.001)
        assertEquals(Long.MAX_VALUE, cheapSideOnly.timeWindowMs)
        assertEquals(0L, cheapSideOnly.minTimeToCloseMs)
        
        // Last six minutes preset
        val lastSix = WinningStrategy.Presets.LAST_SIX_MINUTES
        assertTrue(lastSix.enabled)
        assertEquals(0.50, lastSix.maxAskFilter, 0.001)
        assertEquals(360_000L, lastSix.timeWindowMs)
        assertEquals(60_000L, lastSix.minTimeToCloseMs)
    }

    @Test
    fun `passesForSide returns true for cheap YES side`() {
        val config = WinningStrategy.Config(enabled = true, maxAskFilter = 0.40)
        val nowMs = System.currentTimeMillis()
        val market = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            noAsk = 0.65,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        assertTrue(config.passesForSide(market, "YES", nowMs))
        assertFalse(config.passesForSide(market, "NO", nowMs)) // NO is expensive
    }

    @Test
    fun `passesForSide returns true for cheap NO side`() {
        val config = WinningStrategy.Config(enabled = true, maxAskFilter = 0.40)
        val nowMs = System.currentTimeMillis()
        val market = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.65,
            noAsk = 0.35,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        assertTrue(config.passesForSide(market, "NO", nowMs))
        assertFalse(config.passesForSide(market, "YES", nowMs)) // YES is expensive
    }

    @Test
    fun `extension functions work correctly`() {
        val config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L
        )
        val nowMs = System.currentTimeMillis()
        
        val validMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.35,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        assertTrue(validMarket.passesWinningStrategy(config, nowMs))
        assertNull(validMarket.winningStrategyReason(config, nowMs))
        
        val expensiveMarket = testMarket(
            ticker = "KXBTC15M-26SEP251500-00",
            series = "KXBTC15M",
            yesAsk = 0.75,
            closeTimeEpochMs = nowMs + 330_000L
        )
        
        assertFalse(expensiveMarket.passesWinningStrategy(config, nowMs))
        assertNotNull(expensiveMarket.winningStrategyReason(config, nowMs))
    }
}
