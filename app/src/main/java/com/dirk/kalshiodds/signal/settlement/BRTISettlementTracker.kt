package com.dirk.kalshiodds.signal.settlement

import com.dirk.kalshiodds.domain.Clock
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.model.MarketTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.abs

/**
 * Tracks BRTI prints and calculates settlement probabilities in real-time.
 * 
 * This is the core engine for the Rank 1 strategy: Averaging-aware late favorite.
 * 
 * Key insight: KXBTC15M settles based on the simple average of 60 one-second BRTI prints
 * compared against a fixed target. With m prints remaining, the effective price is:
 *   E = (Σₖ + m·S_now)/60
 * 
 * The variance collapses rapidly as m decreases:
 * - 60 prints left: σ ≈ 3.63 bp
 * - 30 prints left: σ ≈ 1.30 bp
 * - 10 prints left: σ ≈ 0.26 bp
 * 
 * This means the outcome is effectively decided 20-40 seconds before the close.
 */
class BRTISettlementTracker(
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    
    private val mutex = Mutex()
    private val _activeWindows = mutableMapOf<String, BRTIWindow>()
    private val _currentBRTI = MutableStateFlow<Double?>(null)
    
    /**
     * All active BRTI windows being tracked.
     */
    val activeWindows: Map<String, BRTIWindow> get() = _activeWindows.toMap()
    
    /**
     * Current BRTI value (1-second).
     */
    val currentBRTI: StateFlow<Double?> = _currentBRTI.asStateFlow()
    
    /**
     * Trackable windows (those ready for trading or about to be ready).
     */
    private val _trackableWindows = MutableStateFlow<List<BRTIWindow>>(emptyList())
    val trackableWindows: StateFlow<List<BRTIWindow>> = _trackableWindows.asStateFlow()
    
    /**
     * Windows with high-probability trading opportunities.
     */
    private val _tradingOpportunities = MutableStateFlow<List<TradingOpportunity>>(emptyList())
    val tradingOpportunities: StateFlow<List<TradingOpportunity>> = _tradingOpportunities.asStateFlow()
    
    /**
     * Start tracking BRTI prints.
     */
    fun start() {
        // Start processing pipeline
        scope.launch {
            _currentBRTI.onEach { brti ->
                updateAllWindows(brti)
            }.launchIn(scope)
        }
    }
    
    /**
     * Stop tracking and clean up.
     */
    fun stop() {
        scope.cancel()
        _activeWindows.clear()
    }
    
    /**
     * Update the current BRTI value.
     * This should be called whenever a new BRTI print is received.
     */
    suspend fun updateBRTI(value: Double, timestamp: Instant) = mutex.withLock {
        _currentBRTI.value = value
        
        // Create or update windows for this timestamp
        val windowId = generateWindowId(timestamp)
        getOrCreateWindow(windowId, timestamp)
    }
    
    /**
     * Add a BRTI print to the tracker.
     */
    suspend fun addPrint(print: BRTIPrint) = mutex.withLock {
        _currentBRTI.value = print.value
        
        val window = _activeWindows[print.windowId] ?: run {
            val windowId = print.windowId
            val closeTime = extractCloseTimeFromWindowId(windowId)
            BRTIWindow.forCloseTime(closeTime, 0.0).also { _activeWindows[windowId] = it }
        }
        
        window.addPrint(print)
        updateWindowState(window)
    }
    
    /**
     * Create or get a window for a given close time.
     */
    suspend fun getOrCreateWindow(windowId: String, closeTime: Instant): BRTIWindow = mutex.withLock {
        _activeWindows[windowId]?.let { return@withLock it }
        
        val newWindow = BRTIWindow.forCloseTime(closeTime.toEpochMilli(), 0.0)
        _activeWindows[windowId] = newWindow
        return@withLock newWindow
    }
    
    /**
     * Update a window with a market's target value.
     */
    suspend fun updateWindowTarget(windowId: String, target: Double) = mutex.withLock {
        _activeWindows[windowId]?.let { window ->
            _activeWindows[windowId] = window.copy(target = target)
            updateWindowState(window.copy(target = target))
        }
    }
    
    /**
     * Mark a window as settled with its final value.
     */
    suspend fun settleWindow(windowId: String, settlementValue: Double, outcome: String) = mutex.withLock {
        _activeWindows[windowId]?.let { window ->
            val settledWindow = window.copy(
                isComplete = true,
                settlementValue = settlementValue,
                settledOutcome = outcome
            )
            _activeWindows[windowId] = settledWindow
            updateWindowState(settledWindow)
        }
    }
    
    /**
     * Remove a window from tracking.
     */
    suspend fun removeWindow(windowId: String) = mutex.withLock {
        _activeWindows.remove(windowId)
        updateTrackableWindows()
    }
    
    /**
     * Get the settlement probability for a given market.
     * 
     * @param ticker The market ticker (e.g., "KXBTC15M-26OCT130230")
     * @param currentBRTI Current BRTI value
     * @param target The market's target value
     * @param closeTimeEpochMs The close time in milliseconds
     * @return Settlement probability info, or null if not trackable
     */
    suspend fun getSettlementProbability(
        ticker: String,
        currentBRTI: Double,
        target: Double,
        closeTimeEpochMs: Long
    ): SettlementProbability? = mutex.withLock {
        val windowId = generateWindowIdFromCloseTime(closeTimeEpochMs)
        val window = _activeWindows[windowId] ?: run {
            // Create a temporary window for calculation
            val tempWindow = BRTIWindow.forCloseTime(closeTimeEpochMs, target)
            // Simulate having collected some prints
            return@withLock SettlementProbability(
                ticker = ticker,
                windowId = windowId,
                closeTimeEpochMs = closeTimeEpochMs,
                target = target,
                currentBRTI = currentBRTI,
                printsCollected = 0,
                printsRemaining = BRTIConstants.TOTAL_PRINT_COUNT,
                sumOfPrints = 0.0,
                effectivePrice = currentBRTI,
                settlementStdDev = BRTIConstants.SETTLEMENT_SD_MULTIPLIERS[BRTIConstants.TOTAL_PRINT_COUNT] ?: 3.63,
                probabilityYes = tempWindow.probabilityOfYes(currentBRTI),
                probabilityNo = tempWindow.probabilityOfNo(currentBRTI),
                fairValueYes = tempWindow.fairValueYes(currentBRTI),
                fairValueNo = tempWindow.fairValueNo(currentBRTI),
                isReadyForTrading = false
            )
        }
        
        return@withLock SettlementProbability(
            ticker = ticker,
            windowId = windowId,
            closeTimeEpochMs = closeTimeEpochMs,
            target = target,
            currentBRTI = currentBRTI,
            printsCollected = window.printCount,
            printsRemaining = window.printsRemaining,
            sumOfPrints = window.sumOfPrints,
            effectivePrice = window.effectivePrice(currentBRTI),
            settlementStdDev = window.settlementStandardDeviation(),
            probabilityYes = window.probabilityOfYes(currentBRTI),
            probabilityNo = window.probabilityOfNo(currentBRTI),
            fairValueYes = window.fairValueYes(currentBRTI),
            fairValueNo = window.fairValueNo(currentBRTI),
            isReadyForTrading = window.isReadyForTrading()
        )
    }
    
    /**
     * Calculate the trade signal for a market based on settlement probability.
     * 
     * @param ticker Market ticker
     * @param yesAsk Current YES ask price (0-1)
     * @param noAsk Current NO ask price (0-1)
     * @param currentBRTI Current BRTI value
     * @param target Market target
     * @param closeTimeEpochMs Close time in milliseconds
     * @return Trade signal with recommended action
     */
    suspend fun calculateTradeSignal(
        ticker: String,
        yesAsk: Double,
        noAsk: Double,
        currentBRTI: Double,
        target: Double,
        closeTimeEpochMs: Long
    ): TradeSignal? {
        val prob = getSettlementProbability(ticker, currentBRTI, target, closeTimeEpochMs) ?: return null
        
        if (!prob.isReadyForTrading) return null
        
        // Calculate fair value in cents
        val fairValueYesCents = prob.fairValueYes * 100
        val fairValueNoCents = prob.fairValueNo * 100
        val yesAskCents = yesAsk * 100
        val noAskCents = noAsk * 100
        
        // Get taker fee at current ask price
        val yesFeeCents = getTakerFeeCents(yesAsk)
        val noFeeCents = getTakerFeeCents(noAsk)
        
        // Calculate edge for YES side
        val yesEdge = fairValueYesCents - yesAskCents - yesFeeCents - BRTIConstants.SAFETY_BUFFER_CENTS
        
        // Calculate edge for NO side
        val noEdge = fairValueNoCents - noAskCents - noFeeCents - BRTIConstants.SAFETY_BUFFER_CENTS
        
        // Determine best side
        val (side, edge, askCents, fairValueCents, feeCents) = when {
            yesEdge > noEdge && yesEdge > 0 -> "YES" to yesEdge to yesAskCents to fairValueYesCents to yesFeeCents
            noEdge > yesEdge && noEdge > 0 -> "NO" to noEdge to noAskCents to fairValueNoCents to noFeeCents
            else -> return null  // No positive edge
        }
        
        // Only trade if probability is high enough
        val probability = if (side == "YES") prob.probabilityYes else prob.probabilityNo
        if (probability < BRTIConstants.TARGET_PROBABILITY_THRESHOLD) return null
        
        return TradeSignal(
            ticker = ticker,
            windowId = prob.windowId,
            side = side,
            askPrice = if (side == "YES") yesAsk else noAsk,
            fairValue = if (side == "YES") prob.fairValueYes else prob.fairValueNo,
            probability = probability,
            edgeCents = edge,
            edgeUsd = edge / 100.0,
            feeCents = feeCents,
            feeUsd = feeCents / 100.0,
            printsRemaining = prob.printsRemaining,
            effectivePrice = prob.effectivePrice,
            target = prob.target,
            currentBRTI = prob.currentBRTI,
            minContracts = BRTIConstants.MIN_CONTRACTS_PER_ORDER,
            recommendation = when {
                edge > 3.0 -> "STRONG_BUY"
                edge > 1.0 -> "BUY"
                edge > 0.5 -> "WEAK_BUY"
                else -> "MARGINAL"
            }
        )
    }
    
    /**
     * Get taker fee in cents for a given price.
     */
    private fun getTakerFeeCents(price: Double): Double {
        val priceCents = (price * 100).toInt()
        return BRTIConstants.TAKER_FEES_PER_CONTRACT[priceCents] ?: 
            BRTIConstants.TAKER_FEES_PER_CONTRACT[50] ?: 1.75
    }
    
    /**
     * Update all windows with the current BRTI value.
     */
    private suspend fun updateAllWindows(currentBRTI: Double?) = mutex.withLock {
        if (currentBRTI == null) return@withLock
        
        _activeWindows.values.forEach { window ->
            updateWindowState(window)
        }
    }
    
    /**
     * Update the state of a single window.
     */
    private suspend fun updateWindowState(window: BRTIWindow) = mutex.withLock {
        val currentBRTI = _currentBRTI.value ?: return@withLock
        
        // Check if ready for trading
        if (window.isReadyForTrading()) {
            // Calculate fair values
            val fairYes = window.fairValueYes(currentBRTI)
            val fairNo = window.fairValueNo(currentBRTI)
            
            // Check for opportunities
            val opportunities = mutableListOf<TradingOpportunity>()
            
            // YES opportunity: fair value high, ask low
            if (fairYes > 0.95) {
                opportunities.add(TradingOpportunity(
                    windowId = window.windowId,
                    side = "YES",
                    fairValue = fairYes,
                    probability = window.probabilityOfYes(currentBRTI),
                    printsRemaining = window.printsRemaining,
                    opportunityType = "AVERAGING_AWARE_LATE_FAVORITE"
                ))
            }
            
            // NO opportunity: fair value high for NO
            if (fairNo > 0.95) {
                opportunities.add(TradingOpportunity(
                    windowId = window.windowId,
                    side = "NO",
                    fairValue = fairNo,
                    probability = window.probabilityOfNo(currentBRTI),
                    printsRemaining = window.printsRemaining,
                    opportunityType = "AVERAGING_AWARE_LATE_FAVORITE"
                ))
            }
            
            _tradingOpportunities.value = opportunities
        }
        
        // Update trackable windows
        updateTrackableWindows()
    }
    
    /**
     * Update the list of trackable windows.
     */
    private suspend fun updateTrackableWindows() = mutex.withLock {
        val trackable = _activeWindows.values
            .filter { it.shouldStartTracking() || it.isReadyForTrading() }
            .sortedBy { it.closeTimeEpochMs }
            .toList()
        _trackableWindows.value = trackable
    }
    
    /**
     * Generate a window ID from a timestamp.
     */
    private fun generateWindowId(timestamp: Instant): String {
        val epochMs = timestamp.toEpochMilli()
        return generateWindowIdFromCloseTime(epochMs)
    }
    
    /**
     * Generate a window ID from a close time.
     */
    private fun generateWindowIdFromCloseTime(closeTimeEpochMs: Long): String {
        return "KXBTC15M-${closeTimeEpochMs}"
    }
    
    /**
     * Extract close time from a window ID.
     */
    private fun extractCloseTimeFromWindowId(windowId: String): Instant {
        val timeStr = windowId.removePrefix("KXBTC15M-")
        return Instant.ofEpochMilli(timeStr.toLongOrNull() ?: clock.now().toEpochMilli())
    }
    
    /**
     * Clean up old windows.
     */
    suspend fun cleanupOldWindows() = mutex.withLock {
        val now = clock.now().toEpochMilli()
        val cutoff = now - 2 * 60 * 60 * 1000  // 2 hours old
        
        _activeWindows.entries.removeAll { (_, window) ->
            window.closeTimeEpochMs < cutoff && window.isComplete
        }
    }
}

/**
 * Settlement probability information for a market.
 */
data class SettlementProbability(
    val ticker: String,
    val windowId: String,
    val closeTimeEpochMs: Long,
    val target: Double,
    val currentBRTI: Double,
    val printsCollected: Int,
    val printsRemaining: Int,
    val sumOfPrints: Double,
    val effectivePrice: Double,
    val settlementStdDev: Double,
    val probabilityYes: Double,
    val probabilityNo: Double,
    val fairValueYes: Double,
    val fairValueNo: Double,
    val isReadyForTrading: Boolean
)

/**
 * A trading opportunity identified by the settlement tracker.
 */
data class TradingOpportunity(
    val windowId: String,
    val side: String,
    val fairValue: Double,
    val probability: Double,
    val printsRemaining: Int,
    val opportunityType: String
)

/**
 * A trade signal with recommended action.
 */
data class TradeSignal(
    val ticker: String,
    val windowId: String,
    val side: String,
    val askPrice: Double,
    val fairValue: Double,
    val probability: Double,
    val edgeCents: Double,
    val edgeUsd: Double,
    val feeCents: Double,
    val feeUsd: Double,
    val printsRemaining: Int,
    val effectivePrice: Double,
    val target: Double,
    val currentBRTI: Double,
    val minContracts: Int,
    val recommendation: String
) {
    /**
     * Check if this signal is strong enough to trade.
     */
    val isTradingSignal: Boolean get() = 
        edgeCents > 0 && 
        probability >= BRTIConstants.TARGET_PROBABILITY_THRESHOLD
    
    /**
     * Get the display string for this signal.
     */
    fun display(): String {
        return "[$ticker] ${side} @ ${"%.2f".format(askPrice * 100)}c " +
               "(Fair: ${"%.2f".format(fairValue * 100)}c, " +
               "Prob: ${"%.1f%%".format(probability * 100)}, " +
               "Edge: +${"%.2f".format(edgeCents)}c)"
    }
}
