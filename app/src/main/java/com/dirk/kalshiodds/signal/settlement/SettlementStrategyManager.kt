package com.dirk.kalshiodds.signal.settlement

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages all settlement-based strategies.
 * 
 * Prioritizes strategies by rank:
 * 1. Averaging-Aware Late Favorite (Rank 1) - Expected edge: 1-4¢/contract
 * 2. Reference-Price Errors (Rank 2) - Large per event, rare
 * 3. Maker Front-of-Queue (Rank 3) - Sub-cent to 1¢/fill
 * 4. Fair-Anchored Front-of-Queue Quoting (Rank 4) - ~0.35¢/order
 * 5. Liquidity Incentive Program (Rank 5) - Unknown
 * 6. Hour-of-Day Volatility (Rank 6) - <1¢
 * 7. Lean Against Taker Jumps (Rank 7) - <1¢
 * 8. Taker Latency Arbitrage (Rank 8) - AVOID
 * 
 * This manager coordinates between strategies and ensures we don't
 * double-trade the same opportunity.
 */
class SettlementStrategyManager(
    private val settlementTracker: BRTISettlementTracker,
    private val queueMonitor: QueuePositionMonitor,
    private val feeVerifier: FeeTypeVerifier
) {
    
    /**
     * All available strategies, ranked by priority.
     */
    private val strategies: List<BaseSettlementStrategy>
    
    /**
     * Currently active strategy (highest priority that triggers).
     */
    private var activeStrategy: BaseSettlementStrategy? = null
    
    /**
     * Track which markets we're already trading.
     */
    private val tradedMarkets = mutableSetOf<String>()
    
    init {
        // Initialize all strategies
        strategies = listOf(
            AveragingAwareStrategy(settlementTracker, feeVerifier),
            MakerStrategy(settlementTracker, queueMonitor, feeVerifier)
            // Add more strategies here as they're implemented
        )
    }
    
    /**
     * Evaluate a market across all strategies.
     * 
     * @param market The market to evaluate
     * @param currentBRTI Current BRTI value
     * @param nowMs Current time in milliseconds
     * @return Best strategy result, or null if no strategy triggers
     */
    suspend fun evaluateMarket(
        market: MarketUiModel,
        currentBRTI: Double,
        nowMs: Long
    ): StrategyEvaluation? {
        // Skip if we're already trading this market
        if (tradedMarkets.contains(market.ticker)) {
            return null
        }
        
        var bestResult: StrategyEvaluation? = null
        var bestPriority = Int.MAX_VALUE
        
        for (strategy in strategies) {
            val result = strategy.evaluate(market, currentBRTI, nowMs)
            
            if (result != null && result.passes && strategy.getRank() < bestPriority) {
                bestResult = StrategyEvaluation(
                    strategy = strategy,
                    result = result,
                    market = market,
                    priority = strategy.getRank()
                )
                bestPriority = strategy.getRank()
            }
        }
        
        return bestResult
    }
    
    /**
     * Get the best trade signal for a market.
     */
    suspend fun getBestSignal(
        market: MarketUiModel,
        currentBRTI: Double,
        nowMs: Long
    ): TradeSignal? {
        val evaluation = evaluateMarket(market, currentBRTI, nowMs) ?: return null
        return evaluation.result.signal
    }
    
    /**
     * Create a trade ticket from the best strategy.
     */
    suspend fun createTradeTicket(
        market: MarketUiModel,
        currentBRTI: Double,
        nowMs: Long
    ): TradeTicket? {
        val evaluation = evaluateMarket(market, currentBRTI, nowMs) ?: return null
        
        // Mark this market as traded
        tradedMarkets.add(market.ticker)
        
        // Create ticket using the strategy
        return when (val strategy = evaluation.strategy) {
            is AveragingAwareStrategy -> strategy.createTicket(market, currentBRTI, nowMs)
            is MakerStrategy -> strategy.createMakerTicket(market, currentBRTI, nowMs)
            else -> null
        }
    }
    
    /**
     * Mark a trade as completed for a market.
     */
    fun markTradeCompleted(marketTicker: String) {
        tradedMarkets.remove(marketTicker)
    }
    
    /**
     * Get all active strategies with their status.
     */
    suspend fun getAllStrategyStatuses(): List<StrategyStatus> {
        return strategies.map { strategy ->
            when (strategy) {
                is AveragingAwareStrategy -> strategy.getStatus()
                is MakerStrategy -> strategy.getStatus()
                else -> StrategyStatus(
                    strategyName = strategy.javaClass.simpleName,
                    rank = strategy.getRank(),
                    isEnabled = true,
                    isViable = true,
                    feeAnalysis = FeeImpactAnalysis(
                        ticker = "KXBTC15M",
                        strategyType = StrategyType.TAKER_LATE_FAVORITE,
                        hasMakerFees = false,
                        makerFeeAt50c = 0.0,
                        makerFeeAt95c = 0.0,
                        takerFeeAt50c = 1.75,
                        takerFeeAt95c = 0.33,
                        makerFeeImpact = 0.0,
                        takerFeeImpact = -1.75,
                        isStrategyViable = true,
                        recommendation = "Unknown"
                    ),
                    config = "N/A",
                    description = "Strategy status not available"
                )
            }
        }.sortedBy { it.rank }
    }
    
    /**
     * Get the status of a specific strategy.
     */
    suspend fun getStrategyStatus(rank: Int): StrategyStatus? {
        return strategies.firstOrNull { it.getRank() == rank }?.let { strategy ->
            when (strategy) {
                is AveragingAwareStrategy -> strategy.getStatus()
                is MakerStrategy -> strategy.getStatus()
                else -> null
            }
        }
    }
    
    /**
     * Check if any strategy is viable for the current market conditions.
     */
    suspend fun isAnyStrategyViable(): Boolean {
        return strategies.any { strategy ->
            when (strategy) {
                is AveragingAwareStrategy -> strategy.isViable()
                is MakerStrategy -> strategy.getStatus().isViable
                else -> true
            }
        }
    }
    
    /**
     * Get the current fee analysis for all strategies.
     */
    suspend fun getFeeAnalysisSummary(): Map<String, FeeImpactAnalysis> {
        val results = mutableMapOf<String, FeeImpactAnalysis>()
        
        for (strategy in strategies) {
            val feeAnalysis = when (strategy) {
                is AveragingAwareStrategy -> feeVerifier.getFeeImpactAnalysis(
                    "KXBTC15M", StrategyType.TAKER_LATE_FAVORITE
                )
                is MakerStrategy -> feeVerifier.getFeeImpactAnalysis(
                    "KXBTC15M", StrategyType.MAKER_LATE_WINDOW
                )
                else -> FeeImpactAnalysis(
                    ticker = "KXBTC15M",
                    strategyType = StrategyType.TAKER_LATE_FAVORITE,
                    hasMakerFees = false,
                    makerFeeAt50c = 0.0,
                    makerFeeAt95c = 0.0,
                    takerFeeAt50c = 1.75,
                    takerFeeAt95c = 0.33,
                    makerFeeImpact = 0.0,
                    takerFeeImpact = -1.75,
                    isStrategyViable = true,
                    recommendation = "Unknown"
                )
            }
            results[strategy.getName()] = feeAnalysis
        }
        
        return results
    }
    
    /**
     * Clear all traded market tracking.
     */
    fun clearTradedMarkets() {
        tradedMarkets.clear()
    }
}

/**
 * Base interface for all settlement-based strategies.
 */
interface BaseSettlementStrategy {
    
    /**
     * Evaluate a market for this strategy.
     */
    suspend fun evaluate(
        market: MarketUiModel,
        currentBRTI: Double,
        nowMs: Long
    ): com.dirk.kalshiodds.signal.settlement.StrategyResult?
    
    /**
     * Get the rank of this strategy (lower = higher priority).
     */
    fun getRank(): Int
    
    /**
     * Get the name of this strategy.
     */
    fun getName(): String
    
    /**
     * Get the status of this strategy.
     */
    suspend fun getStatus(): StrategyStatus
}

/**
 * Result of evaluating a market across all strategies.
 */
data class StrategyEvaluation(
    val strategy: BaseSettlementStrategy,
    val result: com.dirk.kalshiodds.signal.settlement.StrategyResult,
    val market: MarketUiModel,
    val priority: Int
)

/**
 * Extension functions for easy access.
 */

/**
 * Create a settlement strategy manager with default components.
 */
fun createSettlementStrategyManager(
    settlementTracker: BRTISettlementTracker,
    queueMonitor: QueuePositionMonitor,
    feeVerifier: FeeTypeVerifier
): SettlementStrategyManager {
    return SettlementStrategyManager(settlementTracker, queueMonitor, feeVerifier)
}

/**
 * Check if a market should be traded by any settlement strategy.
 */
suspend fun MarketUiModel.shouldTradeSettlement(
    settlementTracker: BRTISettlementTracker,
    currentBRTI: Double,
    nowMs: Long
): Boolean {
    val manager = SettlementStrategyManager(settlementTracker, QueuePositionMonitor(
        com.dirk.kalshiodds.data.api.KalshiTradeApi(
            okhttp3.OkHttpClient(),
            "https://trade-api.kalshi.com/"
        ),
        { Pair("", "") }
    ), FeeTypeVerifier(
        com.dirk.kalshiodds.data.api.KalshiTradeApi(
            okhttp3.OkHttpClient(),
            "https://trade-api.kalshi.com/"
        ),
        { Pair("", "") }
    ))
    
    return manager.evaluateMarket(this, currentBRTI, nowMs) != null
}
