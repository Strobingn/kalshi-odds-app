package com.dirk.kalshiodds.signal.feedback

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
            val store = SqliteResultsStore(context)
            try {
                store.insertForwardTests(listOf(first, first.copy(modelYes = 0.1)))
            } finally {
                store.close()
            }
            val reopened = SqliteResultsStore(context)
            try {
                reopened.upsertSettled(listOf(SettledWindowRow("KXBTC15M-FWD", "KXBTC15M", "yes")))
                val row = reopened.forwardTests().single()
                assertEquals(0.75, row.modelYes, 1e-9)
                assertEquals("yes", row.outcome)
                assertEquals(4.57, row.allInUsd!!, 1e-9)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(SqliteResultsStore.DB_NAME)
        }
    }
}
