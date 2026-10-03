package com.dirk.kalshiodds.signal.trade

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketRetryTest {
    @Test
    fun failedApproveReusesTheSameClientOrderId() = runBlocking {
        val ids = mutableListOf<String>()
        var failNext = true
        val session = TicketSession(
            placeOrder = { ticket, clientOrderId ->
                ids += clientOrderId
                if (failNext) {
                    failNext = false
                    Result.failure(IllegalStateException("timeout"))
                } else {
                    Result.success(placed(ticket, clientOrderId, "ord-1"))
                }
            },
            idFactory = { "coid-stable" }
        )
        session.addManual(buyTicket())
        val failed = session.approve("t1")
        val failedPhase = failed.phase as TicketPhase.Failed
        assertEquals("coid-stable", failedPhase.clientOrderId)
        session.openApprove("t1")
        val again = session.snapshot().phase as TicketPhase.AwaitingApprove
        assertEquals("coid-stable", again.clientOrderId)
        val submitted = session.approve("t1")
        assertTrue(submitted.phase is TicketPhase.Submitted)
        assertEquals(listOf("coid-stable", "coid-stable"), ids)
    }

    @Test
    fun retryLooksUpAnExistingOrderBeforeSendingAnother() = runBlocking {
        var places = 0
        var lookups = 0
        val session = TicketSession(
            placeOrder = { _, _ ->
                places += 1
                Result.failure(IllegalStateException("timeout"))
            },
            findExisting = { ticket, clientOrderId ->
                lookups += 1
                placed(ticket, clientOrderId, "already")
            },
            idFactory = { "coid-stable" }
        )
        session.addManual(buyTicket())
        session.approve("t1")
        assertEquals(1, places)
        assertEquals(0, lookups)
        val adopted = session.approve("t1")
        assertEquals(1, places)
        assertEquals(1, lookups)
        val submitted = adopted.phase as TicketPhase.Submitted
        assertEquals("already", submitted.order.orderId)
        assertEquals("coid-stable", submitted.order.clientOrderId)
    }

    @Test
    fun duplicateClientOrderResponseAdoptsTheExistingOrder() = runBlocking {
        var places = 0
        val session = TicketSession(
            placeOrder = { _, _ ->
                places += 1
                Result.failure(IllegalStateException("HTTP 409 Duplicate client_order_id — not re-sent"))
            },
            findExisting = { ticket, clientOrderId -> placed(ticket, clientOrderId, "already") },
            idFactory = { "coid-stable" }
        )
        session.addManual(buyTicket())
        val state = session.approve("t1")
        assertEquals(1, places)
        assertTrue(state.phase is TicketPhase.Submitted)
        assertEquals("already", (state.phase as TicketPhase.Submitted).order.orderId)
    }

    @Test
    fun pendingClientOrderIdSurvivesDismissAndANewProcess() = runBlocking {
        val store = MemoryOrderIntentStore()
        val ids = mutableListOf<String>()
        var n = 0
        fun session() = TicketSession(
            placeOrder = { _, clientOrderId ->
                ids += clientOrderId
                Result.failure(IllegalStateException("timeout"))
            },
            intentStore = store,
            idFactory = { "id-${++n}" }
        )
        val first = session()
        first.addManual(buyTicket())
        first.approve("t1")
        first.dismiss("t1")
        val second = session()
        second.onStart()
        second.addManual(buyTicket())
        second.approve("t1")
        assertEquals(listOf("id-1", "id-1"), ids)
        assertEquals("id-1", store.load().single().clientOrderId)
        assertEquals(PendingOrderIntent.STATE_UNCERTAIN, store.load().single().state)
    }

    @Test
    fun editingTermsDoesNotSendOrDropThePendingOrder() = runBlocking {
        val store = MemoryOrderIntentStore()
        val ids = mutableListOf<String>()
        val session = TicketSession(
            placeOrder = { _, clientOrderId ->
                ids += clientOrderId
                Result.failure(IllegalStateException("timeout"))
            },
            intentStore = store,
            idFactory = { "id-keep" }
        )
        session.addManual(buyTicket())
        session.approve("t1")
        session.revise("t1") {
            it.copy(limitPrice = 0.20, yesLimitPrice = 0.20, contracts = 24, stakeUsd = 4.8)
        }
        session.openApprove("t1")
        val blocked = session.approve("t1")
        assertEquals(listOf("id-keep"), ids)
        assertEquals(TicketSession.TERMS_CHANGED_WHILE_PENDING, blocked.lastError)
        val kept = store.load().single()
        assertEquals("id-keep", kept.clientOrderId)
        assertEquals(12, kept.contracts)
        assertEquals(0.40, kept.limitPrice, 1e-9)
    }

    @Test
    fun editedRetryAdoptsTheOriginalOrderWhenKalshiAlreadyHasIt() = runBlocking {
        var places = 0
        val session = TicketSession(
            placeOrder = { _, _ ->
                places += 1
                Result.failure(IllegalStateException("timeout"))
            },
            findExisting = { _, clientOrderId -> placed(buyTicket(), clientOrderId, "already") },
            idFactory = { "id-keep" }
        )
        session.addManual(buyTicket())
        session.approve("t1")
        session.revise("t1") {
            it.copy(limitPrice = 0.20, yesLimitPrice = 0.20, contracts = 24, stakeUsd = 4.8)
        }
        session.openApprove("t1")
        val adopted = session.approve("t1")
        assertEquals(1, places)
        val order = (adopted.phase as TicketPhase.Submitted).order
        assertEquals("already", order.orderId)
        assertEquals(0.40, order.ticket.limitPrice, 1e-9)
        assertEquals(12, order.ticket.contracts)
    }

    @Test
    fun definitiveRejectLetsTheNextApproveUseANewClientOrderId() = runBlocking {
        val store = MemoryOrderIntentStore()
        val ids = mutableListOf<String>()
        var n = 0
        var reject = true
        val session = TicketSession(
            placeOrder = { ticket, clientOrderId ->
                ids += clientOrderId
                if (reject) {
                    reject = false
                    Result.failure(IllegalStateException("HTTP 400 Rejected — check price"))
                } else {
                    Result.success(placed(ticket, clientOrderId, "ord-new"))
                }
            },
            intentStore = store,
            idFactory = { "id-${++n}" }
        )
        session.addManual(buyTicket())
        val failed = session.approve("t1")
        assertTrue(failed.phase is TicketPhase.Failed)
        assertTrue(store.load().isEmpty())
        session.openApprove("t1")
        val submitted = session.approve("t1")
        assertTrue(submitted.phase is TicketPhase.Submitted)
        assertEquals(listOf("id-1", "id-2"), ids)
    }

    @Test
    fun confirmQuoteMatchesTheOrderApproveSubmitsAndKeepsTheCap() {
        val ticket = buyTicket()
        val quote = ConfirmQuote.buy(ticket, stakeText = "5", centsText = "20")
        val submitted = TicketBuilder.repriceBuy(ticket, quote.submittedStakeUsd, quote.submittedLimitPrice)
        assertEquals(0.20, quote.submittedLimitPrice, 1e-9)
        assertEquals(submitted.contracts, quote.contracts)
        assertEquals(submitted.allInUsd ?: -1.0, quote.allInUsd, 1e-9)
        assertEquals(submitted.feeUsd ?: -1.0, quote.feeUsd, 1e-9)
        assertTrue(quote.contracts > ticket.contracts)
        assertTrue(quote.allInUsd <= 5.0 + 1e-6)
        val capped = ConfirmQuote.buy(ticket, stakeText = "40", centsText = "20")
        val cappedSubmit = TicketBuilder.repriceBuy(ticket, capped.submittedStakeUsd, capped.submittedLimitPrice)
        assertEquals(cappedSubmit.contracts, capped.contracts)
        assertTrue(capped.allInUsd <= 5.0 + 1e-6)
        assertTrue(capped.withinCap)
    }

    @Test
    fun repriceBuyUsesTypedLimitAndKeepsTheFiveDollarCap() {
        val ticket = buyTicket()
        val clipped = TicketBuilder.repriceBuy(ticket, stakeUsd = 25.0, limitPrice = 0.33)
        assertEquals(0.33, clipped.limitPrice, 1e-9)
        assertTrue((clipped.allInUsd ?: 0.0) <= 5.0 + 1e-6)
        assertTrue(clipped.sizingNote.contains("clipped", ignoreCase = true))
        val smaller = TicketBuilder.repriceBuy(ticket, stakeUsd = 2.0, limitPrice = 0.40)
        assertTrue((smaller.allInUsd ?: 99.0) <= 2.0 + 1e-6)
        assertTrue(smaller.contracts > 0)
    }

    private fun buyTicket() = TradeTicket(
        id = "t1",
        ticker = "KXBTC15M-T",
        side = "YES",
        bookSide = "bid",
        stakeUsd = 5.0,
        limitPrice = 0.40,
        yesLimitPrice = 0.40,
        contracts = 12,
        estimatedFillUsd = 4.8,
        maxPayoutUsd = 12.0,
        estimatedAvgFill = 0.40,
        sizingNote = "test",
        kind = TicketKind.MANUAL
    )

    private fun placed(ticket: TradeTicket, clientOrderId: String, orderId: String) = PlacedOrder(
        ticket = ticket,
        clientOrderId = clientOrderId,
        orderId = orderId,
        fillCount = 0.0,
        remainingCount = ticket.contracts.toDouble(),
        averageFillPrice = ticket.limitPrice,
        placedAtMs = 1L
    )
}
