package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.UUID

/** Result of one executed leg. Prices are integer cents; fee in cents. */
data class FillResult(
    val filledContracts: Int,
    val avgPriceCents: Int,
    val feeCents: Int,
    val clientOrderId: String,
    val orderId: String? = null
)

/**
 * Order executor for one scalper leg. Never throws into the engine — soft
 * failures return false / null and the engine retries on the next tick.
 * Guardrails are checked by the engine BEFORE calling either method.
 */
interface ScalpExecutor {
    /** Buy YES at the ask. True when at least one contract filled. */
    suspend fun enter(position: ScalpPosition): Boolean

    /** Reduce-only sell YES at the bid. Null when nothing filled (retry later). */
    suspend fun exit(position: ScalpPosition, reason: ExitReason): FillResult?
}

/**
 * Paper fills at the current [LocalOrderBook] snapshot — entry at the ask,
 * exit at the bid — with the official Kalshi taker fee deducted from P&L.
 * Mirrors [com.dirk.kalshiodds.signal.paper.PaperBook]'s fill simulation;
 * never calls Kalshi.
 */
class PaperScalpExecutor(
    private val bookProvider: () -> LocalOrderBook?,
    private val feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) : ScalpExecutor {

    override suspend fun enter(position: ScalpPosition): Boolean = runCatching {
        val ask01 = position.entryPriceCents / 100.0
        // The book ask at fill time must not be worse than the decision ask.
        val bookAsk = bookProvider()?.bestYesAsk()
        if (bookAsk != null && bookAsk > ask01 + 1e-9) return false
        position.contracts >= 1
    }.getOrDefault(false)

    override suspend fun exit(position: ScalpPosition, reason: ExitReason): FillResult? = runCatching {
        val exitCents = position.exitPriceCents ?: return null
        val book = bookProvider() ?: return null
        val bid01 = book.bestYesBid() ?: return null
        // Never fill below the decision bid.
        val priceCents = minOf(exitCents, (bid01 * 100.0 + 1e-9).toInt())
        if (priceCents < 1) return null
        val price01 = priceCents / 100.0
        val feeCents = feeCents(position.contracts, price01)
        FillResult(
            filledContracts = position.contracts,
            avgPriceCents = priceCents,
            feeCents = feeCents,
            clientOrderId = "paper-${idFactory()}"
        )
    }.getOrNull()

    internal fun feeCents(contracts: Int, price01: Double): Int =
        kotlin.math.round(KalshiFee.total(contracts, price01, feeRate) * 100.0).toInt()
}

/**
 * Real Kalshi execution via [KalshiTradeClient]. Entry: limit buy YES at the
 * ask (GTC — Kalshi V2 requires non-IoC for non-reduce-only). Exit:
 * reduce-only IoC sell YES at the bid (the client sets TIF automatically
 * for reduce-only). Client order ids are prefixed `scalp-`.
 *
 * Dirk's $10 all-in cap is re-enforced here with
 * [LiveOrderSizer.enforce] — any ticket that would exceed it is refused
 * rather than clipped up. Soft failures are runCaught and surfaced as
 * false / null; [onPlaced] hands the [PlacedOrder] back so the engine can
 * persist order ids for later cancel/exit.
 */
class LiveScalpExecutor(
    private val tradeClient: KalshiTradeClient,
    private val feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val onPlaced: (ScalpPosition, PlacedOrder) -> Unit = { _, _ -> }
) : ScalpExecutor {

    override suspend fun enter(position: ScalpPosition): Boolean = runCatching {
        val ask01 = position.entryPriceCents / 100.0
        val ticket = TradeTicket(
            id = idFactory(),
            ticker = position.ticker,
            side = "YES",
            bookSide = "bid",
            stakeUsd = 0.0,
            limitPrice = ask01,
            yesLimitPrice = KalshiPrice.clipLimit(ask01),
            contracts = position.contracts,
            estimatedFillUsd = 0.0,
            maxPayoutUsd = position.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD,
            estimatedAvgFill = ask01,
            sizingNote = "scalp entry · dip buy YES at the ask · auto (non-approve) path",
            gateNote = "DipHunter scalper auto-entry · $10 all-in cap enforced",
            kind = TicketKind.HUNTER,
            feeUsd = KalshiFee.total(position.contracts, ask01, feeRate),
            allInUsd = 0.0
        )
        val clip = LiveOrderSizer.enforce(ticket, feeRate = feeRate)
        if (!clip.ok) return false
        if (clip.allInUsd > LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-9) return false
        val clientOrderId = "scalp-${idFactory()}"
        val placed = tradeClient.createLimit(ticket, clientOrderId)
        if (placed.error != null) return false
        if (placed.fillCount < 1.0) return false
        onPlaced(position, placed)
        true
    }.getOrDefault(false)

    override suspend fun exit(position: ScalpPosition, reason: ExitReason): FillResult? = runCatching {
        val exitCents = position.exitPriceCents ?: return null
        val bid01 = exitCents / 100.0
        val ticket = TradeTicket(
            id = idFactory(),
            ticker = position.ticker,
            side = "YES",
            bookSide = "ask",
            stakeUsd = 0.0,
            limitPrice = bid01,
            yesLimitPrice = KalshiPrice.clipLimit(bid01),
            contracts = position.contracts,
            estimatedFillUsd = 0.0,
            maxPayoutUsd = 0.0,
            estimatedAvgFill = bid01,
            sizingNote = "scalp exit · reduce-only sell YES at the bid ($reason) · IoC",
            gateNote = "DipHunter scalper auto-exit · leftover size canceled",
            kind = TicketKind.SELL,
            reduceOnly = true,
            heldContracts = position.contracts,
            feeUsd = KalshiFee.total(position.contracts, bid01, feeRate),
            allInUsd = 0.0
        )
        val clientOrderId = "scalp-${idFactory()}"
        val placed = tradeClient.createLimit(ticket, clientOrderId)
        if (placed.error != null) return null
        val filled = placed.filledContracts
        if (filled < 1) return null
        val avg = placed.averageFillPrice ?: bid01
        FillResult(
            filledContracts = filled,
            avgPriceCents = kotlin.math.round(avg * 100.0).toInt(),
            feeCents = kotlin.math.round(
                KalshiFee.total(filled, avg, feeRate) * 100.0
            ).toInt(),
            clientOrderId = placed.clientOrderId,
            orderId = placed.orderId
        )
    }.getOrNull()
}
