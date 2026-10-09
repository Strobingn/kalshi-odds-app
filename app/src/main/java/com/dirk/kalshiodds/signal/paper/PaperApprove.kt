package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket

/**
 * Explicit paper Approve. Isolated from Kalshi credentials, live cash,
 * and [TicketSession.approve] / the V2 order client. Callers must show
 * [PaperBuy.Outcome.message] on the ticket card.
 */
object PaperApprove {

    fun apply(
        session: TicketSession,
        book: PaperBook,
        ticketId: String,
        size: (TradeTicket) -> TradeTicket = { it },
        onHistory: (TicketAttemptRow) -> Unit = {},
        nowMs: () -> Long = { System.currentTimeMillis() }
    ): PaperBuy.Outcome {
        val ticket = session.snapshot().proposals.firstOrNull { it.id == ticketId }
        if (ticket == null) {
            val missing = PaperBuy.Outcome(ok = false, message = "Paper buy ignored — no matching ticket")
            session.failSoft(missing.message)
            return missing
        }
        val sized = if (ticket.isSell) ticket else size(ticket)
        val outcome = PaperBuy.execute(book, sized)
        if (outcome.ok) {
            session.failSoft(outcome.message)
            runCatching {
                onHistory(
                    TicketAttemptRow(
                        ticker = ticket.ticker,
                        side = ticket.side,
                        stakeUsd = outcome.stakeUsd,
                        approved = true,
                        result = "paper filled",
                        createdAtMs = nowMs(),
                        note = outcome.message
                    )
                )
            }
            session.dismiss(ticketId)
        } else {
            session.failSoft(outcome.visibleReason)
        }
        return outcome
    }
}
