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
        try {
            val first = row()
            SqliteResultsStore(context).insertTicketForward(listOf(first, first.copy(modelYes = 0.99)))
            val reopened = SqliteResultsStore(context)
            reopened.upsertSettled(listOf(SettledWindowRow(first.ticker, first.series, "no")))
            val saved = reopened.ticketForward().single()
            assertEquals(0.15, saved.modelYes, 1e-9)
            assertEquals("no", saved.outcome)
            assertEquals(40, saved.contracts)
            assertEquals(1_000_034, saved.buildCode)
        } finally { context.deleteDatabase(SqliteResultsStore.DB_NAME) }
    }

    @Test fun existingVersionSixDatabaseGetsNewTableWithoutLosingForwardSignals() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(SqliteResultsStore.DB_NAME)
        try {
            val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(SqliteResultsStore.DB_NAME), null)
            db.execSQL("CREATE TABLE settled_windows (ticker TEXT PRIMARY KEY, result TEXT)")
            db.execSQL("CREATE TABLE forward_test (ticker TEXT PRIMARY KEY, series TEXT, captured_at_ms INTEGER, model_yes REAL, market_yes REAL, side TEXT, book_ask REAL, size_at_ask REAL, contracts INTEGER, all_in_usd REAL, fee_usd REAL, quote_qualified INTEGER)")
            db.execSQL("INSERT INTO forward_test VALUES ('KXBTC15M-OLD', 'KXBTC15M', 1, .7, .5, 'YES', .6, 20, 8, 4.9, .1, 1)")
            db.version = 6
            db.close()
            val store = SqliteResultsStore(context)
            store.insertTicketForward(listOf(row()))
            assertEquals("KXBTC15M-OLD", store.forwardTests().single().ticker)
            assertEquals(row().ticker, store.ticketForward().single().ticker)
        } finally { context.deleteDatabase(SqliteResultsStore.DB_NAME) }
    }
}
