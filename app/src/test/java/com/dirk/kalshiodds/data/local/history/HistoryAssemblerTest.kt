package com.dirk.kalshiodds.data.local.history

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAssemblerTest {
    @Test
    fun filtersLivePaperCoinAndDate() {
        val tickets = listOf(
            TicketAttemptRow(
                id = 1,
                ticker = "KXBTC15M-A",
                side = "YES",
                stakeUsd = 12.5,
                approved = true,
                result = "submitted",
                createdAtMs = 1_000L,
                note = "win-target $50"
            )
        )
        val paper = PaperBookState(
            fills = listOf(
                PaperFill(
                    id = "p1",
                    ticker = "KXETH15M-B",
                    side = "NO",
                    stakeUsd = 5.0,
                    contracts = 10,
                    limitPrice = 0.50,
                    source = "AI hunter",
                    createdAtMs = 2_000L,
                    note = "t",
                    won = true,
                    settled = true,
                    pnlUsd = 5.0,
                    winTargetUsd = 50.0
                )
            )
        )
        val all = HistoryAssembler.bets(tickets, paper)
        assertEquals(2, all.size)
        val live = HistoryAssembler.bets(tickets, paper, source = HistoryAssembler.SourceFilter.LIVE)
        assertEquals(1, live.size)
        assertTrue(live.single().live)
        assertEquals(50.0, live.single().winTargetUsd!!, 1e-9)
        val eth = HistoryAssembler.bets(tickets, paper, coin = HistoryAssembler.CoinFilter.ETH)
        assertEquals("KXETH15M-B", eth.single().ticker)
        val old = HistoryAssembler.bets(tickets, paper, sinceMs = 1_500L)
        assertEquals(1, old.size)
    }

    @Test
    fun totalsAndCumulativePnl() {
        val bets = listOf(
            HistoryBet("a", 1_000L, "KXBTC15M-A", "YES", 5, 0.2, 1.0, "paper", "won", 4.0, false),
            HistoryBet("b", 2_000L, "KXBTC15M-B", "NO", 5, 0.2, 1.0, "paper", "lost", -1.0, false),
            HistoryBet("c", 3_000L, "KXBTC15M-C", "YES", 5, 0.2, 1.0, "paper", "open", null, false)
        )
        val t = HistoryAssembler.totals(bets)
        assertEquals(3, t.count)
        assertEquals(3.0, t.pnlUsd, 1e-9)
        assertEquals(1, t.wins)
        assertEquals(1, t.losses)
        assertEquals(1, t.open)
        val curve = HistoryAssembler.cumulativePnl(bets)
        assertEquals(listOf(1_000L to 4.0, 2_000L to 3.0, 3_000L to 3.0), curve)
    }

    @Test
    fun signalsJoinSettlement() {
        val snaps = listOf(
            ScoredSnapshotRow(
                ticker = "KXBTC15M-A",
                series = "KXBTC15M",
                side = "YES",
                edgePp = 6.0,
                fairPp = 70.0,
                marketPp = 64.0,
                regime = null,
                uncertainty = null,
                createdAtMs = 1L
            )
        )
        val settled = listOf(
            SettledWindowRow(ticker = "KXBTC15M-A", series = "KXBTC15M", result = "yes")
        )
        val lines = HistoryAssembler.signals(snaps, settled)
        assertEquals("yes", lines.single().settled)
        assertEquals(64.0, lines.single().marketPp, 1e-9)
        assertEquals(70.0, lines.single().fairPp, 1e-9)
    }
}
