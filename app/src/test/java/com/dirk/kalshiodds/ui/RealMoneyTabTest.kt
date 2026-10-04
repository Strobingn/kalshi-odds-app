package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.d3.D3Snapshot
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealMoneyTabTest {
    @Test
    fun noKeyShowsAddKeyAndHidesALiveBalance() {
        val page = RealMoneyTab.of(
            liveKeySaved = false,
            demoEnvironment = false,
            cashUsd = null,
            paperOn = true,
            proposals = emptyList(),
            d3 = D3Snapshot.EMPTY
        )
        assertFalse(page.account.keySaved)
        assertFalse(page.account.keyValid)
        assertTrue(page.account.showAddKey)
        assertTrue(page.account.balanceLabel.contains("No Kalshi API key"))
        assertEquals(RealMoneyTab.ADD_KEY, RealMoneyTab.ADD_KEY)
        assertTrue(page.explainer.contains("Approve"))
        assertTrue(page.explainer.contains("REAL MONEY"))
        assertTrue(page.explainer.contains("$10"))
        assertTrue(page.switches.any { it.id == "demo" && !it.on })
        assertTrue(page.switches.any { it.id == "paper" && it.on })
    }

    @Test
    fun savedLiveKeyShowsBalance() {
        val page = RealMoneyTab.of(
            liveKeySaved = true,
            demoEnvironment = false,
            cashUsd = 42.5,
            paperOn = false,
            proposals = emptyList(),
            d3 = D3Snapshot.EMPTY
        )
        assertTrue(page.account.keySaved)
        assertTrue(page.account.keyValid)
        assertTrue(page.account.liveEnvironment)
        assertFalse(page.account.showAddKey)
        assertTrue(page.account.balanceLabel.contains("42.50"))
        assertTrue(page.account.environmentLabel.contains("Live"))
        val demo = RealMoneyTab.of(
            liveKeySaved = true,
            demoEnvironment = true,
            cashUsd = 42.5,
            paperOn = false,
            proposals = emptyList(),
            d3 = D3Snapshot.EMPTY
        )
        assertFalse(demo.account.keyValid)
        assertFalse(demo.account.liveEnvironment)
        assertTrue(demo.account.environmentLabel.contains("Demo"))
    }

    @Test
    fun pendingTicketsAreListedApartFromD3() {
        val manual = ticket("t-live", TicketKind.MANUAL)
        val d3 = ticket("t-d3", TicketKind.D3)
        val page = RealMoneyTab.of(
            liveKeySaved = true,
            demoEnvironment = false,
            cashUsd = 10.0,
            paperOn = false,
            proposals = listOf(manual, d3),
            d3 = D3Snapshot.EMPTY
        )
        assertEquals(listOf("t-live"), page.pending.map { it.id })
        assertEquals(listOf("t-d3"), page.d3Tickets.map { it.id })
        assertTrue(page.pending.single().line.contains("UP"))
        assertTrue(page.d3Status.contains("D3") || page.d3Status.contains("5 PM"))
        assertTrue(page.realPnlLabel.contains("not paper"))
    }

    private fun ticket(id: String, kind: TicketKind) = TradeTicket(
        id = id,
        ticker = "KXBTC15M-RM",
        side = "YES",
        bookSide = "bid",
        stakeUsd = 4.0,
        limitPrice = 0.40,
        yesLimitPrice = 0.40,
        contracts = 10,
        estimatedFillUsd = 4.0,
        maxPayoutUsd = 10.0,
        estimatedAvgFill = 0.40,
        sizingNote = "test",
        kind = kind
    )
}
