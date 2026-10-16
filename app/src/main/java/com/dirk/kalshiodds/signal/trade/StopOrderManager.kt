package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
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
 * This is for paper trading experimentation only - no real money.
 */
class StopOrderManager(
    private val paperBook: PaperBook,
    private val trailingStopPct: Double = 10.0,
    private val stopLossPct: Double = 20.0,
    private val minProfitPct: Double = 5.0,
    private val autoExecute: Boolean = true
) {

    data class StopConfig(
        val trailingStopPct: Double = 10.0,
        val stopLossPct: Double = 20.0,
        val minProfitPct: Double = 5.0,
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

    private val _triggeredStops = MutableStateFlow<List<StopOrderManager.StopExecution>>(emptyList())

    /**
     * Check all open positions and execute stops if triggered
     */
    fun checkAndExecute(currentPrices: Map<String, Double>): List<StopExecution> {
        if (!enabled()) return emptyList()
        
        val currentState = paperBook.snapshot()
        val manager = currentState.getTrailingStopManager(
            trailingStopPct, stopLossPct, minProfitPct
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
        val sellFill = createSellFill(fill, currentPrice, order)
        
        // Add sell to paper book
        paperBook.forceFill(
            ticker = fill.ticker,
            side = if (fill.side == "YES") "NO" else "YES",
            limitPrice = currentPrice,
            contracts = fill.contracts,
            source = "STOP ${if (order.isTrailing) "TRAILING" else "LOSS"}",
            note = "Auto-stop executed: ${order.side} @ ${order.stopPrice} (current: $currentPrice)"
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
            // P&L = (yesEntry - stopPrice) * contracts
            val yesEntry = 1.0 - entryPrice
            (yesEntry - stopPrice) * fill.contracts
        }
    }

    /**
     * Create a sell fill record for the stop
     */
    private fun createSellFill(
        buyFill: PaperFill,
        currentPrice: Double,
        stopOrder: TrailingStop.StopOrder
    ): PaperFill {
        return PaperFill(
            id = "STOP-${buyFill.id}-${System.currentTimeMillis()}",
            ticker = buyFill.ticker,
            side = if (buyFill.side == "YES") "NO" else "YES",
            stakeUsd = buyFill.stakeUsd,
            contracts = buyFill.contracts,
            limitPrice = currentPrice,
            source = "STOP ${if (stopOrder.isTrailing) "TRAILING" else "LOSS"}",
            createdAtMs = System.currentTimeMillis(),
            settled = false,
            note = "Auto-stop: ${stopOrder.side} @ ${stopOrder.stopPrice} (triggered at $currentPrice)"
        )
    }

    /**
     * Get all active stop levels for display
     */
    fun getActiveStops(currentPrices: Map<String, Double>): List<TrailingStop.PositionState> {
        val currentState = paperBook.snapshot()
        val manager = currentState.getTrailingStopManager(
            trailingStopPct, stopLossPct, minProfitPct
        )
        return manager.getPositionsWithStops(currentPrices)
    }

    /**
     * Get stop configuration
     */
    fun getConfig(): StopConfig = StopConfig(
        trailingStopPct = trailingStopPct,
        stopLossPct = stopLossPct,
        minProfitPct = minProfitPct,
        autoExecute = autoExecute,
        enabled = true
    )

    /**
     * Update stop configuration
     */
    fun updateConfig(config: StopConfig): StopOrderManager {
        return StopOrderManager(
            paperBook,
            config.trailingStopPct,
            config.stopLossPct,
            config.minProfitPct,
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
    trailingStopPct: Double = 10.0,
    stopLossPct: Double = 20.0,
    minProfitPct: Double = 5.0,
    autoExecute: Boolean = true
): StopOrderManager {
    return StopOrderManager(this, trailingStopPct, stopLossPct, minProfitPct, autoExecute)
}

/**
 * Default stop configuration
 */
object DefaultStopConfig {
    const val TRAILING_STOP_PCT = 10.0
    const val STOP_LOSS_PCT = 20.0
    const val MIN_PROFIT_PCT = 5.0
    const val AUTO_EXECUTE = true
    const val ENABLED = true
}
