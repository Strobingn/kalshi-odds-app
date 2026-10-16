package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import kotlin.math.abs

/**
 * Trailing stop and stop-loss management for paper trading.
 * 
 * Features:
 * - Trailing stop: locks in profits as the position moves favorably
 * - Stop loss: hard stop to limit losses
 * - Auto-sell when stop is hit
 * 
 * This is for paper trading experimentation only - no real money.
 */
class TrailingStop(
    private val paperState: PaperBookState,
    private val trailingStopPct: Double = 10.0,  // 10% trailing stop
    private val stopLossPct: Double = 20.0,      // 20% stop loss
    private val minProfitPct: Double = 5.0       // Minimum profit to activate trailing stop
) {

    data class StopConfig(
        val trailingStopPct: Double = 10.0,
        val stopLossPct: Double = 20.0,
        val minProfitPct: Double = 5.0,
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
        val stopType: String?
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
     */
    fun calculatePositionState(fill: PaperFill, currentPrice: Double): PositionState {
        val entryPrice = fill.limitPrice
        val side = fill.side.uppercase()
        
        // Calculate P&L
        val pnl = if (side == "YES") {
            // For YES: profit if currentPrice > entryPrice
            (currentPrice - entryPrice) * fill.contracts
        } else {
            // For NO: profit if currentPrice < (1 - entryPrice)
            // NO position means we bet on price going down
            // entryPrice for NO is the NO ask (which is 1 - YES bid)
            // So if YES price goes down, NO value goes up
            val yesEntry = 1.0 - entryPrice
            (yesEntry - currentPrice) * fill.contracts
        }
        
        val stake = fill.stakeUsd
        val pnlPct = if (stake > 0) (pnl / stake) * 100 else 0.0
        
        // Calculate stop-loss price (always active)
        val stopLossPrice = calculateStopLossPrice(side, entryPrice, fill.stakeUsd, stopLossPct)
        
        // Calculate trailing stop price (only active when in profit)
        val trailingStopPrice = if (abs(pnlPct) >= minProfitPct) {
            calculateTrailingStopPrice(side, entryPrice, currentPrice, fill.stakeUsd, trailingStopPct)
        } else {
            null
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
            stopType = stopType
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
            // If YES price drops below this, we sell
            (entryPrice - (entryPrice * stopLossPct / 100)).coerceIn(0.0, 1.0)
        } else {
            // For NO: stop loss is entryPrice plus stopLossPct
            // entryPrice for NO is the NO ask (1 - YES bid)
            // If YES price rises above (1 - entryPrice + stopLoss), we sell
            val yesEntry = 1.0 - entryPrice
            (yesEntry + (yesEntry * stopLossPct / 100)).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Calculate trailing stop price for a position
     */
    private fun calculateTrailingStopPrice(
        side: String,
        entryPrice: Double,
        currentPrice: Double,
        stake: Double,
        trailingStopPct: Double
    ): Double {
        return if (side == "YES") {
            // For YES: trailing stop follows the high
            // Stop is at currentPrice minus trailingStopPct from the peak
            val peak = maxOf(entryPrice, currentPrice)
            (peak - (peak * trailingStopPct / 100)).coerceIn(0.0, 1.0)
        } else {
            // For NO: trailing stop follows the low
            // entryPrice for NO is the NO ask
            // Stop is at (1 - currentPrice) plus trailingStopPct from the low
            val yesEntry = 1.0 - entryPrice
            val yesCurrent = currentPrice
            val low = minOf(yesEntry, yesCurrent)
            (low + (low * trailingStopPct / 100)).coerceIn(0.0, 1.0)
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
            // For NO: trigger if currentPrice >= (1 - stopPrice)
            // stopPrice for NO is in YES terms
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
        trailingStopPct = trailingStopPct,
        stopLossPct = stopLossPct,
        minProfitPct = minProfitPct,
        enabled = true
    )

    /**
     * Update stop configuration
     */
    fun updateConfig(config: StopConfig): TrailingStop {
        return TrailingStop(
            paperState,
            config.trailingStopPct,
            config.stopLossPct,
            config.minProfitPct
        )
    }
}

/**
 * Extension functions for easy access
 */
fun PaperBookState.getTrailingStopManager(
    trailingStopPct: Double = 10.0,
    stopLossPct: Double = 20.0,
    minProfitPct: Double = 5.0
): TrailingStop {
    return TrailingStop(this, trailingStopPct, stopLossPct, minProfitPct)
}
