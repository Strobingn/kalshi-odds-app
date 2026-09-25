package com.dirk.kalshiodds.data.local

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.chart.ChartTickSchema
import com.dirk.kalshiodds.data.local.history.ArchiveSchema
import com.dirk.kalshiodds.data.local.archive.ChartTickRow
import com.dirk.kalshiodds.data.local.history.HistorySession
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.6 (DB v3) → 0.3.7 (v4) → 0.3.9 (v5) upgrades: existing snapshots,
 * tickets, and settled windows survive. v4/v5 only CREATE TABLE / INDEX
 * — never DROP.
 */
class SqliteMigrationTest {

    @Test
    fun v036FixtureRowsSurviveHistoryUpgrade() {
        val fixture = InMemoryResultsStore()
        fixture.insertSnapshots(
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
        fixture.insertTicket(
            TicketAttemptRow(
                ticker = "KXBTC15M-OLD",
                side = "YES",
                stakeUsd = 5.0,
                approved = true,
                result = "submitted",
                createdAtMs = 1_700_000_000_100L
            )
        )
        fixture.upsertSettled(
            listOf(
                SettledWindowRow(
                    ticker = "KXBTC15M-OLD",
                    series = "KXBTC15M",
                    result = "yes",
                    closeMs = 1_700_000_900_000L
                )
            )
        )

        val sql = ArchiveSchema.upgradeSql(ArchiveSchema.V036)
        assertTrue(sql.isNotEmpty())
        assertTrue(sql.none { ArchiveSchema.isDestructive(it) })
        assertTrue(sql.any { it.contains(ArchiveSchema.SETTINGS_TABLE) })
        assertTrue(sql.any { it.contains(ArchiveSchema.SESSION_TABLE) })

        fixture.insertSettingsChange(
            SettingsChange(
                createdAtMs = 1_700_000_000_200L,
                key = "win_target_usd",
                oldValue = "50",
                newValue = "75",
                snapshotJson = """{"winTargetUsd":75.0}"""
            )
        )
        fixture.insertSession(
            HistorySession(id = "sess-1", startedAtMs = 1_700_000_000_000L)
        )

        assertEquals("KXBTC15M-OLD", fixture.recentSnapshots(10).first().ticker)
        assertEquals("KXBTC15M-OLD", fixture.recentTickets(10).first().ticker)
        assertTrue(fixture.settledTickers().contains("KXBTC15M-OLD"))
        assertEquals("yes", fixture.recentSettled("KXBTC15M", 5).first().result)
        assertEquals(1, fixture.stats().settledCount)
        assertEquals(1, fixture.recentSettingsChanges(10).size)
        assertEquals("sess-1", fixture.recentSessions(5).first().id)
    }

    @Test
    fun v5ChartTicksAreAdditiveAndBounded() {
        val sql = ChartTickSchema.upgradeSql(ChartTickSchema.FROM_VERSION)
        assertTrue(sql.isNotEmpty())
        assertTrue(sql.none { ChartTickSchema.isDestructive(it) })
        assertTrue(sql.any { it.contains(ChartTickSchema.TABLE) })
        assertTrue(ChartTickSchema.upgradeSql(ChartTickSchema.VERSION).isEmpty())

        val fixture = InMemoryResultsStore()
        fixture.insertSnapshots(
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
        fixture.insertChartTicks(
            listOf(
                ChartTickRow(
                    ticker = "KXBTC15M-OLD",
                    tMs = 1_700_000_000_000L,
                    yesBid = 0.56,
                    noBid = 0.43
                )
            )
        )
        assertEquals("KXBTC15M-OLD", fixture.recentSnapshots(10).first().ticker)
        assertEquals(1, fixture.chartTicks("KXBTC15M-OLD", 0L, Long.MAX_VALUE, 10).size)
    }

    @Test
    fun upgradeFromCurrentIsNoopAndNeverDrops() {
        assertTrue(ArchiveSchema.upgradeSql(ArchiveSchema.CURRENT).isEmpty())
        assertFalse(ArchiveSchema.isDestructive(ArchiveSchema.CREATE_SETTINGS))
        assertFalse(ArchiveSchema.isDestructive(ArchiveSchema.CREATE_SESSIONS))
        assertFalse(ChartTickSchema.isDestructive(ChartTickSchema.CREATE))
    }

    @Test
    fun upgradesNeverTouchCredentialColumns() {
        val sql = ArchiveSchema.upgradeSql(ArchiveSchema.V036) + ChartTickSchema.upgradeSql(4)
        assertTrue(sql.isNotEmpty())
        assertTrue(
            sql.none {
                it.contains("api_key", ignoreCase = true) ||
                    it.contains("private_key", ignoreCase = true) ||
                    it.contains("kalshi_signal_secrets", ignoreCase = true)
            }
        )
    }
}
