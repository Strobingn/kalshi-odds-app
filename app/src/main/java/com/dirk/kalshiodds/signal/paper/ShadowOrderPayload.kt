package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The Kalshi V2 body a limited-live order would send, plus the action,
 * timestamp, and model reason. Building this never calls the order API.
 */
object ShadowOrderPayload {
    private val json = Json { encodeDefaults = true }

    @Serializable
    data class Wire(
        val ticker: String,
        val side: String,
        val action: String,
        val outcome_side: String,
        val count: String,
        val price: String,
        val time_in_force: String,
        val client_order_id: String,
        val post_only: Boolean,
        val reduce_only: Boolean,
        val self_trade_prevention_type: String,
        val reason: String,
        val timestamp_ms: Long
    )

    /**
     * Payload for the Kelly clip [sized]. Count and price match the paper
     * decision. There is no $10 clip. This function does not submit.
     */
    fun fromKelly(
        ticker: String,
        side: String,
        sized: PaperKellySizer.Result,
        depth: Int?,
        reason: String,
        nowMs: Long,
        clientOrderId: String,
        regimeKey: String? = null,
        id: String = ""
    ): ShadowTicket {
        val outcome = if (side.equals("NO", true)) "NO" else "YES"
        val px = sized.ask.takeIf { it > 0.0 } ?: KalshiPrice.usable(sized.ask) ?: 0.0
        val yesPx = if (outcome == "YES") px else KalshiPrice.clipLimit(1.0 - px)
        val bookSide = if (outcome == "YES") "bid" else "ask"
        val yesWire = KalshiPrice.toWireDollars(yesPx) ?: String.format(Locale.US, "%.4f", yesPx)
        val countWire = String.format(Locale.US, "%.2f", sized.contracts.toDouble())
        val body = CreateOrderV2Request(
            ticker = ticker,
            side = bookSide,
            count = countWire,
            price = yesWire,
            timeInForce = CreateOrderV2Request.TIME_IN_FORCE_GTC,
            clientOrderId = clientOrderId,
            postOnly = false,
            reduceOnly = false
        )
        val wire = Wire(
            ticker = body.ticker,
            side = body.side,
            action = "buy",
            outcome_side = outcome,
            count = body.count,
            price = body.price,
            time_in_force = body.timeInForce,
            client_order_id = body.clientOrderId,
            post_only = body.postOnly,
            reduce_only = body.reduceOnly,
            self_trade_prevention_type = body.selfTradePreventionType,
            reason = reason,
            timestamp_ms = nowMs
        )
        val depthFill = sized.ok && depth != null && depth >= sized.contracts
        val unfilled = when {
            !sized.ok -> sized.reason ?: "Kelly size is empty"
            depth == null || depth < sized.contracts ->
                "Would not fill — ask size ${depth ?: 0} is below ${sized.contracts} contracts at the limit"
            else -> null
        }
        return ShadowTicket(
            id = id,
            ticker = ticker,
            side = outcome,
            action = "buy",
            bookSide = bookSide,
            count = sized.contracts,
            limitPrice = px,
            yesLimitPrice = yesPx,
            clientOrderId = clientOrderId,
            createdAtMs = nowMs,
            reason = reason,
            payloadJson = json.encodeToString(wire),
            depthFill = depthFill,
            booked = depthFill,
            unfilledReason = unfilled,
            stakeUsd = if (sized.ok) sized.allInUsd else 0.0,
            feeUsd = sized.feeUsd,
            regimeKey = regimeKey
        )
    }

    /**
     * Ticket shaped exactly like the shadow payload. Kelly size is kept.
     * Manual Approve tickets stay on the $10 cap. This function does not submit.
     */
    fun toTradeTicket(ticket: ShadowTicket): TradeTicket = TradeTicket(
        id = ticket.id.ifBlank { ticket.clientOrderId },
        ticker = ticket.ticker,
        side = ticket.side,
        bookSide = ticket.bookSide,
        stakeUsd = ticket.stakeUsd,
        limitPrice = ticket.limitPrice,
        yesLimitPrice = ticket.yesLimitPrice,
        contracts = ticket.count,
        estimatedFillUsd = ticket.stakeUsd,
        maxPayoutUsd = ticket.count * SignalConstants.CONTRACT_SETTLEMENT_USD,
        estimatedAvgFill = ticket.limitPrice,
        sizingNote = "Limited live autopilot · fee-aware Kelly · paper+shadow agreement",
        gateNote = "Armed session · Approve + REAL MONEY already confirmed · no dollar cap",
        kellyAutopilot = true,
        kind = TicketKind.CONFIGURED,
        feeUsd = ticket.feeUsd,
        allInUsd = ticket.stakeUsd,
        clientOrderId = ticket.clientOrderId,
        postOnly = false,
        reduceOnly = false
    )
}
