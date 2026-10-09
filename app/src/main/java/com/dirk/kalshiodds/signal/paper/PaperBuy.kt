package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TradeTicket

/**
 * Explicit paper buy / sell. Isolated from Kalshi credentials, live cash,
 * and the V2 order client. Fills at the ticket's walked ask (or bid for
 * sells) and caps size to paper cash instead of failing silently.
 */
object PaperBuy {

    data class Outcome(
        val ok: Boolean,
        val fill: PaperFill? = null,
        val message: String,
        val capped: Boolean = false,
        val contracts: Int = 0,
        val stakeUsd: Double = 0.0
    ) {
        val visibleReason: String get() = message
    }

    /**
     * Paper-fill [ticket]. Never requires a Kalshi key. Returns a user-visible
     * confirmation or failure reason — callers must surface [Outcome.message].
     */
    fun execute(book: PaperBook, ticket: TradeTicket): Outcome {
        if (!ticket.blockedReason.isNullOrBlank()) {
            return Outcome(ok = false, message = ticket.blockedReason!!)
        }
        if (ticket.isSell) {
            val fill = book.sell(ticket)
            val msg = book.snapshot().lastMessage
                ?: if (fill != null) "Paper sell filled" else "Paper sell failed"
            return Outcome(
                ok = fill != null,
                fill = fill,
                message = msg,
                contracts = fill?.contracts ?: 0,
                stakeUsd = fill?.stakeUsd ?: 0.0
            )
        }
        return book.explicitBuy(ticket)
    }

    fun costUsd(
        contracts: Int,
        price: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double {
        val n = contracts.coerceAtLeast(0)
        val px = com.dirk.kalshiodds.domain.KalshiPrice.clipLimit(price)
        return KalshiFee.totalCost(n, px, feeRate)
    }

    /**
     * Cap [want] so contract cost + official taker fee fits in [cashUsd].
     * Never returns a size that would overdraw the paper book.
     */
    fun capContracts(
        want: Int,
        cashUsd: Double,
        price: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Pair<Int, Boolean> {
        val px = com.dirk.kalshiodds.domain.KalshiPrice.clipLimit(price)
        var qty = want.coerceAtLeast(0)
        while (qty > 0 && costUsd(qty, px, feeRate) > cashUsd + 1e-9) {
            qty--
        }
        return qty to (want > 0 && qty < want)
    }
}
