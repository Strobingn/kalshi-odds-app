package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import java.util.Locale
import kotlin.math.roundToInt

/**
 * User-typed limit in whole cents (1–99). Stake, contracts, fee, and the
 * $10 all-in cap are recomputed at that price. Does not place an order.
 */
object LimitPriceInput {
    const val MIN_CENTS = 1
    const val MAX_CENTS = 99

    fun suggestedCents(price: Double): String {
        val cents = (price * 100.0).roundToInt().coerceIn(MIN_CENTS, MAX_CENTS)
        return cents.toString()
    }

    fun parse(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { !it.isDigit() }) return null
        val cents = trimmed.toIntOrNull() ?: return null
        if (cents !in MIN_CENTS..MAX_CENTS) return null
        return cents
    }

    fun validationError(text: String): String? {
        if (parse(text) != null) return null
        return "Enter a whole number of cents from $MIN_CENTS to $MAX_CENTS"
    }

    fun dollars(cents: Int): Double = cents.coerceIn(MIN_CENTS, MAX_CENTS) / 100.0

    fun apply(ticket: TradeTicket, cents: Int, feeRate: Double): TradeTicket {
        val px = KalshiPrice.clipLimit(dollars(cents))
        if (ticket.isSell) {
            return TicketBuilder.applySellQuote(ticket, ticket.contracts, px, feeRate)
        }
        val maker = ticket.postOnly || ticket.kind == TicketKind.D3
        val rate = if (maker) 0.0 else feeRate
        val live = LiveOrderSizer.size(px, LiveOrderSizer.LIVE_ALL_IN_CAP_USD, rate)
        if (!live.ok) {
            return ticket.copy(
                limitPrice = px,
                yesLimitPrice = yesLeg(ticket.side, px),
                contracts = 0,
                stakeUsd = 0.0,
                estimatedFillUsd = 0.0,
                estimatedAvgFill = px,
                feeUsd = 0.0,
                allInUsd = 0.0,
                profitIfWinUsd = 0.0,
                maxPayoutUsd = 0.0,
                blockedReason = live.refusedReason ?: "Cannot size a \$10 live order",
                sizingNote = live.refusedReason ?: "Cannot size at this limit"
            )
        }
        return ticket.copy(
            limitPrice = px,
            yesLimitPrice = yesLeg(ticket.side, px),
            contracts = live.count,
            stakeUsd = live.allInUsd,
            estimatedFillUsd = live.allInUsd,
            estimatedAvgFill = px,
            maxPayoutUsd = live.count * SignalConstants.CONTRACT_SETTLEMENT_USD,
            feeUsd = live.feeUsd,
            allInUsd = live.allInUsd,
            profitIfWinUsd = live.profitIfWinUsd,
            blockedReason = null,
            sizingNote = String.format(
                Locale.US,
                "%d ct @ %d¢ · all-in \$%.2f (fee \$%.2f) · profit if win \$%.2f · \$10 cap",
                live.count,
                cents,
                live.allInUsd,
                live.feeUsd,
                live.profitIfWinUsd
            ),
            winTargetNote = String.format(
                Locale.US,
                "\$10 all-in at %d¢ · wins \$%.2f",
                cents,
                live.profitIfWinUsd
            )
        )
    }

    private fun yesLeg(side: String, price: Double): Double {
        val yes = if (side.equals("NO", ignoreCase = true)) 1.0 - price else price
        return KalshiPrice.clipLimit(yes)
    }
}

object ApproveControls {
    fun enabled(credentialsConfigured: Boolean, canApprove: Boolean, submitting: Boolean): Boolean =
        credentialsConfigured && canApprove && !submitting
}
