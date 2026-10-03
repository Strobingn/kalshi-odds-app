package com.dirk.kalshiodds.data.local.results

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PendingOrderSqliteTest {
    @Test
    fun pendingClientOrderIdSurvivesANewDatabaseOpen() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val first = SqliteResultsStore(ctx)
        first.save(
            PendingClientOrder(
                key = "KXBTC15M-T|NO|BUY",
                clientOrderId = "cid-persist",
                attempted = true,
                ticker = "KXBTC15M-T",
                side = "NO",
                kind = "BUY",
                updatedAtMs = 5L
            )
        )
        val second = SqliteResultsStore(ctx)
        val loaded = second.find("KXBTC15M-T|NO|BUY")
        assertEquals("cid-persist", loaded?.clientOrderId)
        assertEquals(true, loaded?.attempted)
        second.clear("KXBTC15M-T|NO|BUY")
        assertNull(SqliteResultsStore(ctx).find("KXBTC15M-T|NO|BUY"))
    }
}
