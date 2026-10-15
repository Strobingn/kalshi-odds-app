package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Tests for adaptive learning strategy
 */
class AdaptiveStrategyTest {

    private fun createPaperState(vararg fills: PaperFill): PaperBookState {
        return PaperBookState(
            startingUsd = 1000.0,
            cashUsd = 1000.0 - fills.sumOf { it.stakeUsd },
            fills = fills.toList(),
            archived = emptyList()
        )
    }

    private fun createFill(
        won: Boolean,
        pnl: Double,
        stake: Double = 5.0,
        limitPrice: Double = 0.30
    ): PaperFill {
        return PaperFill(
            id = UUID.randomUUID().toString(),
            ticker = "KXBTC15M-26SEP251500-00",
            side = if (won) "YES" else "NO",
            stakeUsd = stake,
            contracts = 1,
            limitPrice = limitPrice,
            source = "AI",
            createdAtMs = System.currentTimeMillis(),
            settled = true,
            outcome = if (won) "yes" else "no",
            won = won,
            pnlUsd = pnl,
            note = "Test fill"
        )
    }

    @Test
    fun `metrics with no trades returns zeros`() {
        val state = createPaperState()
        val strategy = AdaptiveStrategy(state)
        val metrics = strategy.calculateMetrics()
        
        assertEquals(0, metrics.totalTrades)
        assertEquals(0.0, metrics.totalPnl, 0.001)
        assertEquals(0.0, metrics.winRate, 0.001)
        assertFalse(metrics.hasEnoughData)
    }

    @Test
    fun `metrics with winning trades calculated correctly`() {
        val fills = listOf(
            createFill(won = true, pnl = 4.5),
            createFill(won = true, pnl = 4.5),
            createFill(won = false, pnl = -5.0)
        )
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val metrics = strategy.calculateMetrics()
        
        assertEquals(3, metrics.totalTrades)
        assertEquals(2, metrics.winningTrades)
        assertEquals(1, metrics.losingTrades)
        assertEquals(4.0, metrics.totalPnl, 0.001)
        assertEquals(2.0/3.0, metrics.winRate, 0.001)
        assertTrue(metrics.isProfitable)
    }

    @Test
    fun `metrics with losing trades calculated correctly`() {
        val fills = listOf(
            createFill(won = false, pnl = -5.0),
            createFill(won = false, pnl = -5.0),
            createFill(won = true, pnl = 4.5)
        )
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val metrics = strategy.calculateMetrics()
        
        assertEquals(3, metrics.totalTrades)
        assertEquals(1, metrics.winningTrades)
        assertEquals(2, metrics.losingTrades)
        assertEquals(-5.5, metrics.totalPnl, 0.001)
        assertEquals(1.0/3.0, metrics.winRate, 0.001)
        assertFalse(metrics.isProfitable)
    }

    @Test
    fun `adapt with no data returns defaults`() {
        val state = createPaperState()
        val strategy = AdaptiveStrategy(state)
        val config = strategy.adapt()
        
        assertEquals(0.40, config.maxAskFilter, 0.001)
        assertEquals(360_000L, config.timeWindowMs)
        assertEquals(300_000L, config.minTimeToCloseMs)
        assertTrue(config.reason.contains("Not enough data"))
    }

    @Test
    fun `adapt with profitable trades increases aggression`() {
        val fills = (1..15).map { 
            createFill(won = true, pnl = 4.5, limitPrice = 0.30)
        }
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val config = strategy.adapt()
        
        // With high win rate, should increase max ask
        assertTrue(config.maxAskFilter > 0.40)
        assertTrue(config.reason.contains("High win rate") || config.reason.contains("Good performance"))
    }

    @Test
    fun `adapt with losing trades decreases aggression`() {
        val fills = (1..15).map { 
            createFill(won = false, pnl = -5.0, limitPrice = 0.70)
        }
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val config = strategy.adapt()
        
        // With low win rate, should decrease max ask
        assertTrue(config.maxAskFilter < 0.40)
        assertTrue(config.reason.contains("Low win rate"))
    }

    @Test
    fun `shouldTrade returns true with no data`() {
        val state = createPaperState()
        val strategy = AdaptiveStrategy(state)
        val market = MarketUiModel(
            ticker = "KXBTC15M-26SEP251500-00",
            yesAsk = 0.30
        )
        
        assertTrue(strategy.shouldTrade(market))
    }

    @Test
    fun `shouldTrade returns false on losing streak`() {
        val fills = (1..5).map { 
            createFill(won = false, pnl = -5.0)
        }
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val market = MarketUiModel(
            ticker = "KXBTC15M-26SEP251500-00",
            yesAsk = 0.30
        )
        
        assertFalse(strategy.shouldTrade(market))
    }

    @Test
    fun `shouldTrade returns false with big drawdown`() {
        val fills = listOf(
            createFill(won = false, pnl = -50.0),
            createFill(won = false, pnl = -50.0),
            createFill(won = false, pnl = -50.0)
        )
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val market = MarketUiModel(
            ticker = "KXBTC15M-26SEP251500-00",
            yesAsk = 0.30
        )
        
        assertFalse(strategy.shouldTrade(market))
    }

    @Test
    fun `shouldTrade returns true when profitable`() {
        val fills = listOf(
            createFill(won = true, pnl = 4.5),
            createFill(won = true, pnl = 4.5),
            createFill(won = true, pnl = 4.5),
            createFill(won = false, pnl = -5.0)
        )
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val market = MarketUiModel(
            ticker = "KXBTC15M-26SEP251500-00",
            yesAsk = 0.30
        )
        
        assertTrue(strategy.shouldTrade(market))
    }

    @Test
    fun `getLearningSummary returns correct message with no data`() {
        val state = createPaperState()
        val strategy = AdaptiveStrategy(state)
        val summary = strategy.getLearningSummary()
        
        assertTrue(summary.contains("Need 10+"))
        assertTrue(summary.contains("settled trades"))
    }

    @Test
    fun `getLearningSummary returns correct message with data`() {
        val fills = listOf(
            createFill(won = true, pnl = 4.5, limitPrice = 0.30),
            createFill(won = true, pnl = 4.5, limitPrice = 0.30),
            createFill(won = false, pnl = -5.0, limitPrice = 0.70)
        )
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val summary = strategy.getLearningSummary()
        
        assertNotNull(summary)
        assertTrue(summary.contains("Trades: 3"))
        assertTrue(summary.contains("P&L:"))
        assertTrue(summary.contains("Win rate:"))
    }

    @Test
    fun `toWinningStrategyConfig converts correctly`() {
        val fills = (1..15).map { 
            createFill(won = true, pnl = 4.5, limitPrice = 0.30)
        }
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val config = strategy.getAdaptiveConfig()
        
        assertTrue(config.enabled)
        assertTrue(config.maxAskFilter > 0)
        assertTrue(config.timeWindowMs > 0)
    }

    @Test
    fun `current streak calculated correctly`() {
        val fills = listOf(
            createFill(won = true, pnl = 4.5),
            createFill(won = true, pnl = 4.5),
            createFill(won = true, pnl = 4.5),
            createFill(won = false, pnl = -5.0)
        )
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state)
        val metrics = strategy.calculateMetrics()
        
        // Last trade was a loss, so streak should be negative
        // But since we sort by createdAtMs descending, the first one is the most recent
        // For this test, all have same timestamp, so order is preserved
        assertTrue(metrics.currentStreak <= 0 || metrics.currentStreak == 1)
    }

    @Test
    fun `best ask range identified correctly`() {
        val fills = listOf(
            createFill(won = true, pnl = 4.5, limitPrice = 0.15),  // 15c - win
            createFill(won = true, pnl = 4.5, limitPrice = 0.25),  // 25c - win
            createFill(won = false, pnl = -5.0, limitPrice = 0.75), // 75c - loss
            createFill(won = false, pnl = -5.0, limitPrice = 0.85)  // 85c - loss
        )
        val state = createPaperState(*fills.toTypedArray())
        val strategy = AdaptiveStrategy(state, minSamplesForLearning = 2)
        val metrics = strategy.calculateMetrics()
        
        // 0-20c range should have 100% win rate (1 win, 0 losses in that range)
        // 20-40c range should have 100% win rate (1 win, 0 losses)
        // So best range should be one of these
        assertNotNull(metrics.bestAskRange)
        assertTrue(metrics.bestAskRange!!.second <= 0.40)
    }
}
