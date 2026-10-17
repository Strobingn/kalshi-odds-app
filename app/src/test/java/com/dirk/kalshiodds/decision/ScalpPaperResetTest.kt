package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

/** 0.3.86 "Reset scalp paper money": fresh stats, old trades archived (tagged, not deleted), open trades carry over. */
class ScalpPaperResetTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val vid = ForecastScalp.variantId(true, 600)
    private fun closed(id: String, net: Double) = ScalpTrade(id, ticker, "YES", ScalpState.CLOSED, 1_000L, .40, .5, contracts = 10,
        entryPrice = .40, entryAtMs = 2_000L, closedAtMs = 3_000L, soldContracts = 10, proceedsUsd = 4.0 + net, netUsd = net,
        exitReason = "horizon reached", ruleVersion = "v|$vid|P")

    @Test fun resetArchivesHistoryAndStartsStatsFresh() {
        val store = InMemoryScalpPersistence()
        store.upsertScalpAndStats(closed("a", -1.0).copy(state = ScalpState.OPEN, closedAtMs = null, netUsd = null, soldContracts = 0, proceedsUsd = 0.0), null)
        store.upsertScalpAndStats(closed("a", -1.0), null)
        val open = closed("b", 0.0).copy(state = ScalpState.OPEN, closedAtMs = null, netUsd = null, soldContracts = 0, proceedsUsd = 0.0)
        store.upsertScalpAndStats(open, null)
        val book = ScalpBook(store, variants = emptyList(), liveExtras = true)
        assertTrue(book.stats.value.any { it.n > 0 })
        val runId = book.resetPaperRun(10_000L)
        assertTrue(book.stats.value.all { it.n == 0 && it.netUsd == 0.0 })
        assertEquals(1, book.stats.value.sumOf { it.openN }) // the open trade still counts in the new run
        val rows = store.loadScalps().associateBy { it.id }
        assertEquals(2, rows.size) // nothing deleted
        assertTrue(rows.getValue("a").note.contains("prevRun=$runId"))
        assertFalse(rows.getValue("b").note.contains("prevRun="))
        assertEquals(listOf(runId), store.archivedRuns())
        assertTrue(store.archivedStats(runId).any { it.n == 1 })
    }
}
