package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.PortfolioOrderDto
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Rebuilds a placed ticket from a GET /portfolio/orders row.
 *
 * A timed-out Approve can be retried with an edited limit. The adopted
 * order must show the side, price, and count Kalshi actually accepted,
 * never the ticket the user edited afterwards.
 */
object AdoptedOrder {
    fun toPlaced(order: PortfolioOrderDto, fallback: TradeTicket, clientOrderId: String): PlacedOrder? {
        val ticket = ticket(order, fallback) ?: return null
        return PlacedOrder(
            ticket = ticket,
            clientOrderId = order.clientOrderId?.takeIf { it.isNotBlank() } ?: clientOrderId,
            orderId = order.orderId,
            fillCount = parseCount(order.fillCountFp ?: order.fillCount) ?: 0.0,
            remainingCount = parseCount(order.remainingCountFp ?: order.remainingCount) ?: 0.0,
            averageFillPrice = null,
            placedAtMs = System.currentTimeMillis()
        )
    }

    fun ticket(order: PortfolioOrderDto, fallback: TradeTicket): TradeTicket? {
        val bookSide = bookSide(order) ?: return null
        val userSide = if (bookSide == "ask") "NO" else "YES"
        val yes = dollars(order.yesPriceDollars) ?: cents(order.yesPriceCents)
        val no = dollars(order.noPriceDollars) ?: cents(order.noPriceCents)
        val yesLimit = KalshiPrice.usable(yes) ?: KalshiPrice.usable(no?.let { 1.0 - it }) ?: return null
        val limit = if (userSide == "NO") {
            KalshiPrice.usable(no) ?: KalshiPrice.usable(1.0 - yesLimit)
        } else {
            KalshiPrice.usable(yesLimit)
        } ?: return null
        val count = countOf(order) ?: return null
        val fee = feeOf(order)
        val position = count * limit
        val allIn = position + (fee ?: 0.0)
        val ticker = order.ticker?.takeIf { it.isNotBlank() } ?: fallback.ticker
        return fallback.copy(
            ticker = ticker,
            side = userSide,
            bookSide = bookSide,
            limitPrice = limit,
            yesLimitPrice = if (userSide == "NO") KalshiPrice.clipLimit(1.0 - limit) else limit,
            contracts = count,
            stakeUsd = allIn,
            estimatedFillUsd = allIn,
            maxPayoutUsd = count * SignalConstants.CONTRACT_SETTLEMENT_USD,
            estimatedAvgFill = limit,
            feeUsd = fee,
            allInUsd = allIn,
            profitIfWinUsd = count * SignalConstants.CONTRACT_SETTLEMENT_USD - allIn,
            sizingNote = String.format(
                Locale.US,
                "Adopted from Kalshi %s — %s %d @ %.1f¢",
                order.orderId ?: order.clientOrderId ?: "order",
                userSide,
                count,
                limit * 100.0
            )
        )
    }

    private fun bookSide(order: PortfolioOrderDto): String? {
        when (order.bookSide?.trim()?.lowercase()) {
            "bid" -> return "bid"
            "ask" -> return "ask"
        }
        val outcome = order.outcomeSide?.trim()?.lowercase() ?: order.side?.trim()?.lowercase()
        return when (outcome) {
            "yes" -> "bid"
            "no" -> "ask"
            else -> null
        }
    }

    private fun countOf(order: PortfolioOrderDto): Int? {
        val initial = parseCount(order.initialCountFp ?: order.initialCount)
        if (initial != null && initial > 0.0) return initial.roundToInt().coerceAtLeast(1)
        val fill = parseCount(order.fillCountFp ?: order.fillCount) ?: 0.0
        val remaining = parseCount(order.remainingCountFp ?: order.remainingCount) ?: 0.0
        val sum = fill + remaining
        if (sum > 0.0) return sum.roundToInt().coerceAtLeast(1)
        return null
    }

    private fun feeOf(order: PortfolioOrderDto): Double? {
        val taker = dollars(order.takerFeesDollars)
        val maker = dollars(order.makerFeesDollars)
        if (taker == null && maker == null) return null
        return (taker ?: 0.0) + (maker ?: 0.0)
    }

    private fun dollars(raw: String?): Double? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    private fun cents(raw: Int?): Double? = raw?.takeIf { it > 0 }?.div(100.0)

    private fun parseCount(raw: String?): Double? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
}
