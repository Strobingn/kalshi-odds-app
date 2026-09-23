package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.PayoutGate
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PayoutGateTest {

    @Test
    fun maxLimitForDefaultStakeIsFiveCents() {
        val max = PayoutGate.maxLimitForPayout(5.0, 100.0)
        assertEquals(0.05, max!!, 1e-9)
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
    fun replaceProposalsPreservesIdAndDoesNotPlace() {
        val placed = AtomicInteger(0)
        val session = session(placed)
        session.replaceProposals(listOf(sampleTicket("t1", ticker = "KXBTC15M-A")))
        val id = session.snapshot().proposals.single().id
        session.replaceProposals(listOf(sampleTicket("t2", ticker = "KXBTC15M-A")))
        assertEquals(id, session.snapshot().proposals.single().id)
        assertEquals(0, placed.get())
    }

    private fun session(placed: AtomicInteger) = TicketSession(
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
        }
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
                ticketStakeUsd = 5.0
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
        assertEquals(125, ticket!!.contracts)
        assertEquals("YES", ticket.side)
        assertEquals("bid", ticket.bookSide)
    }

    @Test
    fun cheapLiquidClearedMarketIsProposed() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketRespectGates = true, ticketsEnabled = true, ticketStakeUsd = 5.0),
            alertsPaused = false,
            idFactory = { "id" },
            nowMs = 1L
        )
        val ticket = TicketBuilder.propose(market(passed = true, muted = false, ask = 0.03, volume = 5_000.0), ctx)
        assertTrue(ticket != null)
        assertEquals(166, ticket!!.contracts)
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
