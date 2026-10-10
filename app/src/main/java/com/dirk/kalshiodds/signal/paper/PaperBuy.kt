package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TradeTicket

/**
 * Explicit paper buy / sell. Isolated from Kalshi credentials, live cash,
 * and the V2 order client. Fills at the ticket's walked ask (or bid for
 * sells). Paper credit is synthetic and intentionally not cash-capped.
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
     * Legacy sizing helper retained for callers compiled against older builds.
     * Paper credit is unrestricted, so it returns the requested quantity.
     */
    fun capContracts(
        want: Int,
        cashUsd: Double,
        price: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Pair<Int, Boolean> {
        // Keep the parameters for binary/source compatibility. They are not
        // financial limits in the paper-only experiment.
        return want.coerceAtLeast(0) to false
    }
}
