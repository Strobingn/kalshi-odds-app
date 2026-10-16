package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Executes paper-only exits against the held-side bid and displayed depth. */
class StopOrderManager(
    private val paperBook: PaperBook,
    private val config: StopConfig = StopConfig(),
    private val autoExecute: Boolean = true
) {
    data class StopConfig(
        val trailingStopPct: Double = SignalConstants.DEFAULT_TRAILING_STOP_PCT,
        val stopLossPct: Double = SignalConstants.DEFAULT_STOP_LOSS_PCT,
        val trailActivationPct: Double = SignalConstants.DEFAULT_TRAIL_ACTIVATION_PCT,
        val stopLossDelayMs: Long = SignalConstants.DEFAULT_STOP_LOSS_DELAY_MS,
        val maxHoldMs: Long = SignalConstants.DEFAULT_MAX_PAPER_HOLD_MS,
        val settlementFallbackMs: Long = SignalConstants.DEFAULT_SETTLEMENT_FALLBACK_MS,
        val maxQuoteAgeMs: Long = SignalConstants.DEFAULT_MAX_EXIT_QUOTE_AGE_MS,
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
        val contracts: Int,
        val exitFeeUsd: Double,
        val isTrailing: Boolean,
        val isStopLoss: Boolean,
        val isTimeFallback: Boolean,
        val executedAt: Long,
        val pnlAtStop: Double
    )

    private val _executions = MutableStateFlow<List<StopExecution>>(emptyList())
    val executions: StateFlow<List<StopExecution>> = _executions.asStateFlow()
    private val consumedDepthBySnapshot = mutableMapOf<String, Int>()

    /** Convenience compatibility path for tests; production uses quote depth. */
    fun checkAndExecute(currentPrices: Map<String, Double>): List<StopExecution> =
        checkAndExecute(quotes = currentPrices.mapValues { PaperExitQuote(it.value) })

    fun checkAndExecute(quotes: Map<String, PaperExitQuote>, nowMs: Long = System.currentTimeMillis()): List<StopExecution> {
        if (!enabled() || !autoExecute || !config.autoExecute) return emptyList()
        paperBook.snapshot().fills.filter { !it.settled }.forEach { fill ->
            val quote = quotes[fill.ticker] ?: quotes[fill.ticker.uppercase()]
            quote?.takeIf { isFresh(it, nowMs) }?.let { paperBook.updatePeak(fill.id, it.price, nowMs) }
        }
        val current = paperBook.snapshot()
        val manager = current.getTrailingStopManager(
            stopLossPct = config.stopLossPct,
            trailingStopPct = config.trailingStopPct,
            trailActivationPct = config.trailActivationPct,
            stopLossDelayMs = config.stopLossDelayMs,
            maxHoldMs = config.maxHoldMs,
            settlementFallbackMs = config.settlementFallbackMs,
            maxQuoteAgeMs = config.maxQuoteAgeMs,
            enabled = config.enabled
        )
        val orders = manager.checkPositions(quotes, nowMs)
        val newExecutions = mutableListOf<StopExecution>()
        orders.forEach { order ->
            val quote = quotes[order.ticker] ?: quotes[order.ticker.uppercase()] ?: return@forEach
            val held = current.fills.firstOrNull { it.id == order.fillId && !it.settled } ?: return@forEach
            val depth = order.availableContracts?.coerceAtLeast(0) ?: return@forEach
            val snapshotId = quote.sourceSnapshotId ?: return@forEach
            val consumedKey = "${order.fillId}|$snapshotId"
            val alreadyConsumed = consumedDepthBySnapshot[consumedKey] ?: 0
            val remainingDepth = (depth - alreadyConsumed).coerceAtLeast(0)
            val qty = minOf(held.contracts, remainingDepth)
            if (qty <= 0) return@forEach
            val exitFee = KalshiFee.total(qty, quote.price)
            val entryFee = if (held.contracts > 0) held.entryFeeUsd * qty.toDouble() / held.contracts else 0.0
            val pnl = qty * quote.price - exitFee - qty * held.limitPrice - entryFee
            val sold = paperBook.sellAtPrice(
                ticker = held.ticker,
                side = held.side,
                price = quote.price,
                contracts = qty,
                source = "STOP ${order.reason.uppercase()}",
                note = "Paper stop at executable ${quote.source} bid"
            ) ?: return@forEach
            consumedDepthBySnapshot[consumedKey] = alreadyConsumed + sold.contracts
            val execution = StopExecution(
                fillId = order.fillId,
                ticker = order.ticker,
                side = order.side,
                entryPrice = order.entryPrice,
                stopPrice = order.stopPrice,
                currentPrice = quote.price,
                contracts = sold.contracts,
                exitFeeUsd = exitFee,
                isTrailing = order.isTrailing,
                isStopLoss = order.isStopLoss,
                isTimeFallback = order.isTimeFallback,
                executedAt = nowMs,
                pnlAtStop = pnl
            )
            newExecutions += execution
        }
        if (newExecutions.isNotEmpty()) _executions.value = _executions.value + newExecutions
        return newExecutions
    }

    fun getActiveStops(currentPrices: Map<String, Double>): List<TrailingStop.PositionState> =
        paperBook.snapshot().getTrailingStopManager(
            stopLossPct = config.stopLossPct,
            trailingStopPct = config.trailingStopPct,
            trailActivationPct = config.trailActivationPct,
            stopLossDelayMs = config.stopLossDelayMs,
            maxHoldMs = config.maxHoldMs,
            settlementFallbackMs = config.settlementFallbackMs,
            maxQuoteAgeMs = config.maxQuoteAgeMs,
            enabled = config.enabled
        ).getPositionsWithStops(currentPrices)

    fun getConfig(): StopConfig = config.copy(autoExecute = autoExecute && config.autoExecute, enabled = enabled())

    fun updateConfig(next: StopConfig): StopOrderManager = StopOrderManager(paperBook, next, next.autoExecute)

    fun enabled(): Boolean = config.enabled

    fun getExecutions(): List<StopExecution> = _executions.value

    fun clearExecutions() {
        _executions.value = emptyList()
        consumedDepthBySnapshot.clear()
    }

    private fun isFresh(quote: PaperExitQuote, nowMs: Long): Boolean =
        quote.observedAtMs > 0L && nowMs - quote.observedAtMs <= config.maxQuoteAgeMs
}

fun PaperBook.getStopOrderManager(
    trailingStopPct: Double = SignalConstants.DEFAULT_TRAILING_STOP_PCT,
    stopLossPct: Double = SignalConstants.DEFAULT_STOP_LOSS_PCT,
    trailActivationPct: Double = SignalConstants.DEFAULT_TRAIL_ACTIVATION_PCT,
    stopLossDelayMs: Long = SignalConstants.DEFAULT_STOP_LOSS_DELAY_MS,
    maxHoldMs: Long = SignalConstants.DEFAULT_MAX_PAPER_HOLD_MS,
    settlementFallbackMs: Long = SignalConstants.DEFAULT_SETTLEMENT_FALLBACK_MS,
    maxQuoteAgeMs: Long = SignalConstants.DEFAULT_MAX_EXIT_QUOTE_AGE_MS,
    autoExecute: Boolean = true,
    enabled: Boolean = true
    ): StopOrderManager = StopOrderManager(
    paperBook = this,
    config = StopOrderManager.StopConfig(
        trailingStopPct = trailingStopPct,
        stopLossPct = stopLossPct,
        trailActivationPct = trailActivationPct,
        stopLossDelayMs = stopLossDelayMs,
        maxHoldMs = maxHoldMs,
        settlementFallbackMs = settlementFallbackMs,
        maxQuoteAgeMs = maxQuoteAgeMs,
        enabled = enabled,
        autoExecute = autoExecute
    ),
    autoExecute = autoExecute
)

object DefaultStopConfig {
    const val TRAILING_STOP_PCT = SignalConstants.DEFAULT_TRAILING_STOP_PCT
    const val STOP_LOSS_PCT = SignalConstants.DEFAULT_STOP_LOSS_PCT
    const val TRAIL_ACTIVATION_PCT = SignalConstants.DEFAULT_TRAIL_ACTIVATION_PCT
    const val STOP_LOSS_DELAY_MS = SignalConstants.DEFAULT_STOP_LOSS_DELAY_MS
    const val MAX_HOLD_MS = SignalConstants.DEFAULT_MAX_PAPER_HOLD_MS
    const val SETTLEMENT_FALLBACK_MS = SignalConstants.DEFAULT_SETTLEMENT_FALLBACK_MS
    const val AUTO_EXECUTE = true
    const val ENABLED = true
}
