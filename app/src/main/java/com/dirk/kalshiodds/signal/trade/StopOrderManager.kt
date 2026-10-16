package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages stop orders (trailing stops and stop losses) for paper trading.
 * Automatically executes sell orders when stops are triggered.
 * 
 * UPDATED: Now uses absolute cents for trailing stop (default 3c).
 * Example: If price goes to 90c, trailing stop is at 87c.
 * When price drops to 87c, it auto-sells.
 * 
 * This is for paper trading experimentation only - no real money.
 */
class StopOrderManager(
    private val paperBook: PaperBook,
    private val trailingStopCents: Double = 3.0,    // 3 cent trailing stop
    private val stopLossPct: Double = 3.0,         // 3% stop loss (more reasonable for prediction markets)
    private val minProfitCents: Double = 0.0,      // No minimum - active from entry
    private val autoExecute: Boolean = true
) {

    data class StopConfig(
        val trailingStopCents: Double = 3.0,
        val stopLossPct: Double = 3.0,         // 3% stop loss default
        val minProfitCents: Double = 0.0,
        val autoExecute: Boolean = true,
        val enabled: Boolean = true
    )

    data class StopExecution(
        val fillId: String,
        val ticker: String,
        val side: String,
        val entryPrice: Double,
        val stopPrice: Double,
        val currentPrice: Double,
        val isTrailing: Boolean,
        val isStopLoss: Boolean,
        val executedAt: Long,
        val pnlAtStop: Double
    )

    private val _executions = MutableStateFlow<List<StopExecution>>(emptyList())
    val executions: StateFlow<List<StopExecution>> = _executions.asStateFlow()

    /**
     * Check all open positions and execute stops if triggered
     */
    fun checkAndExecute(currentPrices: Map<String, Double>): List<StopExecution> {
        if (!enabled()) return emptyList()
        
        val currentState = paperBook.snapshot()
        val manager = currentState.getTrailingStopManager(
            trailingStopCents, stopLossPct, minProfitCents
        )
        
        val stopOrders = manager.checkPositions(currentPrices)
        val newExecutions = mutableListOf<StopExecution>()
        
        stopOrders.forEach { order ->
            val execution = executeStop(order, currentPrices[order.ticker] ?: return@forEach)
            if (execution != null) {
                newExecutions.add(execution)
                _executions.value = _executions.value + execution
            }
        }
        
        return newExecutions
    }

    /**
     * Execute a stop order (create a paper sell)
     */
    private fun executeStop(order: TrailingStop.StopOrder, currentPrice: Double): StopExecution? {
        val currentState = paperBook.snapshot()
        val fill = currentState.fills.firstOrNull { it.id == order.fillId } ?: return null
        
        // Calculate P&L at stop
        val pnlAtStop = calculatePnLAtStop(fill, order.stopPrice, order.isTrailing)
        
        // Create a paper sell
        paperBook.forceFill(
            ticker = fill.ticker,
            side = if (fill.side == "YES") "NO" else "YES",
            limitPrice = currentPrice,
            contracts = fill.contracts,
            source = "STOP ${if (order.isTrailing) "TRAILING" else "LOSS"}",
            note = "Auto-stop executed: ${fill.side} @ ${order.stopPrice} (current: $currentPrice)"
        )
        
        val execution = StopExecution(
            fillId = fill.id,
            ticker = fill.ticker,
            side = fill.side,
            entryPrice = order.entryPrice,
            stopPrice = order.stopPrice,
            currentPrice = currentPrice,
            isTrailing = order.isTrailing,
            isStopLoss = order.isStopLoss,
            executedAt = System.currentTimeMillis(),
            pnlAtStop = pnlAtStop
        )
        
        return execution
    }

    /**
     * Calculate P&L at the stop price
     */
    private fun calculatePnLAtStop(
        fill: PaperFill,
        stopPrice: Double,
        isTrailing: Boolean
    ): Double {
        val side = fill.side.uppercase()
        val entryPrice = fill.limitPrice
        
        return if (side == "YES") {
            // For YES: P&L = (stopPrice - entryPrice) * contracts
            (stopPrice - entryPrice) * fill.contracts
        } else {
            // For NO: entryPrice is NO ask, so YES bid = 1 - entryPrice
            val yesEntry = 1.0 - entryPrice
            (yesEntry - stopPrice) * fill.contracts
        }
    }

    /**
     * Get all active stop levels for display
     */
    fun getActiveStops(currentPrices: Map<String, Double>): List<TrailingStop.PositionState> {
        val currentState = paperBook.snapshot()
        val manager = currentState.getTrailingStopManager(
            trailingStopCents, stopLossPct, minProfitCents
        )
        return manager.getPositionsWithStops(currentPrices)
    }

    /**
     * Get stop configuration
     */
    fun getConfig(): StopConfig = StopConfig(
        trailingStopCents = trailingStopCents,
        stopLossPct = stopLossPct,
        minProfitCents = minProfitCents,
        autoExecute = autoExecute,
        enabled = true
    )

    /**
     * Update stop configuration
     */
    fun updateConfig(config: StopConfig): StopOrderManager {
        return StopOrderManager(
            paperBook,
            config.trailingStopCents,
            config.stopLossPct,
            config.minProfitCents,
            config.autoExecute
        )
    }

    /**
     * Check if stop orders are enabled
     */
    fun enabled(): Boolean = true

    /**
     * Get all stop executions
     */
    fun getExecutions(): List<StopExecution> = _executions.value

    /**
     * Clear stop executions
     */
    fun clearExecutions() {
        _executions.value = emptyList()
    }
}

/**
 * Extension function to create a stop order manager from a paper book
 */
fun PaperBook.getStopOrderManager(
    trailingStopCents: Double = 3.0,
    stopLossPct: Double = 3.0,         // 3% stop loss (more reasonable for prediction markets)
    minProfitCents: Double = 0.0,
    autoExecute: Boolean = true
): StopOrderManager {
    return StopOrderManager(this, trailingStopCents, stopLossPct, minProfitCents, autoExecute)
}

/**
 * Default stop configuration
 */
object DefaultStopConfig {
    const val TRAILING_STOP_CENTS = 3.0
    const val STOP_LOSS_PCT = 3.0       // Changed from 20% to 3% for prediction markets
    const val MIN_PROFIT_CENTS = 0.0
    const val AUTO_EXECUTE = true
    const val ENABLED = true
}
