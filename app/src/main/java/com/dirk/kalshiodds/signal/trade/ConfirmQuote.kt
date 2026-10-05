package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * Numbers shown on the REAL MONEY confirm sheet. They are the same
 * [TicketBuilder.repriceBuy] result that Approve submits, so a limit edit
 * cannot display one size and send another. The $5 all-in cap lives in
 * that sizer.
 */
object ConfirmQuote {
    data class Buy(
        /** Stake text Approve will pass into [TicketBuilder.repriceBuy]. */
        val submittedStakeUsd: Double,
        /** Limit Approve will pass into [TicketBuilder.repriceBuy]. */
        val submittedLimitPrice: Double,
        val contracts: Int,
        val feeUsd: Double,
        val allInUsd: Double,
        val profitIfWinUsd: Double,
        val blockedReason: String?,
        /** Same edge check [TicketBuilder.repriceBuy] stored. Does not change the order. */
        val edgeNote: String? = null
    ) {
        val withinCap: Boolean
            get() = allInUsd <= SignalConstants.LIVE_ALL_IN_CAP_USD + 1e-6 && contracts > 0 && blockedReason == null
    }

    fun buy(
        ticket: TradeTicket,
        stakeText: String,
        centsText: String,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Buy {
        val stake = stakeText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: ticket.stakeUsd
        val cents = centsText.toDoubleOrNull()
        val price = if (cents != null && cents.isFinite()) cents / 100.0 else ticket.limitPrice
        val priced = TicketBuilder.repriceBuy(ticket, stake, price, feeRate)
        return Buy(
            submittedStakeUsd = stake,
            submittedLimitPrice = priced.limitPrice,
            contracts = priced.contracts,
            feeUsd = priced.feeUsd ?: 0.0,
            allInUsd = priced.allInUsd ?: priced.stakeUsd,
            profitIfWinUsd = priced.profitIfWinUsd ?: priced.potentialGainUsd,
            blockedReason = priced.blockedReason,
            edgeNote = priced.edgeCheckNote
        )
    }
}
