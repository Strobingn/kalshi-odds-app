package com.dirk.kalshiodds.signal.settlement

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.signal.trade.TicketKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rank 1 Strategy: Averaging-Aware Late Favorite
 * 
 * This is the highest-priority strategy based on the research.
 * 
 * Key Insight:
 * KXBTC15M settles based on the simple average of 60 one-second BRTI prints.
 * With m prints remaining, the effective price is:
 *   E = (Σₖ + m·S_now)/60
 * 
 * The variance collapses rapidly:
 * - 60 prints left: σ ≈ 3.63 bp
 * - 30 prints left: σ ≈ 1.30 bp
 * - 10 prints left: σ ≈ 0.26 bp
 * 
 * This means the outcome is effectively decided 20-40 seconds before the close.
 * 
 * Strategy:
 * 1. Track BRTI prints in real-time
 * 2. Calculate effective price E and settlement probability
 * 3. Buy when ask < fair_value - fee_buffer - safety_margin
 * 4. Only trade when probability >= 95% (configurable)
 * 5. Size: minimum 3 contracts to avoid fee rounding issues
 * 
 * Expected Edge: 1-4¢ per contract on triggered trades
 * 
 * @see <a href="https://arxiv.org/abs/2607.26245">OpenMarket Research</a>
 * @see <a href="https://www.botforkalshi.com/blog/kalshi-bitcoin-bot">BotForKalshi Analysis</a>
 */
class AveragingAwareStrategy(
    private val settlementTracker: BRTISettlementTracker,
    private val feeVerifier: FeeTypeVerifier,
    private val config: Config = Config()
) {
    
    /**
     * Configuration for the averaging-aware strategy.
     */
    data class Config(
        /** Minimum probability threshold for trading (default: 95%) */
        val minProbability: Double = 0.95,
        
        /** Minimum edge in cents before trading (default: 0.5c) */
        val minEdgeCents: Double = 0.5,
        
        /** Safety buffer in cents (default: 0.5c) */
        val safetyBufferCents: Double = 0.5,
        
        /** Minimum contracts per order (default: 3 to avoid fee rounding) */
        val minContracts: Int = 3,
        
        /** Maximum contracts per order (default: 10) */
        val maxContracts: Int = 10,
        
        /** Only trade in the last N seconds (default: 60 = full window) */
        val maxSecondsBeforeClose: Int = 60,
        
        /** Minimum seconds before close to trade (default: 5 = 5 prints left) */
        val minSecondsBeforeClose: Int = 5,
        
        /** Whether to require front-of-queue for maker orders */
        val requireFrontOfQueue: Boolean = true,
        
        /** Whether to use maker orders when possible */
        val useMakerOrders: Boolean = false
    )
    
    /**
     * Check if a market passes the averaging-aware strategy filters.
     * 
     * @param market The market to evaluate
     * @param currentBRTI Current BRTI value
     * @param nowMs Current time in milliseconds
     * @return Strategy result with signal or null
     */
    suspend fun evaluate(
        market: MarketUiModel,
        currentBRTI: Double,
        nowMs: Long
    ): StrategyResult? {
        // Skip if strategy is disabled
        if (!config.minProbability > 0) return null
        
        val closeTime = market.closeTimeEpochMs ?: return null
        val timeLeftMs = closeTime - nowMs
        val timeLeftSeconds = timeLeftMs / 1000
        
        // Check time window
        if (timeLeftSeconds > config.maxSecondsBeforeClose) {
            return StrategyResult(
                market = market,
                passes = false,
                reason = "Too early (${timeLeftSeconds}s before close, max ${config.maxSecondsBeforeClose}s)"
            )
        }
        
        if (timeLeftSeconds < config.minSecondsBeforeClose) {
            return StrategyResult(
                market = market,
                passes = false,
                reason = "Too close to settlement (${timeLeftSeconds}s left, min ${config.minSecondsBeforeClose}s)"
            )
        }
        
        // Get settlement probability
        val prob = settlementTracker.getSettlementProbability(
            ticker = market.ticker,
            currentBRTI = currentBRTI,
            target = market.yesTarget ?: 0.0,
            closeTimeEpochMs = closeTime
        ) ?: return StrategyResult(
            market = market,
            passes = false,
            reason = "No settlement probability data"
        )
        
        // Check probability threshold
        val bestProbability = max(prob.probabilityYes, prob.probabilityNo)
        if (bestProbability < config.minProbability) {
            return StrategyResult(
                market = market,
                passes = false,
                reason = "Probability too low (${"%.1f%%".format(bestProbability * 100)} < ${config.minProbability * 100}%)"
            )
        }
        
        // Determine best side
        val (side, fairValue, askPrice, bidPrice) = if (prob.probabilityYes > prob.probabilityNo) {
            // YES is favored
            val ask = market.yesAsk ?: return StrategyResult(
                market = market,
                passes = false,
                reason = "No YES ask price"
            )
            val bid = market.yesBid ?: 0.0
            Triple("YES", prob.fairValueYes, ask, bid)
        } else {
            // NO is favored
            val ask = market.noAsk ?: return StrategyResult(
                market = market,
                passes = false,
                reason = "No NO ask price"
            )
            val bid = market.noBid ?: 0.0
            Triple("NO", prob.fairValueNo, ask, bid)
        }
        
        // Calculate edge
        val fairValueCents = fairValue * 100
        val askCents = askPrice * 100
        val feeCents = getTakerFeeCents(askPrice)
        
        val edgeCents = fairValueCents - askCents - feeCents - config.safetyBufferCents
        
        if (edgeCents < config.minEdgeCents) {
            return StrategyResult(
                market = market,
                passes = false,
                reason = "Edge too small (${"%.2f".format(edgeCents)}c < ${config.minEdgeCents}c)"
            )
        }
        
        // Calculate contracts based on edge and risk
        val contracts = calculateContracts(edgeCents, market)
        
        // Calculate stake
        val stakeUsd = contracts * askPrice
        
        // Create trade signal
        val signal = TradeSignal(
            ticker = market.ticker,
            windowId = prob.windowId,
            side = side,
            askPrice = askPrice,
            fairValue = fairValue,
            probability = bestProbability,
            edgeCents = edgeCents,
            edgeUsd = edgeCents / 100.0,
            feeCents = feeCents,
            feeUsd = feeCents / 100.0,
            printsRemaining = prob.printsRemaining,
            effectivePrice = prob.effectivePrice,
            target = prob.target,
            currentBRTI = prob.currentBRTI,
            minContracts = config.minContracts,
            recommendation = when {
                edgeCents >= 3.0 -> "STRONG_BUY"
                edgeCents >= 1.0 -> "BUY"
                else -> "WEAK_BUY"
            }
        )
        
        return StrategyResult(
            market = market,
            passes = true,
            side = side,
            fairValue = fairValue,
            askPrice = askPrice,
            probability = bestProbability,
            edgeCents = edgeCents,
            contracts = contracts,
            stakeUsd = stakeUsd,
            signal = signal,
            reason = "Averaging-aware late favorite: ${"%.1f%%".format(bestProbability * 100)} probability, +${"%.2f".format(edgeCents)}c edge"
        )
    }
    
    /**
     * Create a trade ticket for the averaging-aware strategy.
     */
    suspend fun createTicket(
        market: MarketUiModel,
        currentBRTI: Double,
        nowMs: Long
    ): TradeTicket? {
        val result = evaluate(market, currentBRTI, nowMs) ?: return null
        
        if (!result.passes) return null
        
        val side = result.side
        val askPrice = result.askPrice
        val contracts = result.contracts
        val stakeUsd = result.stakeUsd
        
        return TradeTicket(
            id = java.util.UUID.randomUUID().toString(),
            ticker = market.ticker,
            side = side,
            bookSide = if (side == "YES") "bid" else "ask",
            stakeUsd = stakeUsd,
            limitPrice = askPrice,
            yesLimitPrice = if (side == "YES") askPrice else 1.0 - askPrice,
            contracts = contracts,
            estimatedFillUsd = stakeUsd,
            maxPayoutUsd = contracts * 1.0,  // Each contract settles at $1
            estimatedAvgFill = askPrice,
            netEvUsd = result.edgeUsd * contracts,
            netEvPerContract = result.edgeUsd,
            netEdgePp = result.edgeCents * 100,  // Convert to percentage points
            title = "Averaging-Aware Late Favorite",
            sizingNote = "Sized for ${contracts}x at +${"%.2f".format(result.edgeCents)}c edge",
            gateNote = "Probability: ${"%.1f%%".format(result.probability * 100)}",
            kind = TicketKind.WINNING_STRATEGY,
            paperOnly = true  // Start with paper trading
        )
    }
    
    /**
     * Calculate the number of contracts to trade based on edge and risk.
     */
    private fun calculateContracts(edgeCents: Double, market: MarketUiModel): Int {
        // Base contracts based on edge size (larger edge = more confidence = more size)
        val edgeBased = max(config.minContracts, min(config.maxContracts, 
            (edgeCents / 0.5).toInt()  // +0.5c edge = 1 contract, +1c = 2 contracts, etc.
        ))
        
        // Cap by available liquidity
        val liquidity = market.yesLiquidity ?: market.noLiquidity ?: Double.MAX_VALUE
        val liquidityBased = min(config.maxContracts, (liquidity / 10).toInt())
        
        // Use minimum of the two
        return min(edgeBased, liquidityBased)
    }
    
    /**
     * Get taker fee in cents for a given price.
     */
    private suspend fun getTakerFeeCents(price: Double): Double {
        // Use cached fee info or calculate directly
        val priceCents = (price * 100).toInt()
        return BRTIConstants.TAKER_FEES_PER_CONTRACT[priceCents] ?: 
            BRTIConstants.TAKER_FEES_PER_CONTRACT[50] ?: 1.75
    }
    
    /**
     * Get the strategy configuration.
     */
    fun getConfig(): Config = config
    
    /**
     * Update the strategy configuration.
     */
    fun updateConfig(newConfig: Config): AveragingAwareStrategy {
        return AveragingAwareStrategy(settlementTracker, feeVerifier, newConfig)
    }
    
    /**
     * Check if the strategy can be used (fee structure allows it).
     */
    suspend fun isViable(ticker: String = "KXBTC15M"): Boolean {
        val feeAnalysis = feeVerifier.getFeeImpactAnalysis(ticker, StrategyType.TAKER_LATE_FAVORITE)
        return feeAnalysis.isStrategyViable
    }
    
    /**
     * Get the current status of the strategy.
     */
    suspend fun getStatus(): StrategyStatus {
        val isViable = isViable()
        val feeAnalysis = feeVerifier.getFeeImpactAnalysis("KXBTC15M", StrategyType.TAKER_LATE_FAVORITE)
        
        return StrategyStatus(
            strategyName = "Averaging-Aware Late Favorite",
            rank = 1,
            isEnabled = config.minProbability > 0,
            isViable = isViable,
            feeAnalysis = feeAnalysis,
            config = config,
            description = "Trades based on BRTI settlement average calculation. " +
                         "Buys when ask < fair value with high probability."
        )
    }
}

/**
 * Result of evaluating a market with the averaging-aware strategy.
 */
data class StrategyResult(
    val market: MarketUiModel,
    val passes: Boolean,
    val side: String = "",
    val fairValue: Double = 0.0,
    val askPrice: Double = 0.0,
    val probability: Double = 0.0,
    val edgeCents: Double = 0.0,
    val contracts: Int = 0,
    val stakeUsd: Double = 0.0,
    val signal: TradeSignal? = null,
    val reason: String = ""
)

/**
 * Status of the averaging-aware strategy.
 */
data class StrategyStatus(
    val strategyName: String,
    val rank: Int,
    val isEnabled: Boolean,
    val isViable: Boolean,
    val feeAnalysis: FeeImpactAnalysis,
    val config: AveragingAwareStrategy.Config,
    val description: String
)

/**
 * Pre-configured presets for the averaging-aware strategy.
 */
object AveragingAwarePresets {
    
    /**
     * Conservative: High probability threshold, larger edge requirement.
     */
    val CONSERVATIVE = AveragingAwareStrategy.Config(
        minProbability = 0.98,
        minEdgeCents = 1.0,
        safetyBufferCents = 1.0,
        minContracts = 3,
        maxContracts = 5,
        maxSecondsBeforeClose = 45,
        minSecondsBeforeClose = 10
    )
    
    /**
     * Aggressive: Lower probability threshold, smaller edge requirement.
     */
    val AGGRESSIVE = AveragingAwareStrategy.Config(
        minProbability = 0.90,
        minEdgeCents = 0.3,
        safetyBufferCents = 0.3,
        minContracts = 3,
        maxContracts = 15,
        maxSecondsBeforeClose = 60,
        minSecondsBeforeClose = 5
    )
    
    /**
     * Balanced: Default settings.
     */
    val BALANCED = AveragingAwareStrategy.Config(
        minProbability = 0.95,
        minEdgeCents = 0.5,
        safetyBufferCents = 0.5,
        minContracts = 3,
        maxContracts = 10,
        maxSecondsBeforeClose = 60,
        minSecondsBeforeClose = 5
    )
    
    /**
     * Front-of-Queue Maker: For maker strategies.
     */
    val MAKER_FRONT_OF_QUEUE = AveragingAwareStrategy.Config(
        minProbability = 0.95,
        minEdgeCents = 0.15,  // Maker edge is smaller
        safetyBufferCents = 0.2,
        minContracts = 1,
        maxContracts = 5,
        maxSecondsBeforeClose = 120,  // Can start earlier as maker
        minSecondsBeforeClose = 10,
        useMakerOrders = true,
        requireFrontOfQueue = true
    )
}
