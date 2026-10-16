package com.dirk.kalshiodds.signal.settlement

import com.dirk.kalshiodds.domain.Clock
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.KalshiPrice
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for BRTI Settlement Tracker.
 * 
 * These tests verify the core settlement probability calculations
 * that power the Rank 1 averaging-aware strategy.
 */
class BRTISettlementTrackerTest : TestCase() {
    
    private lateinit var tracker: BRTISettlementTracker
    private lateinit var testClock: TestClock
    
    override fun setUp() {
        super.setUp()
        testClock = TestClock()
        tracker = BRTISettlementTracker(testClock)
    }
    
    override fun tearDown() {
        super.tearDown()
        tracker.stop()
    }
    
    /**
     * Test clock for testing.
     */
    private class TestClock : Clock {
        var nowMs: Long = System.currentTimeMillis()
        override fun now(): Instant = Instant.ofEpochMilli(nowMs)
        override fun nowMs(): Long = nowMs
    }
    
    @Test
    fun testBRTIWindowEffectivePriceCalculation() {
        runBlocking {
            // Create a window with 30 prints collected
            val closeTime = Instant.now().plusSeconds(60).toEpochMilli()
            val window = BRTIWindow.forCloseTime(closeTime, 50000.0)
            
            // Add 30 prints at 42000
            repeat(30) { index ->
                val print = BRTIPrint(
                    timestamp = Instant.now().plusSeconds(index.toLong()),
                    value = 42000.0,
                    windowId = "KXBTC15M-$closeTime",
                    printIndex = index
                )
                window.addPrint(print)
            }
            
            // Current BRTI is 42100
            val currentBRTI = 42100.0
            
            // Effective price = (30 * 42000 + 30 * 42100) / 60
            // = (1,260,000 + 1,263,000) / 60
            // = 2,523,000 / 60
            // = 42050
            val expectedEffectivePrice = 42050.0
            val actualEffectivePrice = window.effectivePrice(currentBRTI)
            
            assertEquals(expectedEffectivePrice, actualEffectivePrice, 0.001)
        }
    }
    
    @Test
    fun testBRTIWindowProbabilityCalculation() {
        runBlocking {
            // Create a window with 50 prints at 42000
            val closeTime = Instant.now().plusSeconds(60).toEpochMilli()
            val window = BRTIWindow.forCloseTime(closeTime, 42000.0)
            
            repeat(50) { index ->
                val print = BRTIPrint(
                    timestamp = Instant.now().plusSeconds(index.toLong()),
                    value = 42000.0,
                    windowId = "KXBTC15M-$closeTime",
                    printIndex = index
                )
                window.addPrint(print)
            }
            
            // Current BRTI is 42100
            val currentBRTI = 42100.0
            
            // Effective price = (50 * 42000 + 10 * 42100) / 60
            // = (2,100,000 + 421,000) / 60
            // = 2,521,000 / 60
            // = 42016.666...
            val effectivePrice = window.effectivePrice(currentBRTI)
            
            // Target is 42000, so E - K = 16.666...
            // With 10 prints left, sd = 0.26 bp (from constants)
            // But we're working in BTC price units, not cents
            // For the probability calculation, we need to convert to probability
            
            // Since E > K, probability should be > 50%
            val probYes = window.probabilityOfYes(currentBRTI)
            
            assertTrue("Probability should be > 0.5 when E > K", probYes > 0.5)
        }
    }
    
    @Test
    fun testSettlementProbabilityHighCertainty() {
        runBlocking {
            // Create a window with 55 prints at 42100, target is 42000
            val closeTime = Instant.now().plusSeconds(60).toEpochMilli()
            val window = BRTIWindow.forCloseTime(closeTime, 42000.0)
            
            repeat(55) { index ->
                val print = BRTIPrint(
                    timestamp = Instant.now().plusSeconds(index.toLong()),
                    value = 42100.0,
                    windowId = "KXBTC15M-$closeTime",
                    printIndex = index
                )
                window.addPrint(print)
            }
            
            // Current BRTI is 42100
            val currentBRTI = 42100.0
            
            // With 55 prints at 42100 and 5 prints at 42100,
            // E = (55 * 42100 + 5 * 42100) / 60 = 42100
            // Target is 42000, so E - K = 100
            // This should give very high probability
            val probYes = window.probabilityOfYes(currentBRTI)
            
            assertTrue("Probability should be very high when E >> K", probYes > 0.99)
        }
    }
    
    @Test
    fun testSettlementProbabilityLowCertainty() {
        runBlocking {
            // Create a window with 30 prints at 41900, target is 42000
            val closeTime = Instant.now().plusSeconds(60).toEpochMilli()
            val window = BRTIWindow.forCloseTime(closeTime, 42000.0)
            
            repeat(30) { index ->
                val print = BRTIPrint(
                    timestamp = Instant.now().plusSeconds(index.toLong()),
                    value = 41900.0,
                    windowId = "KXBTC15M-$closeTime",
                    printIndex = index
                )
                window.addPrint(print)
            }
            
            // Current BRTI is 41900
            val currentBRTI = 41900.0
            
            // E = (30 * 41900 + 30 * 41900) / 60 = 41900
            // Target is 42000, so E - K = -100
            // This should give very low probability
            val probYes = window.probabilityOfYes(currentBRTI)
            
            assertTrue("Probability should be very low when E << K", probYes < 0.01)
        }
    }
    
    @Test
    fun testTradeSignalCalculation() {
        runBlocking {
            // Create a tracker
            val tracker = BRTISettlementTracker()
            
            // Create a market with YES ask at 95c
            val market = MarketUiModel(
                ticker = "KXBTC15M-1234567890",
                yesAsk = 0.95,
                noAsk = 0.05,
                closeTimeEpochMs = System.currentTimeMillis() + 30000  // 30 seconds from now
            )
            
            // Add prints to tracker
            val closeTime = market.closeTimeEpochMs ?: return@runBlocking
            val windowId = "KXBTC15M-$closeTime"
            
            // Add 50 prints at 42100 (above target of 42000)
            repeat(50) { index ->
                val print = BRTIPrint(
                    timestamp = Instant.ofEpochMilli(System.currentTimeMillis() + index * 1000L),
                    value = 42100.0,
                    windowId = windowId,
                    printIndex = index
                )
                tracker.addPrint(print)
            }
            
            // Update window target
            tracker.updateWindowTarget(windowId, 42000.0)
            
            // Calculate trade signal
            val currentBRTI = 42100.0
            val nowMs = System.currentTimeMillis()
            
            val signal = tracker.calculateTradeSignal(
                ticker = market.ticker,
                yesAsk = market.yesAsk ?: 0.0,
                noAsk = market.noAsk ?: 0.0,
                currentBRTI = currentBRTI,
                target = 42000.0,
                closeTimeEpochMs = closeTime
            )
            
            assertNotNull("Signal should not be null", signal)
            assertEquals("YES", signal?.side)
            assertTrue("Signal should be a trading signal", signal?.isTradingSignal ?: false)
        }
    }
    
    @Test
    fun testNoSignalWhenProbabilityTooLow() {
        runBlocking {
            val tracker = BRTISettlementTracker()
            
            val market = MarketUiModel(
                ticker = "KXBTC15M-1234567890",
                yesAsk = 0.50,
                noAsk = 0.50,
                closeTimeEpochMs = System.currentTimeMillis() + 30000
            )
            
            val closeTime = market.closeTimeEpochMs ?: return@runBlocking
            val windowId = "KXBTC15M-$closeTime"
            
            // Add prints below target
            repeat(50) { index ->
                val print = BRTIPrint(
                    timestamp = Instant.ofEpochMilli(System.currentTimeMillis() + index * 1000L),
                    value = 41900.0,
                    windowId = windowId,
                    printIndex = index
                )
                tracker.addPrint(print)
            }
            
            tracker.updateWindowTarget(windowId, 42000.0)
            
            val currentBRTI = 41900.0
            val nowMs = System.currentTimeMillis()
            
            val signal = tracker.calculateTradeSignal(
                ticker = market.ticker,
                yesAsk = market.yesAsk ?: 0.0,
                noAsk = market.noAsk ?: 0.0,
                currentBRTI = currentBRTI,
                target = 42000.0,
                closeTimeEpochMs = closeTime
            )
            
            assertNull("Signal should be null when probability is too low", signal)
        }
    }
}
