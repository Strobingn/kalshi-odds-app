package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.data.local.results.MemoryPendingOrderIdStore
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingClientOrderTest {
    @Test
    fun processRestartReusesTheSameClientOrderId() = runBlocking {
        val store = MemoryPendingOrderIdStore()
        val sent = mutableListOf<String>()
        val first = session(store, sent) { Result.failure(SocketTimeoutException("timeout")) }
        val ticket = manual()
        first.addManual(ticket)
        first.approve(ticket.id)
        assertEquals(listOf("minted-at-create"), sent)

        val lookups = mutableListOf<String>()
        val second = TicketSession(
            placeOrder = { _, id ->
                sent += id
                Result.success(placed(id))
            },
            findExistingOrder = { id, _ ->
                lookups += id
                null
            },
            idFactory = { "brand-new-id" },
            pendingOrderIds = store
        )
        second.onStart()
        val rebuilt = manual().copy(
            id = "ticket-after-kill",
            clientOrderId = "regenerated",
            stakeUsd = ticket.stakeUsd + 3.0,
            contracts = ticket.contracts + 4
        )
        second.addManual(rebuilt)
        second.approve(rebuilt.id)
        assertEquals(listOf("minted-at-create"), lookups)
        assertEquals(listOf("minted-at-create", "minted-at-create"), sent)
    }

    @Test
    fun dismissAndRebuyKeepsPendingIdWhenAnOrderMayExist() = runBlocking {
        val store = MemoryPendingOrderIdStore()
        val sent = mutableListOf<String>()
        val session = session(store, sent) { Result.failure(SocketTimeoutException("timeout")) }
        val ticket = manual()
        session.addManual(ticket)
        session.approve(ticket.id)
        session.dismiss(ticket.id)
        val again = manual().copy(id = "rebuy", clientOrderId = "other-id", stakeUsd = 4.0)
        session.addManual(again)
        session.approve(again.id)
        assertEquals(listOf("minted-at-create", "minted-at-create"), sent)
    }

    @Test
    fun adoptedOrderShowsExchangeTermsNotTheEditedTicket() = runBlocking {
        val store = MemoryPendingOrderIdStore()
        val session = TicketSession(
            placeOrder = { _, _ -> error("must not send a second order") },
            findExistingOrder = { id, ticker ->
                PlacedOrder(
                    ticket = TradeTicket(
                        id = id,
                        ticker = ticker,
                        side = "NO",
                        bookSide = "ask",
                        stakeUsd = 7.60,
                        limitPrice = 0.76,
                        yesLimitPrice = 0.24,
                        contracts = 10,
                        estimatedFillUsd = 7.60,
                        maxPayoutUsd = 10.0,
                        estimatedAvgFill = 0.76,
                        sizingNote = "existing order",
                        kind = TicketKind.MANUAL,
                        clientOrderId = id
                    ),
                    clientOrderId = id,
                    orderId = "ord-no",
                    fillCount = 0.0,
                    remainingCount = 10.0,
                    averageFillPrice = 0.76,
                    placedAtMs = 1L
                )
            },
            pendingOrderIds = store,
            idFactory = { "local" }
        )
        val edited = manual().copy(stakeUsd = 9.0, contracts = 30, limitPrice = 0.25)
        session.addManual(edited)
        val state = session.approve(edited.id)
        val order = (state.phase as TicketPhase.Submitted).order
        assertEquals("NO", order.ticket.side)
        assertEquals("ask", order.ticket.bookSide)
        assertEquals(10, order.ticket.contracts)
        assertEquals(0.76, order.ticket.limitPrice, 1e-9)
        assertEquals(7.60, order.ticket.stakeUsd, 1e-6)
        assertEquals(edited.ticker, order.ticket.ticker)
        assertTrue(state.working.single().ticket.side == "NO")
    }

    private fun session(
        store: MemoryPendingOrderIdStore,
        sent: MutableList<String>,
        place: () -> Result<PlacedOrder>
    ) = TicketSession(
        placeOrder = { ticket, id ->
            sent += id
            place().map { it.copy(ticket = ticket, clientOrderId = id) }
        },
        findExistingOrder = { _, _ -> null },
        idFactory = { "should-not-replace" },
        pendingOrderIds = store
    )

    private fun placed(id: String) = PlacedOrder(
        ticket = manual(),
        clientOrderId = id,
        orderId = "ord-1",
        fillCount = 0.0,
        remainingCount = 1.0,
        averageFillPrice = 0.25,
        placedAtMs = 1L
    )

    private fun manual(): TradeTicket {
        val now = 1_700_000_000_000L
        val market = MarketUiModel(
            ticker = "KXBTC15M-26SEP251530-30",
            title = "BTC",
            subtitle = null,
            floorStrike = 4000.0,
            yesBid = 0.24,
            yesAsk = 0.25,
            noBid = 0.74,
            noAsk = 0.76,
            lastPrice = 0.25,
            yesProbabilityPercent = 25.0,
            noProbabilityPercent = 75.0,
            aiYesPercent = 40.0,
            volume = 1000.0,
            volume24h = 1000.0,
            openInterest = 100.0,
            liquidityDollars = 5000.0,
            closeTimeLocal = null,
            closeTimeEpochMs = now + 600_000L,
            status = "active",
            seriesLabel = "Bitcoin",
            passedFilter = true
        )
        return TicketBuilder.proposeManual(
            market,
            "YES",
            TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                idFactory = { "ticket-1" },
                orderIdFactory = { "minted-at-create" },
                nowMs = now
            )
        )!!
    }
}
