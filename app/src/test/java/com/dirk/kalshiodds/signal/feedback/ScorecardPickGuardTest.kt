package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardPickGuardTest {

    @Test
    fun settlementMarketsAndMissingEntriesAreFake() {
        assertTrue(ScorecardPickGuard.isSettlementMarketUnit(0.0))
        assertTrue(ScorecardPickGuard.isSettlementMarketUnit(1.0))
        assertTrue(ScorecardPickGuard.isSettlementMarketUnit(100.0))
        assertFalse(ScorecardPickGuard.isSettlementMarketUnit(0.41))
        assertTrue(ScorecardPickGuard.isSettlementMarketPct(0.0))
        assertTrue(ScorecardPickGuard.isSettlementMarketPct(100.0))
        assertFalse(ScorecardPickGuard.isSettlementMarketPct(1.0))
        assertTrue(ScorecardPickGuard.isFakeSettled(null, 10.0, won = true, marketUnit = 0.4, marketPct = null))
        assertTrue(ScorecardPickGuard.isFakeSettled(0.001, 10.0, won = true, marketUnit = 0.4, marketPct = null))
        assertTrue(ScorecardPickGuard.isFakeSettled(0.40, 0.0, won = true, marketUnit = 0.4, marketPct = null))
        assertTrue(ScorecardPickGuard.isFakeSettled(0.40, null, won = true, marketUnit = 0.4, marketPct = null))
        assertFalse(ScorecardPickGuard.isFakeSettled(0.40, null, won = false, marketUnit = 0.4, marketPct = null))
        assertTrue(ScorecardPickGuard.isFakeSettled(0.40, 4.0, won = true, marketUnit = 1.0, marketPct = null))
        assertTrue(ScorecardPickGuard.isFakeSettled(0.40, 4.0, won = false, marketUnit = 0.4, marketPct = 0.0))
        assertFalse(ScorecardPickGuard.isFakeSettled(0.40, 4.0, won = true, marketUnit = 0.4, marketPct = 40.0))
    }

    @Test
    fun everySourceDropsNoEntryZeroStakeWinsAndSettlementMarkets() {
        val sources = listOf("ticket", "AI hunter", "AI signal", "autopilot", "manual paper", "long-shot finder")
        val fills = sources.flatMap { source ->
            listOf(
                fill("no-entry-$source", source, limit = 0.0, stake = 0.0, won = true, market = null),
                fill("zero-win-$source", source, limit = 0.40, stake = 0.0, won = true, market = 40.0),
                fill("mkt-$source", source, limit = 0.40, stake = 4.0, won = true, market = 100.0),
                fill("real-$source", source, limit = 0.40, stake = 4.0, won = false, market = 40.0)
            )
        }
        val snap = ScorecardLedger.of(emptyList(), fills)
        assertEquals(sources.size, snap.combined.settledCount)
        assertEquals(0, snap.combined.wins)
        assertEquals(sources.size, snap.combined.losses)
        assertTrue(snap.picks.all { it.stakeUsd!! > 0.0 && it.marketPct == 40.0 })
        assertEquals(snap.combined.money.pnlUsd, snap.bySource.sumOf { it.pnlUsd }, 1e-9)
    }

    @Test
    fun paperBookPurgeReturnsStakeAndDropsSettlementFillsWithoutRemoteDelete() {
        val fakeWin = fill("fake", "ticket", limit = 0.0, stake = 0.0, won = true, market = 100.0).copy(pnlUsd = 0.0)
        val real = fill("real", "ticket", limit = 0.40, stake = 4.0, won = true, market = 40.0).copy(pnlUsd = 6.0)
        val book = PaperBook(
            initial = PaperBookState(
                startingUsd = 1_000.0,
                cashUsd = 1_006.0,
                fills = listOf(fakeWin, real),
                lifetimeRealizedPnlUsd = 6.0
            ),
            idFactory = { "new" },
            nowMs = { 1L }
        )
        assertEquals(1, book.purgeFakeFills())
        val left = book.snapshot()
        assertEquals(listOf("real"), left.fills.map { it.id })
        assertEquals(1_006.0, left.cashUsd, 1e-9)
        assertEquals(6.0, left.lifetimeRealizedPnlUsd!!, 1e-9)
        book.upsertFromSync(fill("remote", "ticket", limit = 1.0, stake = 0.0, won = true, market = 100.0))
        assertTrue(book.snapshot().fills.none { it.id == "remote" })
        assertEquals(0, book.purgeFakeFills())
    }

    @Test
    fun newFillRefusesSettlementMarket() {
        val book = PaperBook(
            initial = PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0),
            idFactory = { "p" },
            nowMs = { 1L }
        )
        val direct = book.considerTicket(
            TradeTicket(
                id = "t",
                ticker = "KXBTC15M-LATE",
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
                kind = TicketKind.HUNTER,
                modelChance = 0.70,
                impliedChance = 1.0
            ),
            enabled = true,
            depthContracts = 100_000
        )
        assertNull(direct)
        assertTrue(book.snapshot().lastMessage!!.contains("settled"))
        assertTrue(book.snapshot().fills.none { it.ticker == "KXBTC15M-LATE" })
    }

    private fun fill(
        id: String,
        source: String,
        limit: Double,
        stake: Double,
        won: Boolean,
        market: Double?
    ) = PaperFill(
        id = id,
        ticker = "KXBTC15M-$id",
        side = "YES",
        stakeUsd = stake,
        contracts = if (limit > 0.0 && stake > 0.0) 10 else 0,
        limitPrice = limit,
        source = source,
        createdAtMs = 1L,
        settled = true,
        won = won,
        pnlUsd = if (won) 1.0 else -stake,
        note = source,
        marketPct = market,
        pickSource = source,
        aiPct = 60.0
    )
}
