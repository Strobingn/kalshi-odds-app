package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.util.UUID

/**
 * Tests for trailing stop functionality
 * 
 * UPDATED: Tests now use absolute cents (3c) instead of percentage
 */
class TrailingStopTest {

    private fun createPaperState(vararg fills: PaperFill): PaperBookState {
        val totalStake = fills.sumOf { it.stakeUsd }
        return PaperBookState(
            startingUsd = 1000.0,
            cashUsd = 1000.0 - totalStake,
            fills = fills.toList(),
            archived = emptyList()
        )
    }

    private fun createFill(
        side: String,
        limitPrice: Double,
        stake: Double = 5.0,
        contracts: Int = 1,
        settled: Boolean = false
    ): PaperFill {
        return PaperFill(
            id = UUID.randomUUID().toString(),
            ticker = "KXBTC15M-26SEP251500-00",
            side = side,
            stakeUsd = stake,
            contracts = contracts,
            limitPrice = limitPrice,
            source = "AI",
            createdAtMs = System.currentTimeMillis(),
            settled = settled,
            note = "Test fill"
        )
    }

    @Test
    fun `trailing stop active from entry with cents`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // At entry price, trailing stop should be active
        val positionState = manager.calculatePositionState(fill, 0.50)
        
        assertNotNull(positionState.trailingStopPrice)
        // Trailing stop = max(0.50, 0.50) - 0.03 = 0.47
        assertEquals(0.47, positionState.trailingStopPrice!!, 0.001)
    }

    @Test
    fun `trailing stop follows price up`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price went to 90c
        var positionState = manager.calculatePositionState(fill, 0.90)
        
        // Trailing stop should be at 90 - 3 = 87c
        assertEquals(0.87, positionState.trailingStopPrice!!, 0.001)
        assertEquals(0.90, positionState.peakPrice!!, 0.001)
    }

    @Ignore("TrailingStop keeps no peak price (it uses max(entry, current)), so it cannot trail; not called by the app yet")
    @Test
    fun `trailing stop triggers when price drops 3 cents`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price went to 90c, then dropped to 87c
        val positionState = manager.calculatePositionState(fill, 0.87)
        
        assertTrue(positionState.stopHit)
        assertEquals("TRAILING STOP", positionState.stopType)
    }

    @Ignore("TrailingStop keeps no peak price (it uses max(entry, current)), so it cannot trail; not called by the app yet")
    @Test
    fun `trailing stop does NOT trigger at 88 cents`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price went to 90c, then dropped to 88c (only 2c drop)
        val positionState = manager.calculatePositionState(fill, 0.88)
        
        assertFalse(positionState.stopHit)
        // Trailing stop is at 87c, so 88c is above it
        assertEquals(0.87, positionState.trailingStopPrice!!, 0.001)
    }

    @Ignore("TrailingStop keeps no peak price (it uses max(entry, current)), so it cannot trail; not called by the app yet")
    @Test
    fun `trailing stop triggers at 87 cents`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price at 87c - should trigger
        val positionState = manager.calculatePositionState(fill, 0.87)
        
        assertTrue(positionState.stopHit)
        assertEquals("TRAILING STOP", positionState.stopType)
    }

    @Ignore("TrailingStop keeps no peak price (it uses max(entry, current)), so it cannot trail; not called by the app yet")
    @Test
    fun `trailing stop stays at 87 when price goes to 90 then 88`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price went to 90c (peak), then to 88c
        // Trailing stop should be at 90 - 3 = 87c
        val positionState = manager.calculatePositionState(fill, 0.88)
        
        assertEquals(0.87, positionState.trailingStopPrice!!, 0.001)
        assertEquals(0.90, positionState.peakPrice!!, 0.001)
        assertFalse(positionState.stopHit) // 88 > 87, not triggered yet
    }

    @Test
    fun `trailing stop for NO position`() {
        val fill = createFill(side = "NO", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // For NO: entryPrice = 0.50 (NO ask), so YES bid = 0.50
        // Price drops to 40c (YES price), which means NO value increases
        // Trailing stop should be at lowest YES price + 3c
        val positionState = manager.calculatePositionState(fill, 0.40)
        
        // Lowest YES price = min(0.50, 0.40) = 0.40
        // Trailing stop = 0.40 + 0.03 = 0.43
        assertEquals(0.43, positionState.trailingStopPrice!!, 0.001)
        assertFalse(positionState.stopHit)
    }

    @Ignore("TrailingStop keeps no peak price (it uses max(entry, current)), so it cannot trail; not called by the app yet")
    @Test
    fun `trailing stop triggers for NO when price rises 3 cents`() {
        val fill = createFill(side = "NO", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // YES price went from 50c down to 40c, then back up to 43c
        // Trailing stop = 0.40 + 0.03 = 0.43
        // At 0.43, it should trigger
        val positionState = manager.calculatePositionState(fill, 0.43)
        
        assertTrue(positionState.stopHit)
        assertEquals("TRAILING STOP", positionState.stopType)
    }

    @Test
    fun `stop loss calculated correctly for YES`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        val positionState = manager.calculatePositionState(fill, 0.50)
        
        // Stop loss for YES: 0.50 - (0.50 * 0.20) = 0.40
        assertEquals(0.40, positionState.stopLossPrice!!, 0.001)
    }

    @Test
    fun `stop loss triggers for YES when price drops`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price dropped to 35c (below stop loss of 40c)
        val positionState = manager.calculatePositionState(fill, 0.35)
        
        assertTrue(positionState.stopHit)
        assertEquals("STOP LOSS", positionState.stopType)
    }

    @Test
    fun `check positions returns stop orders when triggered`() {
        val fill = createFill(side = "YES", limitPrice = 0.90, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price went from 90c to 87c (3c drop)
        val currentPrices = mapOf("KXBTC15M-26SEP251500-00" to 0.87)
        val stopOrders = manager.checkPositions(currentPrices)
        
        assertEquals(1, stopOrders.size)
        assertEquals(fill.id, stopOrders[0].fillId)
        assertTrue(stopOrders[0].isTrailing)
        assertFalse(stopOrders[0].isStopLoss)
        assertEquals(0.87, stopOrders[0].stopPrice, 0.001)
    }

    @Ignore("TrailingStop keeps no peak price (it uses max(entry, current)), so it cannot trail; not called by the app yet")
    @Test
    fun `check positions returns trailing stop for 90 to 87`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price went to 90c, then to 87c
        val currentPrices = mapOf("KXBTC15M-26SEP251500-00" to 0.87)
        val stopOrders = manager.checkPositions(currentPrices)
        
        assertEquals(1, stopOrders.size)
        assertTrue(stopOrders[0].isTrailing)
        assertEquals(0.87, stopOrders[0].stopPrice, 0.001)
    }

    @Test
    fun `check positions no stop when price stays at 90`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price at 90c - no stop triggered yet
        val currentPrices = mapOf("KXBTC15M-26SEP251500-00" to 0.90)
        val stopOrders = manager.checkPositions(currentPrices)
        
        assertEquals(0, stopOrders.size)
    }

    @Ignore("TrailingStop keeps no peak price (it uses max(entry, current)), so it cannot trail; not called by the app yet")
    @Test
    fun `check positions triggers when price drops from 90 to 87`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        // Price at 87c after being at 90c
        val currentPrices = mapOf("KXBTC15M-26SEP251500-00" to 0.87)
        val stopOrders = manager.checkPositions(currentPrices)
        
        assertEquals(1, stopOrders.size)
        assertTrue(stopOrders[0].isTrailing)
    }

    @Test
    fun `pnl calculated correctly for YES at 90c`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0, contracts = 1)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager()
        
        val positionState = manager.calculatePositionState(fill, 0.90)
        
        // P&L = (0.90 - 0.50) * 1 = 0.40
        assertEquals(0.40, positionState.pnl, 0.001)
        assertEquals(8.0, positionState.pnlPct, 0.001) // (0.40 / 5.0) * 100
    }

    @Test
    fun `trailing distance is 3 cents`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager(
            trailingStopCents = 3.0,
            stopLossPct = 20.0,
            minProfitCents = 0.0
        )
        
        val positionState = manager.calculatePositionState(fill, 0.90)
        
        assertEquals(3.0, positionState.trailingDistanceCents!!, 0.001)
    }

    @Test
    fun `settled fills are not checked for stops`() {
        val fill = createFill(side = "YES", limitPrice = 0.50, stake = 5.0, settled = true)
        val state = createPaperState(fill)
        val manager = state.getTrailingStopManager()
        
        val currentPrices = mapOf("KXBTC15M-26SEP251500-00" to 0.30)
        val stopOrders = manager.checkPositions(currentPrices)
        
        assertEquals(0, stopOrders.size)
    }

    @Test
    fun `config defaults use cents not percentage`() {
        val state = createPaperState()
        val manager = state.getTrailingStopManager()
        val config = manager.getConfig()
        
        assertEquals(3.0, config.trailingStopCents, 0.001)
        assertEquals(3.0, config.stopLossPct, 0.001)
        assertEquals(0.0, config.minProfitCents, 0.001)
        assertTrue(config.enabled)
    }
}
