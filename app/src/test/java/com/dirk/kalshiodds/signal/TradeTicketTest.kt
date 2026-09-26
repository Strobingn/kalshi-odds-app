package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.PayoutGate
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PayoutGateTest {

    @Test
    fun maxLimitForDefaultStakeIsFiveCents() {
        val max = PayoutGate.maxLimitForPayout(5.0, 100.0)
        assertEquals(0.05, max!!, 1e-9)
    }

    @Test
    fun hunterDollarOneToTwentyFiveIsFourCents() {
        val max = PayoutGate.maxLimitForPayout(
            SignalConstants.HUNTER_STAKE_USD,
            SignalConstants.HUNTER_MIN_PAYOUT_USD
        )
        assertEquals(0.04, max!!, 1e-9)
        val pass = PayoutGate.evaluate(stakeUsd = 1.0, bestAsk = 0.04, quotedSize = 500.0, minPayoutUsd = 25.0)
        assertTrue(pass.reason, pass.ok)
        assertEquals(25, pass.contracts)
        assertEquals(25.0, pass.maxPayoutUsd, 1e-9)
        val fail = PayoutGate.evaluate(stakeUsd = 1.0, bestAsk = 0.05, quotedSize = 500.0, minPayoutUsd = 25.0)
        assertFalse(fail.ok)
    }

    @Test
    fun contractsAndPayoutFormula() {
        // floor(5 / 0.05) = 100 → $100 max payout
        assertEquals(100, PayoutGate.contractsFor(5.0, 0.05))
        assertEquals(100.0, PayoutGate.maxPayoutUsd(100), 1e-9)
        // 6¢ cannot hit $100 from $5
        assertEquals(83, PayoutGate.contractsFor(5.0, 0.06))
        assertEquals(83.0, PayoutGate.maxPayoutUsd(83), 1e-9)
        // 4¢ → 125 contracts → $125
        assertEquals(125, PayoutGate.contractsFor(5.0, 0.04))
    }

    @Test
    fun fiveDollarsAtFiveCentsPasses() {
        val r = PayoutGate.evaluate(stakeUsd = 5.0, bestAsk = 0.05, quotedSize = 500.0)
        assertTrue(r.reason, r.ok)
        assertEquals(100, r.contracts)
        assertEquals(100.0, r.maxPayoutUsd, 1e-9)
        assertEquals(5.0, r.estimatedFillUsd, 1e-9)
        assertEquals(0.05, r.limitPrice, 1e-9)
    }

    @Test
    fun priceTooHighDoesNotPropose() {
        val r = PayoutGate.evaluate(stakeUsd = 5.0, bestAsk = 0.06, quotedSize = 5_000.0)
        assertFalse(r.ok)
        assertTrue(r.reason, r.reason.contains("price too high"))
        assertEquals(0, r.contracts)
    }

    @Test
    fun thinBookDoesNotPropose() {
        val r = PayoutGate.evaluate(
            stakeUsd = 5.0,
            bestAsk = 0.04,
            askLevels = listOf(0.04 to 10.0),
            quotedSize = 10.0
        )
        assertFalse(r.ok)
        assertTrue(r.reason, r.reason.contains("thin"))
    }

    @Test
    fun walkStopsAtMaxPrice() {
        val walk = PayoutGate.walkAsks(
            levels = listOf(0.03 to 40.0, 0.04 to 80.0, 0.08 to 9_000.0),
            maxPrice = 0.05,
            contractsNeeded = 100
        )
        assertEquals(100, walk.fillable)
        assertTrue(walk.worstPrice <= 0.05 + 1e-9)
        assertTrue(walk.vwap <= 0.05 + 1e-9)
    }

    @Test
    fun missingAskRejected() {
        val r = PayoutGate.evaluate(stakeUsd = 5.0, bestAsk = null, quotedSize = 1_000.0)
        assertFalse(r.ok)
    }

    @Test
    fun deciCentAskSizesContracts() {
        val r = PayoutGate.evaluate(stakeUsd = 1.0, bestAsk = 0.006, quotedSize = 500.0, minPayoutUsd = 25.0)
        assertTrue(r.reason, r.ok)
        assertEquals(166, r.contracts)
    }

    @Test
    fun pointOneCentAskIsNotClampedAndShowsVisibleSize() {
        val r = PayoutGate.evaluate(
            stakeUsd = 5.0,
            bestAsk = 0.001,
            quotedSize = 8000.0,
            minPayoutUsd = 100.0
        )
        assertTrue(r.reason, r.ok)
        assertEquals(5000, r.contracts)
        assertEquals(0.001, r.limitPrice, 1e-12)
        assertEquals(8000, r.fillableContracts)
        assertTrue(r.reason.contains("0.1¢"))
        assertTrue(r.reason.contains("8000 visible"))
        val thin = PayoutGate.evaluate(
            stakeUsd = 5.0,
            bestAsk = 0.001,
            quotedSize = 10.0,
            minPayoutUsd = 100.0
        )
        assertFalse(thin.ok)
        assertTrue(thin.reason.contains("liquidity too thin"))
        assertTrue(thin.reason.contains("0.1¢"))
    }

    @Test
    fun stakeClipAndRaiseConfirm() {
        assertEquals(25.0, PayoutGate.clipStake(99.0), 1e-9)
        assertEquals(1.0, PayoutGate.clipStake(0.1), 1e-9)
        assertFalse(PayoutGate.requiresRaiseConfirm(5.0))
        assertTrue(PayoutGate.requiresRaiseConfirm(6.0))
        assertTrue(PayoutGate.raiseConfirmMatches("raise"))
        assertTrue(PayoutGate.raiseConfirmMatches("RAISE"))
        assertFalse(PayoutGate.raiseConfirmMatches("ok"))
    }
}

class TicketSessionTest {

    @Test
    fun startIsIdleAndPlacesNothing() {
        val placed = AtomicInteger(0)
        val session = session(placed)
        session.onStart()
        assertTrue(session.snapshot().phase is TicketPhase.Idle)
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
    }

    @Test
    fun proposeAndDismissNeverPlace() {
        val placed = AtomicInteger(0)
        val session = session(placed)
        val ticket = sampleTicket("t1")
        session.replaceProposals(listOf(ticket))
        assertTrue(session.snapshot().phase is TicketPhase.Proposed)
        session.dismiss("t1")
        session.openApprove("missing")
        session.cancelApprove()
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
    }

    @Test
    fun approveWrongIdDoesNotPlace() = runBlocking {
        val placed = AtomicInteger(0)
        val session = session(placed)
        session.replaceProposals(listOf(sampleTicket("t1")))
        session.approve("other-id")
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
        assertTrue(session.snapshot().lastError!!.contains("matching ticket"))
    }

    @Test
    fun approveIdleDoesNotPlace() = runBlocking {
        val placed = AtomicInteger(0)
        val session = session(placed)
        session.onStart()
        session.approve("t1")
        assertEquals(0, placed.get())
    }

    @Test
    fun lastOrderErrorRedactsPemAndKeepsKalshiBody() {
        val raw = """401 {"error":{"code":"INCORRECT_API_KEY_SIGNATURE"}} -----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEA
-----END RSA PRIVATE KEY-----"""
        val cleaned = com.dirk.kalshiodds.signal.trade.LastOrderErrorStore.redact(raw)
        assertFalse(cleaned.contains("BEGIN RSA"))
        assertFalse(cleaned.contains("MIIEowIBAAKCAQEA"))
        assertTrue(cleaned.contains("INCORRECT_API_KEY_SIGNATURE"))
        assertTrue(cleaned.contains("[redacted"))
    }

    @Test
    fun blockedTicketApproveShowsReasonAndDoesNotPlace() = runBlocking {
        val placed = AtomicInteger(0)
        val session = session(placed)
        val blocked = sampleTicket("t1").copy(
            contracts = 0,
            blockedReason = "No sellers on YES right now"
        )
        session.replaceProposals(listOf(blocked))
        session.openApprove("t1")
        val after = session.approve("t1")
        assertEquals(0, placed.get())
        assertEquals("No sellers on YES right now", after.lastError)
    }

    @Test
    fun onlyMatchingApprovePlacesOnce() = runBlocking {
        val placed = AtomicInteger(0)
        val session = session(placed)
        val ticket = sampleTicket("t1")
        session.replaceProposals(listOf(ticket))
        assertTrue(session.openApprove("t1"))
        assertTrue(session.snapshot().phase is TicketPhase.AwaitingApprove)
        assertEquals(0, placed.get())
        val after = session.approve("t1")
        assertEquals(1, placed.get())
        assertEquals(1, after.placementCount)
        assertTrue(after.phase is TicketPhase.Submitted)
        session.approve("t1")
        assertEquals(1, placed.get())
    }

    @Test
    fun replaceProposalsKeepsManualTicket() {
        val placed = AtomicInteger(0)
        val session = session(placed)
        val manual = sampleTicket("m1").copy(kind = com.dirk.kalshiodds.signal.trade.TicketKind.MANUAL)
        session.addManual(manual)
        assertTrue(session.snapshot().phase is TicketPhase.AwaitingApprove)
        session.replaceProposals(listOf(sampleTicket("auto", ticker = "KXBTC15M-OTHER")))
        assertTrue(session.snapshot().proposals.any { it.id == "m1" })
        assertTrue(session.snapshot().phase is TicketPhase.AwaitingApprove)
        assertEquals(0, placed.get())
    }

    @Test
    fun blockedTicketSetsLastErrorAndDoesNotPlace() = runBlocking {
        val placed = AtomicInteger(0)
        val session = session(placed)
        val blocked = sampleTicket("b1").copy(
            blockedReason = LiveOrderSizer.belowMinProfitMessage(2.47, 10.0),
            contracts = 7,
            profitIfWinUsd = 2.47
        )
        session.addManual(blocked)
        assertFalse(blocked.canApprove)
        val after = session.approve("b1")
        assertEquals(0, placed.get())
        assertTrue(after.lastError!!.contains("below"))
        assertTrue(after.lastError!!.contains("$10") || after.lastError!!.contains("10"))
    }

    @Test
    fun replaceProposalsPreservesIdAndDoesNotPlace() {
        val placed = AtomicInteger(0)
        val session = session(placed)
        session.replaceProposals(listOf(sampleTicket("t1", ticker = "KXBTC15M-A")))
        val id = session.snapshot().proposals.single().id
        session.replaceProposals(listOf(sampleTicket("t2", ticker = "KXBTC15M-A")))
        assertEquals(id, session.snapshot().proposals.single().id)
        assertEquals(0, placed.get())
    }

    @Test
    fun rolloverVoidsManualOnceThenDropsAndClearsError() = runBlocking {
        val placed = AtomicInteger(0)
        val clock = AtomicLong(1_000L)
        val session = session(placed, nowMs = { clock.get() })
        val manual = sampleTicket("m1", ticker = "KXBTC15M-OLD").copy(
            kind = com.dirk.kalshiodds.signal.trade.TicketKind.MANUAL
        )
        session.addManual(manual)
        session.voidTickers(setOf("KXBTC15M-OLD"))
        val first = session.snapshot()
        val voided = first.proposals.single { it.id == "m1" }
        assertEquals(TicketSession.WINDOW_CLOSED, voided.blockedReason)
        assertFalse(voided.canApprove)
        assertEquals(TicketSession.WINDOW_CLOSED_NOTICE, first.lastError)
        assertEquals(1, session.windowClosedNoticeCount)
        session.approve("m1")
        assertEquals(0, placed.get())

        val liveHunter = sampleTicket("h1", ticker = "KXBTC15M-NEW").copy(
            kind = com.dirk.kalshiodds.signal.trade.TicketKind.HUNTER
        )
        session.replaceProposals(listOf(liveHunter), liveTickers = setOf("KXBTC15M-NEW"))
        val held = session.snapshot()
        assertTrue(held.proposals.any { it.id == "m1" })
        assertEquals(TicketSession.WINDOW_CLOSED_NOTICE, held.lastError)
        assertEquals(1, session.windowClosedNoticeCount)

        clock.set(1_000L + TicketSession.VOID_HOLD_MS)
        session.replaceProposals(
            listOf(liveHunter.copy(id = "h2")),
            liveTickers = setOf("KXBTC15M-NEW")
        )
        val dropped = session.snapshot()
        assertTrue(dropped.proposals.none { it.id == "m1" })
        assertNull(dropped.lastError)
        assertEquals(1, session.windowClosedNoticeCount)

        clock.addAndGet(2_000L)
        session.replaceProposals(
            listOf(liveHunter.copy(id = "h3")),
            liveTickers = setOf("KXBTC15M-NEW")
        )
        assertNull(session.snapshot().lastError)
        assertEquals(1, session.windowClosedNoticeCount)
        assertEquals(0, placed.get())
    }

    @Test
    fun lastOrderErrorRecordedAtMostOncePerVoidedTicket() {
        val recorded = mutableListOf<String>()
        var previous: String? = null
        val clock = AtomicLong(1_000L)
        val session = session(AtomicInteger(0), nowMs = { clock.get() })
        session.addManual(
            sampleTicket("m1", ticker = "KXBTC15M-OLD").copy(
                kind = com.dirk.kalshiodds.signal.trade.TicketKind.MANUAL
            )
        )
        repeat(5) { i ->
            session.voidTickers(setOf("KXBTC15M-OLD"))
            session.replaceProposals(emptyList(), liveTickers = setOf("KXBTC15M-NEW"))
            com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce
                .accept(previous, session.snapshot().lastError)
                ?.let { err ->
                    recorded += err
                    previous = err
                }
            if (session.snapshot().lastError.isNullOrBlank()) previous = null
            if (i == 2) clock.set(1_000L + TicketSession.VOID_HOLD_MS)
        }
        assertEquals(listOf(TicketSession.WINDOW_CLOSED_NOTICE), recorded)
        assertEquals(1, session.windowClosedNoticeCount)
    }

    @Test
    fun submittingOrderIsUntouchedByWindowClose() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val placed = AtomicInteger(0)
        val clock = AtomicLong(1_000L)
        val session = TicketSession(
            placeOrder = { ticket, clientId ->
                entered.complete(Unit)
                release.await()
                placed.incrementAndGet()
                Result.success(
                    com.dirk.kalshiodds.signal.trade.PlacedOrder(
                        ticket = ticket,
                        clientOrderId = clientId,
                        orderId = "ord-live",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = null,
                        placedAtMs = 1L
                    )
                )
            },
            nowMs = { clock.get() }
        )
        session.addManual(sampleTicket("t1", ticker = "KXBTC15M-OLD"))
        val job = launch { session.approve("t1") }
        entered.await()
        assertTrue(session.snapshot().phase is TicketPhase.Submitting)
        session.voidTickers(setOf("KXBTC15M-OLD"))
        session.replaceProposals(emptyList(), liveTickers = emptySet())
        clock.set(1_000L + TicketSession.VOID_HOLD_MS)
        session.replaceProposals(emptyList(), liveTickers = setOf("KXBTC15M-NEW"))
        val mid = session.snapshot()
        assertTrue(mid.phase is TicketPhase.Submitting)
        val submitting = mid.phase as TicketPhase.Submitting
        assertEquals("t1", submitting.ticket.id)
        assertNull(submitting.ticket.blockedReason)
        assertTrue(mid.proposals.any { it.id == "t1" && it.blockedReason == null })
        assertNull(mid.lastError)
        assertEquals(0, session.windowClosedNoticeCount)
        release.complete(Unit)
        job.join()
        assertEquals(1, placed.get())
        assertTrue(session.snapshot().phase is TicketPhase.Submitted)
    }

    @Test
    fun approveOnVoidedTicketNeverPlaces() = runBlocking {
        val placed = AtomicInteger(0)
        val session = session(placed, nowMs = { 1_000L })
        session.addManual(
            sampleTicket("m1", ticker = "KXBTC15M-OLD").copy(
                kind = com.dirk.kalshiodds.signal.trade.TicketKind.MANUAL
            )
        )
        session.voidTickers(setOf("KXBTC15M-OLD"))
        val after = session.approve("m1")
        assertEquals(0, placed.get())
        assertEquals(TicketSession.WINDOW_CLOSED_NOTICE, after.lastError)
        assertFalse(session.snapshot().proposals.single { it.id == "m1" }.canApprove)
    }

    @Test
    fun lastOrderErrorOnceSkipsRepeatWindowClosed() {
        assertEquals(
            TicketSession.WINDOW_CLOSED_NOTICE,
            com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce.accept(
                null,
                TicketSession.WINDOW_CLOSED_NOTICE
            )
        )
        assertNull(
            com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce.accept(
                TicketSession.WINDOW_CLOSED_NOTICE,
                TicketSession.WINDOW_CLOSED_NOTICE
            )
        )
        assertNull(
            com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce.accept(
                TicketSession.WINDOW_CLOSED,
                TicketSession.WINDOW_CLOSED
            )
        )
        assertNull(com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce.accept("x", "PAPER filled"))
    }

    private fun session(placed: AtomicInteger, nowMs: () -> Long = { System.currentTimeMillis() }) = TicketSession(
        placeOrder = { ticket, clientId ->
            placed.incrementAndGet()
            Result.success(
                com.dirk.kalshiodds.signal.trade.PlacedOrder(
                    ticket = ticket,
                    clientOrderId = clientId,
                    orderId = "ord-1",
                    fillCount = 0.0,
                    remainingCount = ticket.contracts.toDouble(),
                    averageFillPrice = null,
                    placedAtMs = 1L
                )
            )
        },
        nowMs = nowMs
    )
}

class TicketBuilderGateTest {

    @Test
    fun qualityGatesBlockWhenEnabled() {
        val cheap = market(passed = false, muted = false, ask = 0.04)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketRespectGates = true, ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "id" },
            nowMs = 1L
        )
        assertNull(TicketBuilder.propose(cheap, ctx))
        val muted = market(passed = true, muted = true, ask = 0.04)
        assertNull(TicketBuilder.propose(muted, ctx))
        val paused = TicketBuilder.Context(
            settings = SignalSettings(ticketRespectGates = true, ticketsEnabled = true),
            alertsPaused = true,
            idFactory = { "id" },
            nowMs = 1L
        )
        assertNull(TicketBuilder.propose(market(passed = true, muted = false, ask = 0.04), paused))
    }

    @Test
    fun gatesCanBeDisabledForTickets() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(
                ticketRespectGates = false,
                ticketsEnabled = true,
                ticketStakeUsd = 5.0,
                winTargetEnabled = false
            ),
            alertsPaused = true,
            idFactory = { "id" },
            nowMs = 1L
        )
        val ticket = TicketBuilder.propose(
            market(passed = false, muted = true, ask = 0.04, volume = 5_000.0),
            ctx
        )
        assertTrue(ticket != null)
        assertEquals(LiveOrderSizer.size(0.04).count, ticket!!.contracts)
        assertEquals("YES", ticket.side)
        assertEquals("bid", ticket.bookSide)
    }

    @Test
    fun cheapLiquidClearedMarketIsProposed() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(
                ticketRespectGates = true,
                ticketsEnabled = true,
                ticketStakeUsd = 5.0,
                winTargetEnabled = false
            ),
            alertsPaused = false,
            idFactory = { "id" },
            nowMs = 1L
        )
        val ticket = TicketBuilder.propose(market(passed = true, muted = false, ask = 0.03, volume = 5_000.0), ctx)
        assertTrue(ticket != null)
        assertEquals(LiveOrderSizer.size(0.03).count, ticket!!.contracts)
        assertTrue(ticket.maxPayoutUsd >= 100.0)
    }

    @Test
    fun expensiveMarketNotProposed() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketRespectGates = false, ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "id" },
            nowMs = 1L
        )
        assertNull(TicketBuilder.propose(market(passed = true, muted = false, ask = 0.40), ctx))
    }

    @Test
    fun snapshotBookDoesNotThrowAndFeedsAskLevels() {
        val book = com.dirk.kalshiodds.signal.engine.BookLevelSnapshot(
            yes = listOf(0.96 to 200.0),
            no = listOf(0.96 to 200.0)
        )
        val m = market(passed = true, muted = false, ask = 0.04, volume = 5_000.0)
        val yesAsks = TicketBuilder.askLevels(m, "YES", book)
        assertEquals(0.04, yesAsks.single().first, 1e-9)
        assertEquals(200.0, yesAsks.single().second, 1e-9)
        val quoted = TicketBuilder.quotedSize(m, "YES", book)
        assertTrue(quoted != null && quoted >= 200.0)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(
                ticketRespectGates = true,
                ticketsEnabled = true,
                ticketStakeUsd = 5.0,
                winTargetEnabled = false
            ),
            alertsPaused = false,
            books = mapOf(m.ticker to book),
            idFactory = { "snap" },
            nowMs = 1L
        )
        val ticket = TicketBuilder.propose(m, ctx)
        assertTrue(ticket != null)
        assertEquals(LiveOrderSizer.size(0.04).count, ticket!!.contracts)
    }

    @Test
    fun hunterIgnoresQualityGatesAndUsesOneDollar() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(
                ticketRespectGates = true,
                ticketsEnabled = true,
                winTargetEnabled = false
            ),
            alertsPaused = true,
            idFactory = { "hunter" },
            nowMs = 1L
        )
        val ticket = TicketBuilder.proposeHunter(
            market(passed = false, muted = true, ask = 0.04, volume = 5_000.0),
            ctx
        )
        assertTrue(ticket != null)
        assertTrue(ticket!!.stakeUsd in 4.0..5.0 + 1e-6)
        assertEquals(LiveOrderSizer.size(0.04).count, ticket.contracts)
        assertTrue(ticket.maxPayoutUsd >= 25.0)
        assertEquals(com.dirk.kalshiodds.signal.trade.TicketKind.HUNTER, ticket.kind)
    }

    @Test
    fun manualBuyOpensWithoutPayoutFloor() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true, ticketStakeUsd = 1.0),
            alertsPaused = false,
            idFactory = { "manual" },
            nowMs = 1L
        )
        val ticket = TicketBuilder.proposeManual(
            market(passed = true, muted = false, ask = 0.40, volume = 5_000.0),
            "YES",
            ctx
        )
        assertTrue(ticket != null)
        assertEquals(com.dirk.kalshiodds.signal.trade.TicketKind.MANUAL, ticket!!.kind)
        assertEquals("YES", ticket.side)
        assertTrue(ticket.contracts >= 1)
    }

    @Test
    fun ticketsDisabledYieldsNothing() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = false, ticketRespectGates = false),
            alertsPaused = false
        )
        assertTrue(TicketBuilder.proposeAll(listOf(market(true, false, 0.03)), ctx).isEmpty())
    }
}

class DefaultConfigV22Test {
    @Test
    fun parsesTicketDefaults() {
        val cfg = com.dirk.kalshiodds.signal.config.DefaultSignalConfig.parse(
            """{"ticketStakeUsd":5,"ticketRespectGates":true,"ticketsEnabled":true}"""
        )
        assertEquals(5.0, cfg.ticketStakeUsd, 1e-9)
        assertTrue(cfg.ticketRespectGates)
        assertTrue(cfg.ticketsEnabled)
        assertEquals(SignalConstants.DEFAULT_TICKET_STAKE_USD, 5.0, 1e-9)
        assertEquals(SignalConstants.TICKET_STAKE_HARD_CAP_USD, 25.0, 1e-9)
        assertEquals(SignalConstants.DEFAULT_MIN_PAYOUT_USD, 100.0, 1e-9)
        assertEquals(SignalConstants.HUNTER_STAKE_USD, 1.0, 1e-9)
        assertEquals(SignalConstants.HUNTER_MIN_PAYOUT_USD, 25.0, 1e-9)
        assertEquals(SignalConstants.PAPER_START_USD, 100.0, 1e-9)
        assertEquals(SignalConstants.PAPER_STAKE_USD, 5.0, 1e-9)
        val paperCfg = com.dirk.kalshiodds.signal.config.DefaultSignalConfig.parse(
            """{"paperTradingEnabled":true}"""
        )
        assertTrue(paperCfg.paperTradingEnabled)
    }
}

private fun sampleTicket(id: String, ticker: String = "KXBTC15M-X") = TradeTicket(
    id = id,
    ticker = ticker,
    side = "YES",
    bookSide = "bid",
    stakeUsd = 5.0,
    limitPrice = 0.05,
    yesLimitPrice = 0.05,
    contracts = 100,
    estimatedFillUsd = 5.0,
    maxPayoutUsd = 100.0,
    estimatedAvgFill = 0.05,
    sizingNote = "test"
)

private fun market(
    passed: Boolean,
    muted: Boolean,
    ask: Double,
    volume: Double = 2_000.0
) = MarketUiModel(
    ticker = "KXBTC15M-T",
    title = "BTC",
    subtitle = null,
    floorStrike = null,
    yesBid = (ask - 0.01).coerceAtLeast(0.01),
    yesAsk = ask,
    noBid = 1.0 - ask,
    noAsk = 1.0 - (ask - 0.01),
    lastPrice = ask,
    yesProbabilityPercent = ask * 100,
    noProbabilityPercent = (1.0 - ask) * 100,
    volume = volume,
    volume24h = volume,
    openInterest = volume,
    liquidityDollars = volume,
    closeTimeLocal = null,
    closeTimeEpochMs = null,
    status = "open",
    seriesLabel = "Bitcoin",
    passedFilter = passed,
    muted = muted,
    predictedSide = "YES",
    edgePp = 8.0,
    netEdgePp = 6.0,
    netEvDollars = 0.04
)
