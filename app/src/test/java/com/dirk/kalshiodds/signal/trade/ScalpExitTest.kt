package com.dirk.kalshiodds.signal.trade

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpExitTest {

    @Test
    fun buysAnythingUnderFiftyNotACoinFlip() {
        assertTrue(ScalpExit.isLowPrice(0.04))
        assertTrue(ScalpExit.isLowPrice(0.20))
        assertTrue(ScalpExit.isLowPrice(0.49))
        assertFalse(ScalpExit.isLowPrice(0.50))
        assertFalse(ScalpExit.isLowPrice(0.62))
        assertFalse(ScalpExit.isLowPrice(0.0))
        assertFalse(ScalpExit.isLowPrice(null))
    }

    @Test
    fun cheaperAskBuysMoreCashAndMoreContracts() {
        val lowStake = ScalpExit.stakeUsd(0.04, 100.0)
        val highStake = ScalpExit.stakeUsd(0.40, 100.0)
        assertTrue(lowStake > highStake)
        assertTrue(ScalpExit.contractsFor(0.04, 100.0) > ScalpExit.contractsFor(0.40, 100.0))
        assertTrue(lowStake < 50.0)
        assertEquals(0.0, ScalpExit.stakeUsd(0.50, 100.0), 0.0)
    }

    @Test
    fun holdsANewHighAndSellsWhenTheBidComesOffIt() {
        val contracts = ScalpExit.contractsFor(0.04, 100.0)
        val entry = 0.04
        val entryFee = KalshiFee.total(contracts, entry)
        assertFalse(ScalpExit.shouldSell(entry, 0.15, entry, contracts, entryFee))
        assertFalse(ScalpExit.shouldSell(entry, 0.14, 0.15, contracts, entryFee))
        assertTrue(ScalpExit.shouldSell(entry, 0.12, 0.15, contracts, entryFee))
        assertFalse(ScalpExit.shouldSell(entry, null, 0.15, contracts, entryFee))
    }

    @Test
    fun oneCentOfRiseOnOneContractIsNotARollover() {
        val entryFee = KalshiFee.total(1, 0.10)
        assertFalse(ScalpExit.shouldSell(0.10, 0.12, 0.10, 1, entryFee))
        assertFalse(ScalpExit.shouldSell(0.10, 0.11, 0.12, 1, entryFee))
    }

    @Test
    fun scalpSellReplacesItselfAndNeverPlaces() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { _, _ ->
                placed.incrementAndGet()
                error("live order must not run")
            }
        )
        session.syncScalpSells(listOf(sell("a", stake = 1.0)))
        session.syncScalpSells(listOf(sell("b", stake = 3.5)))
        val sells = session.snapshot().proposals.filter { it.isSell }
        assertEquals(1, sells.size)
        assertEquals("a", sells.single().id)
        assertEquals(ScalpExit.SELL_NOTE, sells.single().gateNote)
        assertTrue(session.snapshot().phase is TicketPhase.Proposed)
        assertEquals(0, placed.get())
        session.syncScalpSells(emptyList())
        assertTrue(session.snapshot().proposals.none { it.isSell })
        assertTrue(session.snapshot().phase is TicketPhase.Idle)
        assertEquals(0, session.placementCount)
    }

    private fun sell(id: String, stake: Double) = TradeTicket(
        id = id,
        ticker = "KXBTC15M-S",
        side = "YES",
        bookSide = "ask",
        stakeUsd = stake,
        limitPrice = 0.12,
        yesLimitPrice = 0.12,
        contracts = 25,
        estimatedFillUsd = stake,
        maxPayoutUsd = stake,
        estimatedAvgFill = 0.12,
        sizingNote = "sell",
        kind = TicketKind.SELL,
        reduceOnly = true,
        heldContracts = 25
    )
}
