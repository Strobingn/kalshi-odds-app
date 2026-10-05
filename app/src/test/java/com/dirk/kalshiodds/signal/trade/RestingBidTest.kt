package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestingBidTest {

    private fun ticket(side: String = "YES", stake: Double = 5.0, kind: TicketKind = TicketKind.MANUAL) = TradeTicket(
        id = "t1",
        ticker = "KXBTC15M-26OCT041430-30",
        side = side,
        bookSide = if (side == "YES") "bid" else "ask",
        stakeUsd = stake,
        limitPrice = 0.53,
        yesLimitPrice = if (side == "YES") 0.53 else 0.47,
        contracts = 9,
        estimatedFillUsd = 4.77,
        maxPayoutUsd = 9.0,
        estimatedAvgFill = 0.53,
        sizingNote = "taker",
        kind = kind,
        feeUsd = 0.16,
        allInUsd = 4.93
    )

    @Test
    fun priceIsOneCentAboveTheBidAndBelowTheAsk() {
        assertEquals(0.51, RestingBid.priceFor(0.50, 0.53)!!, 1e-9)
        assertEquals(0.52, RestingBid.priceFor(0.51, 0.54)!!, 1e-9)
    }

    @Test
    fun noRoomWhenTheSpreadIsOneCentOrCrossed() {
        assertNull(RestingBid.priceFor(0.50, 0.51))
        assertNull(RestingBid.priceFor(0.50, 0.50))
        assertNull(RestingBid.priceFor(null, 0.51))
        assertNull(RestingBid.priceFor(0.50, null))
    }

    @Test
    fun refusesPricesOutsideFiveToNinetyFiveCents() {
        assertNull(RestingBid.priceFor(0.02, 0.10))
        assertNull(RestingBid.priceFor(0.95, 0.99))
    }

    @Test
    fun yesTicketBecomesAPostOnlyBidWithNoFee() {
        val r = RestingBid.build(ticket(), 0.50, 0.53).getOrThrow()
        assertEquals(0.51, r.limitPrice, 1e-9)
        assertEquals(0.51, r.yesLimitPrice, 1e-9)
        assertEquals("bid", r.bookSide)
        assertEquals(9, r.contracts) // floor(5 / 0.51)
        assertEquals(9 * 0.51, r.allInUsd!!, 1e-9)
        assertEquals(0.0, r.feeUsd!!, 0.0)
        assertEquals(9.0, r.maxPayoutUsd, 0.0)
        assertTrue(r.postOnly)
        assertEquals(RestingBid.CANCEL_AFTER_MS, r.restingCancelAfterMs)
        assertEquals(RestingBid.NOTE, r.gateNote)
        assertTrue(r.sizingNote.contains("no fee"))
    }

    @Test
    fun noTicketSendsTheYesLegAtOneMinusThePrice() {
        val r = RestingBid.build(ticket(side = "NO"), 0.46, 0.49).getOrThrow()
        assertEquals(0.47, r.limitPrice, 1e-9)
        assertEquals(0.53, r.yesLimitPrice, 1e-9)
        assertEquals("ask", r.bookSide)
        assertEquals(10, r.contracts) // floor(5 / 0.47)
    }

    @Test
    fun cheaperThanBuyingNow() {
        val now = ticket()
        val r = RestingBid.build(now, 0.50, 0.53).getOrThrow()
        assertTrue(r.allInUsd!! / r.contracts < now.allInUsd!! / now.contracts)
    }

    @Test
    fun failsWithAReasonInsteadOfCrossing() {
        assertEquals(RestingBid.NO_ROOM, RestingBid.build(ticket(), 0.50, 0.51).exceptionOrNull()?.message)
        assertEquals(RestingBid.NO_QUOTE, RestingBid.build(ticket(), null, 0.51).exceptionOrNull()?.message)
        assertTrue(RestingBid.build(ticket(stake = 0.30), 0.50, 0.53).isFailure)
    }

    @Test
    fun sellsPaperAndReduceOnlyTicketsCannotRest() {
        assertTrue(RestingBid.build(ticket(kind = TicketKind.SELL), 0.50, 0.53).isFailure)
        assertTrue(RestingBid.build(ticket().copy(paperOnly = true), 0.50, 0.53).isFailure)
        assertTrue(RestingBid.build(ticket().copy(reduceOnly = true), 0.50, 0.53).isFailure)
        assertFalse(ticket().postOnly)
    }
}
