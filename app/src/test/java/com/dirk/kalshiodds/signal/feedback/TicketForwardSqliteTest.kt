package com.dirk.kalshiodds.signal.feedback

import android.database.sqlite.SQLiteDatabase
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.data.local.results.TicketForwardRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TicketForwardSqliteTest {
    private fun row() = TicketForwardRow(
        ticker = "KXBTC15M-TICKET", series = "KXBTC15M", capturedAtMs = 1L,
        buildCode = 1_000_034, kind = "HUNTER", modelSource = "on-device AI",
        side = "YES", modelYes = 0.15, marketYes = 0.035, ask = 0.04,
        visibleContracts = 40.0, contracts = 40, allInUsd = 1.71,
        feeUsd = 0.11, feeRate = 0.07, modeledNetUsd = 4.29
    )

    @Test fun firstTicketSurvivesRestartAndSettlementJoinsLater() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(SqliteResultsStore.DB_NAME)
        var store: SqliteResultsStore? = null
        var reopened: SqliteResultsStore? = null
        try {
            val first = row()
            val firstStore = SqliteResultsStore(context)
            store = firstStore
            firstStore.insertTicketForward(listOf(first, first.copy(modelYes = 0.99)))
            val reopenedStore = SqliteResultsStore(context)
            reopened = reopenedStore
            reopenedStore.upsertSettled(listOf(SettledWindowRow(first.ticker, first.series, "no")))
            val saved = reopenedStore.ticketForward().single()
            assertEquals(0.15, saved.modelYes, 1e-9)
            assertEquals("no", saved.outcome)
            assertEquals(40, saved.contracts)
            assertEquals(1_000_034, saved.buildCode)
        } finally {
            reopened?.close()
            store?.close()
            context.deleteDatabase(SqliteResultsStore.DB_NAME)
        }
    }

    @Test fun existingVersionSixDatabaseGetsNewTableWithoutLosingForwardSignals() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(SqliteResultsStore.DB_NAME)
        var store: SqliteResultsStore? = null
        try {
            val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(SqliteResultsStore.DB_NAME), null)
            db.execSQL("CREATE TABLE IF NOT EXISTS settled_windows (ticker TEXT PRIMARY KEY, result TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS forward_test (ticker TEXT PRIMARY KEY, series TEXT, captured_at_ms INTEGER, model_yes REAL, market_yes REAL, side TEXT, book_ask REAL, size_at_ask REAL, contracts INTEGER, all_in_usd REAL, fee_usd REAL, quote_qualified INTEGER)")
            db.execSQL("INSERT OR REPLACE INTO forward_test VALUES ('KXBTC15M-OLD', 'KXBTC15M', 1, .7, .5, 'YES', .6, 20, 8, 4.9, .1, 1)")
            db.version = 6
            db.close()
            val activeStore = SqliteResultsStore(context)
            store = activeStore
            activeStore.insertTicketForward(listOf(row()))
            assertEquals("KXBTC15M-OLD", activeStore.forwardTests().first { it.ticker == "KXBTC15M-OLD" }.ticker)
            assertEquals(row().ticker, activeStore.ticketForward().first { it.ticker == row().ticker }.ticker)
        } finally {
            store?.close()
            context.deleteDatabase(SqliteResultsStore.DB_NAME)
        }
    }
}
