package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Trailing stop and stop-loss management for paper trading.
 * 
 * Features:
 * - Trailing stop: locks in profits as the position moves favorably
 * - Stop loss: hard stop to limit losses
 * - Auto-sell when stop is hit
 * 
 * NEW: Trailing stop now uses absolute cents (default 3c) instead of percentage.
 * This means: if price goes to 90c and stays, trailing stop is at 87c.
 * When price drops to 87c, it sells automatically.
 * 
 * This is for paper trading experimentation only - no real money.
 */
class TrailingStop(
    private val paperState: PaperBookState,
    private val trailingStopCents: Double = 3.0,  // 3 cent trailing stop (0.03)
    private val stopLossPct: Double = 3.0,       // 3% stop loss (more reasonable for prediction markets)
    private val minProfitCents: Double = 0.0      // No minimum - trailing stop active from entry
) {

    data class StopConfig(
        val trailingStopCents: Double = 3.0,
        val stopLossPct: Double = 3.0,         // 3% stop loss default
        val minProfitCents: Double = 0.0,
        val enabled: Boolean = true
    )

    data class StopOrder(
        val fillId: String,
        val ticker: String,
        val side: String,
        val entryPrice: Double,
        val stopPrice: Double,
        val isTrailing: Boolean,
        val isStopLoss: Boolean,
        val triggerPrice: Double? = null,
        val activatedAt: Long? = null
    )

    data class PositionState(
        val fill: PaperFill,
        val currentPrice: Double?,
        val entryPrice: Double,
        val pnl: Double,
        val pnlPct: Double,
        val trailingStopPrice: Double?,
        val stopLossPrice: Double?,
        val stopHit: Boolean,
        val stopType: String?,
        val peakPrice: Double?,
        val trailingDistanceCents: Double?
    )

    /**
     * Check all open positions and return stop order recommendations
     */
    fun checkPositions(currentPrices: Map<String, Double>): List<StopOrder> {
        if (!enabled()) return emptyList()
        
        val stops = mutableListOf<StopOrder>()
        val now = System.currentTimeMillis()
        
        paperState.fills
            .filter { !it.settled }
            .forEach { fill ->
                val currentPrice = currentPrices[fill.ticker.uppercase()] ?: return@forEach
                val positionState = calculatePositionState(fill, currentPrice)
                
                // Check if stop-loss is hit
                positionState.stopLossPrice?.let { stopLossPrice ->
                    val shouldStopLoss = shouldTriggerStopLoss(fill.side, currentPrice, stopLossPrice)
                    if (shouldStopLoss) {
                        stops.add(StopOrder(
                            fillId = fill.id,
                            ticker = fill.ticker,
                            side = fill.side,
                            entryPrice = positionState.entryPrice,
                            stopPrice = stopLossPrice,
                            isTrailing = false,
                            isStopLoss = true,
                            triggerPrice = currentPrice,
                            activatedAt = now
                        ))
                    }
                }
                
                // Check if trailing stop is hit
                positionState.trailingStopPrice?.let { trailingStopPrice ->
                    val shouldTrailing = shouldTriggerTrailing(fill.side, currentPrice, trailingStopPrice)
                    if (shouldTrailing) {
                        stops.add(StopOrder(
                            fillId = fill.id,
                            ticker = fill.ticker,
                            side = fill.side,
                            entryPrice = positionState.entryPrice,
                            stopPrice = trailingStopPrice,
                            isTrailing = true,
                            isStopLoss = false,
                            triggerPrice = currentPrice,
                            activatedAt = now
                        ))
                    }
                }
            }
        
        return stops
    }

    /**
     * Calculate position state with stop levels
     * 
     * For YES positions:
     * - Trailing stop = highest price seen - trailingStopCents
     * - If price drops to trailing stop, sell
     * 
     * For NO positions:
     * - Trailing stop = lowest price seen + trailingStopCents
     * - If price rises to trailing stop, sell
     */
    fun calculatePositionState(fill: PaperFill, currentPrice: Double): PositionState {
        val entryPrice = fill.limitPrice
        val side = fill.side.uppercase()
        
        // Calculate P&L
        val pnl = if (side == "YES") {
            (currentPrice - entryPrice) * fill.contracts
        } else {
            val yesEntry = 1.0 - entryPrice
            (yesEntry - currentPrice) * fill.contracts
        }
        
        val stake = fill.stakeUsd
        val pnlPct = if (stake > 0) (pnl / stake) * 100 else 0.0
        
        // Calculate stop-loss price (always active)
        val stopLossPrice = calculateStopLossPrice(side, entryPrice, fill.stakeUsd, stopLossPct)
        
        // For trailing stop: track the peak/low and subtract/add trailingStopCents
        // The key insight: we want to sell when price moves AGAINST us by trailingStopCents
        val (trailingStopPrice, peakPrice) = if (side == "YES") {
            // For YES: we want to sell if price drops by trailingStopCents from the highest point
            val peak = max(entryPrice, currentPrice)
            val stop = (peak - trailingStopCents / 100.0).coerceIn(0.0, 1.0)
            Pair(stop, peak)
        } else {
            // For NO: we want to sell if price rises by trailingStopCents from the lowest point
            val yesEntry = 1.0 - entryPrice
            val low = min(yesEntry, currentPrice)
            val stop = (low + trailingStopCents / 100.0).coerceIn(0.0, 1.0)
            Pair(stop, low)
        }
        
        // Check if any stop is hit
        val stopHit = stopLossPrice?.let { sl ->
            shouldTriggerStopLoss(side, currentPrice, sl)
        } ?: trailingStopPrice?.let { ts ->
            shouldTriggerTrailing(side, currentPrice, ts)
        } ?: false
        
        val stopType = when {
            stopLossPrice != null && shouldTriggerStopLoss(side, currentPrice, stopLossPrice) -> "STOP LOSS"
            trailingStopPrice != null && shouldTriggerTrailing(side, currentPrice, trailingStopPrice) -> "TRAILING STOP"
            else -> null
        }
        
        return PositionState(
            fill = fill,
            currentPrice = currentPrice,
            entryPrice = entryPrice,
            pnl = pnl,
            pnlPct = pnlPct,
            trailingStopPrice = trailingStopPrice,
            stopLossPrice = stopLossPrice,
            stopHit = stopHit,
            stopType = stopType,
            peakPrice = peakPrice,
            trailingDistanceCents = trailingStopCents
        )
    }

    /**
     * Calculate stop-loss price for a position
     */
    private fun calculateStopLossPrice(
        side: String,
        entryPrice: Double,
        stake: Double,
        stopLossPct: Double
    ): Double {
        return if (side == "YES") {
            // For YES: stop loss is entryPrice minus stopLossPct
            (entryPrice - (entryPrice * stopLossPct / 100)).coerceIn(0.0, 1.0)
        } else {
            // For NO: stop loss is entryPrice plus stopLossPct
            val yesEntry = 1.0 - entryPrice
            (yesEntry + (yesEntry * stopLossPct / 100)).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Check if stop-loss should trigger
     */
    private fun shouldTriggerStopLoss(side: String, currentPrice: Double, stopPrice: Double): Boolean {
        return if (side == "YES") {
            // For YES: trigger if currentPrice <= stopPrice
            currentPrice <= stopPrice
        } else {
            // For NO: trigger if currentPrice >= stopPrice
            currentPrice >= stopPrice
        }
    }

    /**
     * Check if trailing stop should trigger
     */
    private fun shouldTriggerTrailing(side: String, currentPrice: Double, stopPrice: Double): Boolean {
        return if (side == "YES") {
            // For YES: trigger if currentPrice <= stopPrice
            currentPrice <= stopPrice
        } else {
            // For NO: trigger if currentPrice >= stopPrice
            currentPrice >= stopPrice
        }
    }

    /**
     * Get all positions with their stop status
     */
    fun getPositionsWithStops(currentPrices: Map<String, Double>): List<PositionState> {
        return paperState.fills
            .filter { !it.settled }
            .mapNotNull { fill ->
                currentPrices[fill.ticker.uppercase()]?.let { price ->
                    calculatePositionState(fill, price)
                }
            }
    }

    /**
     * Check if trailing stops are enabled
     */
    fun enabled(): Boolean = true

    /**
     * Get stop configuration
     */
    fun getConfig(): StopConfig = StopConfig(
        trailingStopCents = trailingStopCents,
        stopLossPct = stopLossPct,
        minProfitCents = minProfitCents,
        enabled = true
    )

    /**
     * Update stop configuration
     */
    fun updateConfig(config: StopConfig): TrailingStop {
        return TrailingStop(
            paperState,
            config.trailingStopCents,
            config.stopLossPct,
            config.minProfitCents
        )
    }
}

/**
 * Extension functions for easy access
 */
fun PaperBookState.getTrailingStopManager(
    trailingStopCents: Double = 3.0,
    stopLossPct: Double = 3.0,         // 3% stop loss (more reasonable for prediction markets)
    minProfitCents: Double = 0.0
): TrailingStop {
    return TrailingStop(this, trailingStopCents, stopLossPct, minProfitCents)
}
