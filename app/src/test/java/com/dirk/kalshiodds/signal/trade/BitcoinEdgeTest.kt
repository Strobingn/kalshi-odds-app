package com.dirk.kalshiodds.signal.trade

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BitcoinEdgeTest {
    private fun ticket() = TradeTicket("t", "KXBTC15M-26OCT091000-00", "NO", "ask", 1.0,
        .38, .62, 2, .8, 2.0, .38, sizingNote = "test", kind = TicketKind.MANUAL)

    @Test fun noLimitUsesYesBookAndExactRequestedSize() {
        val t = LimitOrderEditor.edit(ticket(), 3, .4, LimitOptions(true), 100.0, 1000)
        assertEquals("ask", t.bookSide)
        assertEquals(.6, t.yesLimitPrice, 1e-9)
        assertEquals(3, t.contracts)
        assertTrue(t.limitOptions.postOnly)
    }
    @Test fun rejectsTooLargeOrUnfundedLimits() {
        assertTrue(runCatching { LimitOrderEditor.edit(ticket(), 100, .4, LimitOptions(), 100.0, 1000) }.isFailure)
        assertTrue(runCatching { LimitOrderEditor.edit(ticket(), 2, .4, LimitOptions(), null, 1000) }.isFailure)
        assertTrue(runCatching { LimitOrderEditor.edit(ticket(), 2, Double.NaN, LimitOptions(), 100.0, 1000) }.isFailure)
    }
    @Test fun expiryAndMakerRequireGtc() {
        assertTrue(runCatching { LimitOptions(true, "immediate_or_cancel").validate(1000, false) }.isFailure)
        assertTrue(runCatching { LimitOptions(false, "immediate_or_cancel", 100).validate(1000, false) }.isFailure)
        assertTrue(runCatching { LimitOptions(expirationTime = 1).validate(1000, false) }.isFailure)
        LimitOptions().validate(1000, false)
    }
    @Test fun interruptedSubmissionSurvivesRestartAndBlocksDuplicate() = runBlocking {
        var journal = emptyList<PlacedOrder>()
        var calls = 0
        val session = TicketSession(placeOrder = { _, _ -> calls++; Result.failure(java.io.IOException("timeout")) },
            saveWorking = { _, orders -> journal = orders }, loadWorking = { journal })
        session.selectAccount("a")
        session.addManual(ticket())
        session.approve("t")
        assertEquals("unknown", journal.single().status)
        assertTrue(journal.single().clientOrderId.isNotBlank())
        session.onStart()
        session.addManual(ticket())
        session.approve("t")
        assertEquals(1, calls)
        val restarted = TicketSession(placeOrder = { _, _ -> error("must not submit") }, loadWorking = { journal })
        restarted.selectAccount("a")
        assertEquals(journal.single().clientOrderId, restarted.snapshot().working.single().clientOrderId)
    }
    @Test fun diskFailurePreventsSubmission() = runBlocking {
        var calls = 0
        val session = TicketSession(placeOrder = { _, _ -> calls++; error("must not submit") },
            saveWorking = { _, _ -> error("disk full") })
        session.addManual(ticket()); session.approve("t")
        assertEquals(0, calls)
    }
    @Test fun reconciliationPreservesPartialFillAndTerminalStatus() = runBlocking {
        val order = PlacedOrder(ticket(), "cid", "oid", 1.0, 1.0, .38, 0L, status = "resting")
        val session = TicketSession(placeOrder = { _, _ -> error("must not submit") },
            loadWorking = { listOf(order) }, refreshOrder = { it.copy(fillCount = 2.0, remainingCount = 0.0, status = "executed") })
        session.selectAccount("a"); session.reconcileWorking()
        assertEquals(2.0, session.snapshot().working.single().fillCount, 0.0)
        assertFalse(session.snapshot().working.single().isResting)
    }
    @Test fun noHoldingTimerAndBothLegsCosted() {
        val rec = ScalpEngine.Replay("btc", "YES", 1, .38, 10, LiveOrderSizer.allInUsd(10, .38), 900000)
        val engine = ScalpEngine(listOf(rec))
        assertNull(engine.exitReason(.38, .40, .60))
        assertEquals("8¢ price bounce", engine.exitReason(.38, .46, .60))
        engine.settle("btc", false, 900001)
        assertEquals(-rec.entryDebit, engine.snapshot().single().netUsd!!, 0.0)
        assertEquals("official settlement", engine.snapshot().single().reason)
    }
    @Test fun bounceCanEnterNearOpenAndJustBeforeCloseAndCostsBothLegs() {
        for (start in listOf(1000L, 894000L)) {
            val engine = ScalpEngine()
            val ticker = "KXBTC15M-26OCT091000-00"
            val prices = listOf(.50, .52, .44, .445, .455)
            var proposals = emptyList<TradeTicket>()
            for ((i, ask) in prices.withIndex()) {
                val now = start + i * 1000
                val market = com.dirk.kalshiodds.domain.MarketUiModel(
                    ticker = ticker, title = "BTC", subtitle = null, floorStrike = 60000.0,
                    yesBid = ask - .01, yesAsk = ask, noBid = 1 - ask, noAsk = 1 - ask + .01,
                    lastPrice = ask, yesProbabilityPercent = ask * 100, noProbabilityPercent = (1 - ask) * 100,
                    aiYesPercent = 70.0, volume = 100.0, volume24h = 100.0, closeTimeLocal = null,
                    closeTimeEpochMs = 900000L, openTimeEpochMs = 0, status = "active", seriesLabel = "Bitcoin")
                val book = com.dirk.kalshiodds.signal.engine.BookLevelSnapshot(
                    yes = listOf((ask - .01) to 20.0), no = listOf((1 - ask) to 20.0))
                val ctx = TicketBuilder.Context(com.dirk.kalshiodds.signal.config.SignalSettings(), false,
                    books = mapOf(ticker to book), nowMs = now, bankrollUsd = 100.0)
                proposals = engine.observe(listOf(market), ctx) { true }
            }
            val entry = proposals.first { it.kind == TicketKind.SCALP && it.side == "YES" }
            assertFalse(entry.paperOnly)
            assertTrue(entry.canApprove)
            assertEquals(.455, entry.limitPrice, 1e-9)
            val replay = engine.snapshot().single()
            assertTrue(replay.entryDebit > replay.entry * replay.count)
            assertNull(replay.endedMs)
        }
    }

    @Test fun liveScalpSizeUsesFreshCashNotConfiguredBankroll() {
        val scalp = ticket().copy(kind = TicketKind.SCALP)
        assertTrue(runCatching { LimitOrderEditor.edit(scalp, 3, .4, LimitOptions(), 100.0, 1000) }.isFailure)
        assertTrue(LimitOrderEditor.edit(scalp, 1, .4, LimitOptions(), 100.0, 1000).canApprove)
    }
    @Test fun changedAccountBlocksPlacement() = runBlocking {
        var account = "a"
        var calls = 0
        val session = TicketSession(placeOrder = { _, _ -> calls++; error("must not submit") }, currentAccount = { account })
        session.selectAccount("a"); session.addManual(ticket()); account = "b"; session.approve("t")
        assertEquals(0, calls)
    }

}
