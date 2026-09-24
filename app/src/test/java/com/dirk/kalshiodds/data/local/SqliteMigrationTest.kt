package com.dirk.kalshiodds.data.local

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory stand-in for the 0.3.6 → 0.3.7 archive upgrade: existing
 * snapshots and settled windows must survive new History / bid columns.
 * Device SQLite onUpgrade (v2→v3→v4) only adds tables/columns
 * (settings_history / sessions in v4). Never drops 0.3.x rows.
 */
class SqliteMigrationTest {
    @Test
    fun existingSnapshotsAndSettledSurviveNewApis() {
        val store = InMemoryResultsStore()
        store.insertSnapshots(
            listOf(
                ScoredSnapshotRow(
                    ticker = "KXBTC15M-OLD",
                    series = "KXBTC15M",
                    side = "YES",
                    edgePp = 4.0,
                    fairPp = 60.0,
                    marketPp = 56.0,
                    regime = null,
                    uncertainty = null,
                    createdAtMs = 1_700_000_000_000L
                )
            )
        )
        store.upsertSettled(
            listOf(
                SettledWindowRow(
                    ticker = "KXBTC15M-OLD",
                    series = "KXBTC15M",
                    result = "yes",
                    closeMs = 1_700_000_900_000L
                )
            )
        )
        assertEquals(1, store.recentSnapshots(10).size)
        assertTrue(store.settledTickers().contains("KXBTC15M-OLD"))
        assertEquals("yes", store.recentSettled("KXBTC15M", 5).first().result)
        assertEquals(1, store.stats().settledCount)
        store.insertSettingsChange(
            com.dirk.kalshiodds.data.local.history.SettingsChange(
                createdAtMs = 1_700_000_000_100L,
                key = "win_target_usd",
                oldValue = "50",
                newValue = "75",
                snapshotJson = """{"winTargetUsd":75.0}"""
            )
        )
        store.insertSession(
            com.dirk.kalshiodds.data.local.history.HistorySession(
                id = "sess-1",
                startedAtMs = 1_700_000_000_000L
            )
        )
        assertEquals(1, store.recentSettingsChanges(10).size)
        assertEquals("KXBTC15M-OLD", store.recentSnapshots(10).first().ticker)
        assertEquals("sess-1", store.recentSessions(5).first().id)
    }
}
