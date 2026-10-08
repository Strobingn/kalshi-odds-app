package com.dirk.kalshiodds.signal.trade

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpExitTest {

    @Test
    fun onlyBuysAtTwentyCentsOrLess() {
        assertTrue(ScalpExit.isLowPrice(0.04))
        assertTrue(ScalpExit.isLowPrice(0.20))
        assertFalse(ScalpExit.isLowPrice(0.21))
        assertFalse(ScalpExit.isLowPrice(0.0))
        assertFalse(ScalpExit.isLowPrice(null))
    }

    @Test
    fun sellsOnlyAfterTheBidClearsFees() {
        val contracts = 125
        val entry = 0.04
        val entryFee = KalshiFee.total(contracts, entry)
        assertFalse(ScalpExit.shouldSell(entry, entry, contracts, entryFee))
        assertFalse(ScalpExit.shouldSell(entry, entry + 0.01, contracts, entryFee))
        assertTrue(ScalpExit.shouldSell(entry, 0.15, contracts, entryFee))
        assertFalse(ScalpExit.shouldSell(entry, null, contracts, entryFee))
    }

    @Test
    fun oneCentOfRiseOnOneContractIsNotWorthTheFee() {
        val entryFee = KalshiFee.total(1, 0.10)
        assertFalse(ScalpExit.shouldSell(0.10, 0.12, 1, entryFee))
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
