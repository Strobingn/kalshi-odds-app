package com.dirk.kalshiodds.signal.feedback

import android.database.sqlite.SQLiteDatabase
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.results.ForwardTestRow
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ForwardTestSqliteTest {
    @Test fun firstPriceIsImmutableAndSettlementJoinsAfterRestart() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(SqliteResultsStore.DB_NAME)
        try {
            val first = ForwardTestRow(
                ticker = "KXBTC15M-FWD", series = "KXBTC15M", capturedAtMs = 1L,
                modelYes = 0.75, marketYes = 0.54, side = "YES", bookAsk = 0.56,
                sizeAtAsk = 20.0, contracts = 8, allInUsd = 4.57,
                feeUsd = 0.09, quoteQualified = true
            )
            SqliteResultsStore(context).insertForwardTests(listOf(first, first.copy(modelYes = 0.1)))
            val reopened = SqliteResultsStore(context)
            reopened.upsertSettled(listOf(SettledWindowRow("KXBTC15M-FWD", "KXBTC15M", "yes")))
            val row = reopened.forwardTests().single()
            assertEquals(0.75, row.modelYes, 1e-9)
            assertEquals("yes", row.outcome)
            assertEquals(4.57, row.allInUsd!!, 1e-9)
        } finally {
            context.deleteDatabase(SqliteResultsStore.DB_NAME)
        }
    }

    @Test fun malformedVersionElevenForwardTableIsRepairedWithoutLosingRows() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(SqliteResultsStore.DB_NAME)
        var store: SqliteResultsStore? = null
        try {
            val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(SqliteResultsStore.DB_NAME), null)
            // Robolectric can retain a connection from the preceding test
            // despite deleteDatabase, so make this v11 fixture explicit.
            db.execSQL("DROP INDEX IF EXISTS idx_forward_time")
            db.execSQL("DROP TABLE IF EXISTS forward_test")
            db.execSQL(
                "CREATE TABLE forward_test (entry_key TEXT PRIMARY KEY, ticker TEXT NOT NULL, series TEXT NOT NULL, " +
                    "captured_at_ms INTEGER NOT NULL, model_yes REAL NOT NULL, market_yes REAL NOT NULL, " +
                    "side TEXT NOT NULL, book_ask REAL, size_at_ask REAL, contracts INTEGER, all_in_usd REAL, " +
                    "fee_usd REAL, quote_qualified INTEGER NOT NULL, settlement_yes REAL, " +
                    "final_minute_samples INTEGER, final_minute_average_usd REAL, " +
                    "required_remaining_average_usd REAL, expected_net_per_contract_usd REAL)"
            )
            db.execSQL(
                "INSERT INTO forward_test VALUES ('KXBTC15M-REPAIR|SCALP', 'KXBTC15M-REPAIR', 'KXBTC15M', " +
                    "1, .75, .54, 'YES', .56, 20, 8, 4.57, .09, 1, null, null, null, null, null)"
            )
            db.version = 11
            db.close()

            store = SqliteResultsStore(context)
            val repaired = store.forwardTests().single()
            assertEquals("KXBTC15M-REPAIR", repaired.ticker)
            assertEquals(0.75, repaired.modelYes, 1e-9)
        } finally {
            store?.close()
            context.deleteDatabase(SqliteResultsStore.DB_NAME)
        }
    }
}
