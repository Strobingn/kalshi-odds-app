package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.history.HistorySession
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionRecoveryTest {
    @Test fun interruptedSessionIsRepairedWithoutChangingCurrentOrAlreadyClosedSessions() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        store.insertSession(HistorySession("old", 100, markets = 2, signals = 3, bets = 4, pnlUsd = 1.25))
        store.insertSession(HistorySession("closed", 200, endedAtMs = 250))
        store.insertSession(HistorySession("current", 1000))
        assertEquals(1, store.repairInterruptedSessions("current", 1000))
        val rows = store.recentSessions(10).associateBy { it.id }
        assertEquals(1000L, rows.getValue("old").endedAtMs)
        assertEquals(4, rows.getValue("old").bets)
        assertEquals(1.25, rows.getValue("old").pnlUsd!!, 0.0)
        assertEquals(250L, rows.getValue("closed").endedAtMs)
        assertNull(rows.getValue("current").endedAtMs)
        assertEquals(0, store.repairInterruptedSessions("current", 2000))
    }
}
