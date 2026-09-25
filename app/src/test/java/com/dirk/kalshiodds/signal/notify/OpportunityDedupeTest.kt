package com.dirk.kalshiodds.signal.notify

import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunityDedupeTest {

    @Test
    fun firstCardNotifies() {
        val ticket = longShot("KXBTC15M-A")
        assertTrue(OpportunityDedupe.isOpportunity(ticket))
        assertTrue(OpportunityDedupe.shouldNotify(ticket, nowMs = 1_000L, lastPostedMs = emptyMap()))
    }

    @Test
    fun sameMarketWithinWindowIsDeduped() {
        val ticket = longShot("KXBTC15M-A")
        val last = mapOf(OpportunityDedupe.keyOf(ticket) to 1_000L)
        assertFalse(
            OpportunityDedupe.shouldNotify(
                ticket,
                nowMs = 1_000L + 5 * 60_000L,
                lastPostedMs = last,
                minIntervalMs = 15 * 60_000L
            )
        )
        assertTrue(
            OpportunityDedupe.shouldNotify(
                ticket,
                nowMs = 1_000L + 16 * 60_000L,
                lastPostedMs = last,
                minIntervalMs = 15 * 60_000L
            )
        )
    }

    @Test
    fun otherMarketIsIndependent() {
        val a = longShot("KXBTC15M-A")
        val b = longShot("KXETH15M-B")
        val last = mapOf(OpportunityDedupe.keyOf(a) to 1_000L)
        assertTrue(OpportunityDedupe.shouldNotify(b, nowMs = 1_100L, lastPostedMs = last))
    }

    @Test
    fun blockedOrSellNeverNotify() {
        val blocked = longShot("KXBTC15M-X").copy(blockedReason = "No sellers", contracts = 0)
        assertFalse(OpportunityDedupe.isOpportunity(blocked))
        val sell = longShot("KXBTC15M-S").copy(kind = TicketKind.SELL)
        assertFalse(OpportunityDedupe.isOpportunity(sell))
    }

    private fun longShot(ticker: String) = TradeTicket(
        id = "id-$ticker",
        ticker = ticker,
        side = "YES",
        bookSide = "bid",
        stakeUsd = 8.0,
        limitPrice = 0.20,
        yesLimitPrice = 0.20,
        contracts = 40,
        estimatedFillUsd = 8.0,
        maxPayoutUsd = 40.0,
        estimatedAvgFill = 0.20,
        sizingNote = "long-shot",
        kind = TicketKind.HUNTER_VALUE,
        winTargetUsd = 50.0
    )
}
