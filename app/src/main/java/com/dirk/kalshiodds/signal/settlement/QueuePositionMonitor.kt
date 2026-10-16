package com.dirk.kalshiodds.signal.settlement

import android.util.Log
import com.dirk.kalshiodds.data.api.KalshiTradeApi
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Monitors order queue positions for maker strategies.
 * 
 * Key insight from research: "A front-of-queue order fills against routine retail flow.
 * An order further back fills only when the whole level is swept, which usually
 * means an informed sweep."
 * 
 * Strategy: Only quote where you can be first (queue rank 0).
 * 
 * @see <a href="https://docs.kalshi.com/api-reference/orders/get-order-queue-position">
 * Kalshi Get Order Queue Position</a>
 */
class QueuePositionMonitor(
    private val tradeApi: KalshiTradeApi,
    private val credentials: () -> Pair<String, String>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pollIntervalMs: Long = 1000  // 1 second
) {
    
    companion object {
        private const val TAG = "QueuePositionMonitor"
    }
    
    private val mutex = Mutex()
    private val _queuePositions = MutableStateFlow<Map<String, QueuePosition>>(emptyMap())
    val queuePositions: StateFlow<Map<String, QueuePosition>> = _queuePositions.asStateFlow()
    
    private val _frontOfQueueOrders = MutableStateFlow<Set<String>>(emptySet())
    val frontOfQueueOrders: StateFlow<Set<String>> = _frontOfQueueOrders.asStateFlow()
    
    private var isMonitoring = false
    
    /**
     * Start monitoring queue positions.
     */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        
        scope.launch {
            while (isMonitoring) {
                try {
                    refreshAllQueuePositions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing queue positions", e)
                }
                delay(pollIntervalMs)
            }
        }
        
        Log.i(TAG, "Queue position monitoring started")
    }
    
    /**
     * Stop monitoring.
     */
    fun stop() {
        isMonitoring = false
        scope.cancel()
        _queuePositions.value = emptyMap()
        _frontOfQueueOrders.value = emptySet()
        Log.i(TAG, "Queue position monitoring stopped")
    }
    
    /**
     * Add an order to monitor.
     */
    suspend fun addOrder(orderId: String, ticker: String, side: String, price: Double) = mutex.withLock {
        val current = _queuePositions.value.toMutableMap()
        current[orderId] = QueuePosition(orderId, ticker, side, price, -1, -1, false)
        _queuePositions.value = current
    }
    
    /**
     * Remove an order from monitoring.
     */
    suspend fun removeOrder(orderId: String) = mutex.withLock {
        val current = _queuePositions.value.toMutableMap()
        current.remove(orderId)
        _queuePositions.value = current
    }
    
    /**
     * Get the queue position for a specific order.
     */
    suspend fun getQueuePosition(orderId: String): QueuePosition? = mutex.withLock {
        _queuePositions.value[orderId]
    }
    
    /**
     * Check if an order is at the front of the queue.
     */
    suspend fun isFrontOfQueue(orderId: String): Boolean = mutex.withLock {
        _queuePositions.value[orderId]?.isFrontOfQueue ?: false
    }
    
    /**
     * Check if an order has any queue ahead of it.
     */
    suspend fun hasQueueAhead(orderId: String): Boolean = mutex.withLock {
        _queuePositions.value[orderId]?.queueAhead?.let { it > 0 } ?: true
    }
    
    /**
     * Get all orders that are currently at the front of their queues.
     */
    suspend fun getFrontOfQueueOrders(): Set<String> = mutex.withLock {
        _frontOfQueueOrders.value
    }
    
    /**
     * Refresh queue positions for all monitored orders.
     */
    private suspend fun refreshAllQueuePositions() = mutex.withLock {
        val (apiKey, secret) = credentials()
        val positions = _queuePositions.value
        
        val updated = mutableMapOf<String, QueuePosition>()
        var frontOfQueueSet = mutableSetOf<String>()
        
        for ((orderId, position) in positions) {
            try {
                val queuePos = fetchQueuePosition(orderId, position.ticker, apiKey, secret)
                if (queuePos != null) {
                    updated[orderId] = queuePos
                    if (queuePos.isFrontOfQueue) {
                        frontOfQueueSet.add(orderId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching queue position for $orderId", e)
                // Keep existing position on error
                updated[orderId] = position
            }
        }
        
        _queuePositions.value = updated
        _frontOfQueueOrders.value = frontOfQueueSet
    }
    
    /**
     * Fetch queue position for a single order from Kalshi API.
     */
    private suspend fun fetchQueuePosition(
        orderId: String,
        ticker: String,
        apiKey: String,
        secret: String
    ): QueuePosition? {
        return try {
            // Note: The actual Kalshi API endpoint for queue position is:
            // GET /trade-api/v2/orders/{order_id}/queue-position
            // However, this requires authentication and the API structure may vary.
            
            // For now, we'll use a placeholder. The actual implementation would call:
            // val response = tradeApi.getOrderQueuePosition(orderId, ticker)
            
            // Placeholder: In a real implementation, this would call the Kalshi API
            // and parse the response to get queueAhead and contractsAhead
            
            // Mock response for demonstration
            QueuePosition(
                orderId = orderId,
                ticker = ticker,
                side = "YES",
                price = 0.95,
                queueAhead = 0,  // Front of queue
                contractsAhead = 0,
                isFrontOfQueue = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "API error fetching queue position for $orderId", e)
            null
        }
    }
    
    /**
     * Check if a price level has sub-cent ticks available.
     * If so, we can step $0.001 ahead to buy priority.
     */
    suspend fun hasSubCentTicks(ticker: String): Boolean {
        return try {
            // Call Kalshi API to get price level structure
            // GET /trade-api/v2/markets/{ticker}/price-level-structure
            // For now, return false as a placeholder
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking price level structure", e)
            false
        }
    }
    
    /**
     * Calculate the price to quote to be at the front of the queue.
     * 
     * @param currentBestBid Current best bid price
     * @param currentBestAsk Current best ask price
     * @param side Side to quote (YES or NO)
     * @param hasSubCentTicks Whether sub-cent ticks are available
     * @return Price to quote for front-of-queue position
     */
    fun calculateFrontOfQueuePrice(
        currentBestBid: Double,
        currentBestAsk: Double,
        side: String,
        hasSubCentTicks: Boolean
    ): Double {
        return if (side == "YES") {
            // For YES, we want to be at the best bid
            if (hasSubCentTicks) {
                // Step $0.001 ahead of current best bid
                currentBestBid + 0.001
            } else {
                // Move to current best bid (will be behind existing orders)
                currentBestBid
            }
        } else {
            // For NO, we want to be at the best ask
            if (hasSubCentTicks) {
                // Step $0.001 ahead (lower) of current best ask
                currentBestAsk - 0.001
            } else {
                // Move to current best ask
                currentBestAsk
            }
        }
    }
    
    /**
     * Check if we can achieve front-of-queue position at a given price.
     */
    suspend fun canAchieveFrontOfQueue(
        ticker: String,
        side: String,
        price: Double
    ): Boolean {
        val hasSubCent = hasSubCentTicks(ticker)
        
        // If we have sub-cent ticks, we can always step ahead
        if (hasSubCent) return true
        
        // Without sub-cent ticks, we need to be at the best price
        // and hope there's no queue ahead
        // In practice, this is hard to guarantee without checking
        return false
    }
}

/**
 * Represents the queue position of an order.
 */
data class QueuePosition(
    val orderId: String,
    val ticker: String,
    val side: String,
    val price: Double,
    val queueAhead: Int,
    val contractsAhead: Int,
    val isFrontOfQueue: Boolean
) {
    /**
     * Check if this order has any queue ahead.
     */
    val hasQueueAhead: Boolean get() = queueAhead > 0 || contractsAhead > 0
    
    /**
     * Get display string.
     */
    fun display(): String {
        return "Order $orderId ($ticker ${side} @ ${"%.2f".format(price * 100)}c): " +
               "Queue ahead: $queueAhead contracts, Front: $isFrontOfQueue"
    }
}

/**
 * Strategy for front-of-queue maker quoting.
 * 
 * Based on research finding: "+0.35¢ per order at the front of the queue, negative with any queue ahead"
 */
class FrontOfQueueStrategy(
    private val queueMonitor: QueuePositionMonitor,
    private val settlementTracker: BRTISettlementTracker
) {
    
    companion object {
        private const val TAG = "FrontOfQueueStrategy"
        
        /**
         * Minimum edge required to quote as a maker.
         */
        const val MIN_MAKER_EDGE_CENTS = 0.15
    }
    
    /**
     * Check if we should quote as a maker for this market.
     */
    suspend fun shouldQuote(
        ticker: String,
        yesBid: Double,
        yesAsk: Double,
        noBid: Double,
        noAsk: Double,
        currentBRTI: Double,
        target: Double,
        closeTimeEpochMs: Long
    ): MakerQuoteDecision {
        // First, check if we have front-of-queue position
        // In practice, we'd need to check our existing orders
        
        // For now, assume we can achieve front-of-queue
        // The key is: only quote if we can be first
        
        // Calculate settlement probability
        val prob = settlementTracker.getSettlementProbability(
            ticker, currentBRTI, target, closeTimeEpochMs
        ) ?: return MakerQuoteDecision(
            ticker = ticker,
            shouldQuote = false,
            reason = "No settlement probability data"
        )
        
        // Only consider if probability is high
        if (prob.fairValueYes < 0.70 && prob.fairValueNo < 0.70) {
            return MakerQuoteDecision(
                ticker = ticker,
                shouldQuote = false,
                reason = "Fair value too low (${"%.2f%%".format(prob.fairValueYes * 100)})"
            )
        }
        
        // Determine which side to quote
        val (side, fairValue, oppositePrice) = if (prob.fairValueYes > prob.fairValueNo) {
            Triple("YES", prob.fairValueYes, yesAsk)
        } else {
            Triple("NO", prob.fairValueNo, noAsk)
        }
        
        // Calculate maker edge
        // Maker edge = fair value - quote price - maker fee
        val makerFeeCents = BRTIConstants.MAKER_FEES_PER_CONTRACT[95] ?: 0.08
        val quotePrice = if (side == "YES") {
            // Quote slightly below fair value
            fairValue - 0.02  // 2c below fair
        } else {
            // Quote slightly below fair value (which means higher NO price)
            // NO fair value = 1 - YES fair value
            1.0 - (fairValue + 0.02)
        }
        
        val edgeCents = when (side) {
            "YES" -> (fairValue - quotePrice) * 100 - makerFeeCents
            "NO" -> ((1.0 - oppositePrice) - (1.0 - quotePrice)) * 100 - makerFeeCents
            else -> 0.0
        }
        
        // Only quote if edge is positive and we can be front-of-queue
        val canBeFront = true  // Placeholder - in practice, check queue monitor
        
        return MakerQuoteDecision(
            ticker = ticker,
            shouldQuote = canBeFront && edgeCents >= MIN_MAKER_EDGE_CENTS,
            side = side,
            quotePrice = quotePrice,
            fairValue = fairValue,
            edgeCents = edgeCents,
            makerFeeCents = makerFeeCents,
            isFrontOfQueue = canBeFront,
            reason = if (canBeFront && edgeCents >= MIN_MAKER_EDGE_CENTS) {
                "Positive edge (${"%.2f".format(edgeCents)}c) at front of queue"
            } else if (!canBeFront) {
                "Cannot achieve front-of-queue position"
            } else {
                "Edge too small (${"%.2f".format(edgeCents)}c < ${MIN_MAKER_EDGE_CENTS}c)"
            }
        )
    }
}

/**
 * Decision for maker quoting.
 */
data class MakerQuoteDecision(
    val ticker: String,
    val shouldQuote: Boolean,
    val side: String = "",
    val quotePrice: Double = 0.0,
    val fairValue: Double = 0.0,
    val edgeCents: Double = 0.0,
    val makerFeeCents: Double = 0.0,
    val isFrontOfQueue: Boolean = false,
    val reason: String = ""
)
