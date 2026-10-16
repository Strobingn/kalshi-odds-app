package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.settlement.BRTIPrint
import com.dirk.kalshiodds.signal.settlement.BRTISettlementTracker
import com.dirk.kalshiodds.signal.settlement.TradeSignal
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.signal.trade.TicketKind
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Paper book extension that supports settlement-aware trading.
 * 
 * This class adds BRTI print tracking and settlement probability calculation
 * to the standard paper book, enabling the Rank 1 averaging-aware strategy.
 */
class SettlementAwarePaperBook(
    initial: PaperBookState = PaperBookState(),
    private val settlementTracker: BRTISettlementTracker,
    private val persist: (PaperBookState) -> Unit = {},
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : PaperBook(initial, persist, idFactory, nowMs) {
    
    /**
     * Record a BRTI print for a market.
     */
    suspend fun recordBRTIPrint(print: BRTIPrint) {
        settlementTracker.addPrint(print)
    }
    
    /**
     * Record the settlement value for a completed market.
     */
    suspend fun recordSettlement(
        ticker: String,
        settlementValue: Double,
        outcome: String,
        closeTimeEpochMs: Long
    ) {
        val windowId = "KXBTC15M-$closeTimeEpochMs"
        settlementTracker.settleWindow(windowId, settlementValue, outcome)
    }
    
    /**
     * Create a paper fill from a settlement-aware trade signal.
     */
    fun createSettlementFill(
        signal: TradeSignal,
        contracts: Int = signal.minContracts
    ): PaperFill? {
        if (CryptoMarkets.isRetiredTicker(signal.ticker)) {
            rememberMessage("Paper skip ${signal.ticker}  Bitcoin-only")
            return null
        }
        
        val px = KalshiPrice.usable(signal.askPrice) ?: run {
            rememberMessage("Paper skip ${signal.ticker}  unusable limit")
            return null
        }
        
        val qty = contracts.coerceAtLeast(signal.minContracts)
        val stake = qty * px
        
        synchronized(lock) {
            val cur = _state.value
            if (cur.cashUsd + 1e-9 < stake && !SignalConstants.PAPER_ALLOW_NEGATIVE) {
                rememberMessage("Paper skip ${signal.ticker}  need \$${"%.2f".format(stake)} (cash \$${"%.2f".format(cur.cashUsd)})")
                return null
            }
            
            val row = PaperFill(
                id = idFactory(),
                ticker = signal.ticker,
                side = signal.side,
                stakeUsd = stake,
                contracts = qty,
                limitPrice = px,
                source = "Settlement-Aware",
                createdAtMs = nowMs(),
                note = "Settlement-aware: ${"%.1f%%".format(signal.probability * 100)} prob, +${"%.2f".format(signal.edgeCents)}c edge",
                winTargetUsd = null
            )
            
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - stake,
                    fills = fills,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "SETTLEMENT %s %s 7 \$%.2f 7 %d ct @ %.0f\u00a2 7 +%.2f\u00a2 edge",
                        row.displaySide,
                        row.ticker,
                        row.stakeUsd,
                        row.contracts,
                        row.limitPrice * 100,
                        signal.edgeCents
                    )
                )
            )
            return row
        }
    }
    
    /**
     * Create a paper fill from a settlement-aware trade ticket.
     */
    fun createSettlementFillFromTicket(ticket: TradeTicket): PaperFill? {
        if (!ticket.canApprove) return null
        if (ticket.kind != TicketKind.WINNING_STRATEGY) return null
        
        return createSettlementFill(
            TradeSignal(
                ticker = ticket.ticker,
                windowId = "",
                side = ticket.side,
                askPrice = ticket.limitPrice,
                fairValue = 0.0,
                probability = 0.0,
                edgeCents = ticket.netEdgePp ?: 0.0,
                edgeUsd = ticket.netEvUsd ?: 0.0,
                feeCents = 0.0,
                feeUsd = 0.0,
                printsRemaining = 0,
                effectivePrice = 0.0,
                target = 0.0,
                currentBRTI = 0.0,
                minContracts = ticket.contracts,
                recommendation = "SETTLEMENT_AWARE"
            ),
            ticket.contracts
        )
    }
    
    /**
     * Settle a paper fill based on actual settlement value.
     */
    fun settleFill(
        fillId: String,
        settlementValue: Double,
        target: Double
    ): Boolean {
        synchronized(lock) {
            val cur = _state.value
            val fill = cur.fills.firstOrNull { it.id == fillId } ?: return false
            
            if (fill.settled) return false
            
            // Determine if the fill won
            val won = when (fill.side) {
                "YES" -> settlementValue >= target
                "NO" -> settlementValue < target
                else -> false
            }
            
            // Calculate P&L
            val pnl = if (won) {
                fill.contracts * (1.0 - fill.limitPrice)
            } else {
                fill.contracts * (0.0 - fill.limitPrice)
            }
            
            // Update the fill
            val updatedFill = fill.copy(
                settled = true,
                outcome = if (won) "YES" else "NO",
                won = won,
                pnlUsd = pnl
            )
            
            val updatedFills = cur.fills.map {
                if (it.id == fillId) updatedFill else it
            }
            
            publish(
                cur.copy(
                    fills = updatedFills,
                    lastMessage = "Settled ${fill.ticker} ${fill.side}: ${if (won) "WIN" else "LOSS"} (\$${"%.2f".format(pnl)})"
                )
            )
            
            return true
        }
    }
    
    /**
     * Get all unsettled fills that are ready for settlement.
     */
    fun getUnsettledFillsReadyForSettlement(): List<PaperFill> {
        synchronized(lock) {
            val cur = _state.value
            return cur.fills.filter { !it.settled }
        }
    }
    
    /**
     * Get settlement statistics for the paper book.
     */
    fun getSettlementStatistics(): SettlementStatistics {
        synchronized(lock) {
            val cur = _state.value
            val settledFills = cur.fills.filter { it.settled }
            val unsettledFills = cur.fills.filter { !it.settled }
            
            val totalTrades = settledFills.size
            val winningTrades = settledFills.count { it.won == true }
            val losingTrades = settledFills.count { it.won == false }
            val totalPnl = settledFills.sumOf { it.pnlUsd ?: 0.0 }
            val avgPnl = if (totalTrades > 0) totalPnl / totalTrades else 0.0
            val winRate = if (totalTrades > 0) winningTrades.toDouble() / totalTrades else 0.0
            
            return SettlementStatistics(
                totalTrades = totalTrades,
                winningTrades = winningTrades,
                losingTrades = losingTrades,
                totalPnl = totalPnl,
                avgPnl = avgPnl,
                winRate = winRate,
                unsettledCount = unsettledFills.size,
                unsettledStake = unsettledFills.sumOf { it.stakeUsd }
            )
        }
    }
}

/**
 * Statistics for settlement-aware paper trading.
 */
@Serializable
data class SettlementStatistics(
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val totalPnl: Double,
    val avgPnl: Double,
    val winRate: Double,
    val unsettledCount: Int,
    val unsettledStake: Double
) {
    val isProfitable: Boolean get() = totalPnl > 0
    val hasTrades: Boolean get() = totalTrades > 0
}

/**
 * Factory function to create a settlement-aware paper book.
 */
fun createSettlementAwarePaperBook(
    initial: PaperBookState = PaperBookState(),
    settlementTracker: BRTISettlementTracker
): SettlementAwarePaperBook {
    return SettlementAwarePaperBook(initial, settlementTracker)
}
