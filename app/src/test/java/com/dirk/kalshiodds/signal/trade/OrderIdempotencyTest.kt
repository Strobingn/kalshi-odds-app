package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderIdempotencyTest {
    @Test
    fun ticketIsMintedWithOneClientOrderId() {
        val ticket = manual()
        assertTrue(ticket.clientOrderId.isNotBlank())
        assertEquals("minted-at-create", ticket.clientOrderId)
    }

    @Test
    fun retryAfterTimeoutSendsTheSameClientOrderId() = runBlocking {
        val sent = mutableListOf<String>()
        var calls = 0
        val session = TicketSession(
            placeOrder = { _, id ->
                calls += 1
                sent += id
                if (calls == 1) Result.failure(SocketTimeoutException("timeout"))
                else Result.success(placed(id))
            },
            findExistingOrder = { _, _ -> null },
            idFactory = { "should-not-replace-minted-id" }
        )
        val ticket = manual()
        session.addManual(ticket)
        session.approve(ticket.id)
        session.approve(ticket.id)
        assertEquals(listOf("minted-at-create", "minted-at-create"), sent)
        assertEquals(2, calls)
    }

    @Test
    fun existingOrderBlocksASecondPlace() = runBlocking {
        val sent = mutableListOf<String>()
        val session = TicketSession(
            placeOrder = { ticket, id ->
                sent += id
                if (sent.size == 1) Result.failure(SocketTimeoutException("timeout"))
                else Result.success(placed(id, ticket))
            },
            findExistingOrder = { id, _ ->
                if (sent.isNotEmpty() && id == "minted-at-create") placed(id) else null
            },
            idFactory = { "other" }
        )
        val ticket = manual()
        session.addManual(ticket)
        session.approve(ticket.id)
        val second = session.approve(ticket.id)
        assertEquals(listOf("minted-at-create"), sent)
        assertTrue(second.phase is TicketPhase.Submitted)
        assertEquals("minted-at-create", (second.phase as TicketPhase.Submitted).order.clientOrderId)
    }

    @Test
    fun inFlightApproveDoesNotStartAnotherOrder() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val session = TicketSession(
            placeOrder = { ticket, id ->
                calls += 1
                gate.await()
                Result.success(placed(id, ticket))
            },
            idFactory = { "once" }
        )
        val ticket = manual()
        session.addManual(ticket)
        val first = launch { session.approve(ticket.id) }
        while (calls == 0) delay(5)
        assertEquals(false, session.openApprove(ticket.id))
        gate.complete(Unit)
        first.join()
        session.approve(ticket.id)
        assertEquals(1, calls)
    }

    private fun placed(id: String, ticket: TradeTicket = manual()): PlacedOrder = PlacedOrder(
        ticket = ticket,
        clientOrderId = id,
        orderId = "ord-existing",
        fillCount = 0.0,
        remainingCount = 1.0,
        averageFillPrice = ticket.limitPrice,
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
                nowMs = 1_700_000_000_000L
            )
        )!!
    }
}
