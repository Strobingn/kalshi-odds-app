package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlin.math.floor
import kotlin.math.min

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

    fun capContracts(want: Int, cashUsd: Double, price: Double): Pair<Int, Boolean> {
        val px = price.coerceIn(0.01, 0.99)
        val maxByCash = floor((cashUsd + 1e-9) / px).toInt()
        val qty = min(want.coerceAtLeast(0), maxByCash.coerceAtLeast(0))
        return qty to (want > 0 && qty < want)
    }
}
