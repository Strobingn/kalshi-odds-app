package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
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

    fun draft(
        ticker: String,
        side: String,
        ask: Double,
        depth: Int?,
        reason: String,
        nowMs: Long,
        clientOrderId: String,
        capUsd: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        regimeKey: String? = null,
        cashUsd: Double = SignalConstants.PAPER_START_USD,
        id: String = ""
    ): ShadowTicket {
        val outcome = if (side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(ask) ?: 0.0
        val room = capUsd.coerceAtMost(LiveOrderSizer.LIVE_ALL_IN_CAP_USD)
        val clip = LiveOrderSizer.size(px, room, feeRate)
        val yesPx = if (outcome == "YES") clip.price else KalshiPrice.clipLimit(1.0 - clip.price)
        val bookSide = if (outcome == "YES") "bid" else "ask"
        val yesWire = KalshiPrice.toWireDollars(yesPx) ?: String.format(Locale.US, "%.4f", yesPx)
        val body = CreateOrderV2Request(
            ticker = ticker,
            side = bookSide,
            count = clip.countWire,
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
        val depthFill = clip.ok && depth != null && depth >= clip.count
        val booked = depthFill && cashUsd + 1e-9 >= clip.allInUsd
        val unfilled = when {
            !clip.ok -> clip.refusedReason ?: "Cannot size a live order under the cap"
            depth == null || depth < clip.count ->
                "Would not fill — ask size ${depth ?: 0} is below ${clip.count} contracts at the limit"
            !booked -> "Shadow bankroll cannot cover the exact order — not marked as a fill"
            else -> null
        }
        return ShadowTicket(
            id = id,
            ticker = ticker,
            side = outcome,
            action = "buy",
            bookSide = bookSide,
            count = clip.count,
            limitPrice = clip.price,
            yesLimitPrice = yesPx,
            clientOrderId = clientOrderId,
            createdAtMs = nowMs,
            reason = reason,
            payloadJson = json.encodeToString(wire),
            depthFill = depthFill,
            booked = booked,
            unfilledReason = unfilled,
            stakeUsd = if (clip.ok) clip.allInUsd else 0.0,
            feeUsd = clip.feeUsd,
            regimeKey = regimeKey
        )
    }

    /**
     * Ticket shaped exactly like the shadow payload so a later live send
     * can reuse the $10 cap. This function does not submit.
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
        sizingNote = "Limited live autopilot · $10 all-in · paper+shadow agreement",
        gateNote = "Armed session · Approve + REAL MONEY already confirmed · daily cap still applies",
        kind = TicketKind.CONFIGURED,
        feeUsd = ticket.feeUsd,
        allInUsd = ticket.stakeUsd,
        clientOrderId = ticket.clientOrderId,
        postOnly = false,
        reduceOnly = false
    )
}
