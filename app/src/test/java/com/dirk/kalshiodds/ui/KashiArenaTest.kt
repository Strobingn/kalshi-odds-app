package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.decision.*
import org.junit.Assert.*
import org.junit.Test

class KashiArenaTest {
    private val v2 = ScalpModels.Model.V2_SF41
    private val original = ScalpModels.Model.SF41
    @Test fun completedWindowsStayInTheirOwnGroupAndStartAtZero() {
        val windows = listOf(
            ScalpModels.WindowRow("a", 1_000_000, mapOf(v2 to 3.0, original to 100.0), emptyMap(), null),
            ScalpModels.WindowRow("b", 1_900_000, mapOf(v2 to -1.0), emptyMap(), null),
            ScalpModels.WindowRow("live", 2_800_000, mapOf(v2 to 99.0), emptyMap(), null))
        assertEquals(listOf(0.0, 3.0, 2.0), KashiArenaData.curve(windows, 2_000_000, setOf(v2)).map { it.net })
        assertEquals(listOf(0.0, 100.0), KashiArenaData.curve(windows, 2_000_000, setOf(original)).map { it.net })
    }
    @Test fun noTradesStillShowsEveryStrategyAndUntradedDoesNotBeatALoss() {
        val row = ScalpModels.rowFromStat(ScalpStat(v2.code)).copy(roundTrips = 1, netUsd = -3.0, winRate = 0.0)
        val rows = KashiArenaData.rows(ScalpTabState(algoAllTime = mapOf(v2 to row)), true)
        assertEquals(ScalpModels.ALL.count { ScalpModels.isV2(it) }, rows.size)
        assertEquals(v2, rows.first().model)
        assertTrue(rows.all { ScalpModels.isV2(it.model) })
    }
    @Test fun windowReadExcludesCounterfactualsAndOtherTickers() {
        val store = InMemoryScalpPersistence()
        val trade = ScalpTrade("p", "KXBTC15M-26OCT122015-15", "YES", ScalpState.PENDING_ENTRY, 1000, .4, .5, ruleVersion = "v|v2-sf41|P")
        assertTrue(trade.isPrimary)
        store.upsertScalp(trade)
        store.upsertScalp(trade.copy(id = "shadow", ruleVersion = "v|v2-sf41|S"))
        store.upsertScalp(trade.copy(id = "other", ticker = "OTHER"))
        assertEquals(listOf("p"), store.windowPrimaryTrades(trade.ticker.lowercase()).map { it.id })
    }
}
