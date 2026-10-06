package com.dirk.kalshiodds.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema
import com.dirk.kalshiodds.data.local.paper.PaperFillSchema
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PredictionLedgerMigrationTest {

    @Test
    fun upgradeSqlIsAdditive() {
        val sql = PredictionLedgerSchema.upgradeSql(6)
        assertTrue(sql.isNotEmpty())
        assertTrue(sql.none { PredictionLedgerSchema.isDestructive(it) })
        assertTrue(sql.none { it.contains("api_key", ignoreCase = true) || it.contains("private_key", ignoreCase = true) })
        assertTrue(PredictionLedgerSchema.upgradeSql(7).isEmpty())
    }

    @Test
    fun v6PaperRowAndSnapshotSurviveLedgerUpgrade() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val file = ctx.getDatabasePath(SqliteResultsStore.DB_NAME)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL(PaperFillSchema.CREATE)
        old.execSQL(
            """
            CREATE TABLE scored_snapshots (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ticker TEXT NOT NULL,
              series TEXT,
              side TEXT,
              edge_pp REAL,
              fair_pp REAL,
              market_pp REAL,
              regime TEXT,
              uncertainty REAL,
              confidence REAL,
              tte TEXT,
              heavy_ml INTEGER,
              note TEXT,
              created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        old.insert("paper_fills", null, ContentValues().apply {
            put("fill_id", "keep-fill")
            put("ticker", "KXBTC15M-OLD")
            put("side", "YES")
            put("stake_usd", 5.0)
            put("contracts", 10)
            put("limit_price", 0.4)
            put("source", "test")
            put("created_at_ms", 10L)
            put("settled", 0)
        })
        old.insert("scored_snapshots", null, ContentValues().apply {
            put("ticker", "KXBTC15M-OLD")
            put("series", "KXBTC15M")
            put("side", "YES")
            put("edge_pp", 1.0)
            put("fair_pp", 60.0)
            put("market_pp", 55.0)
            put("created_at_ms", 10L)
        })
        old.version = 6
        old.close()

        val store = SqliteResultsStore(ctx)
        assertEquals("KXBTC15M-OLD", store.recentSnapshots(5).first().ticker)
        val id = store.insertLedger(
            LedgerRow(
                timestampMs = 20L,
                modelVersion = "test",
                ticker = "KXBTC15M-OLD",
                series = "KXBTC15M",
                settlementRule = "cf-60s-average",
                settlementSource = "cfbenchmarks_value",
                secondsRemaining = 40.0,
                spot = 100.0,
                targetStrike = 99.0,
                zDistance = 0.2,
                rawModelProb = 0.6,
                calibratedProb = null,
                marketMid = 0.55,
                bid = 0.54,
                ask = 0.56,
                spread = 0.02,
                depthAtBest = 20.0,
                imbalance = 0.1,
                bookAgeMs = 100L,
                decision = "ABSTAIN",
                sizeContracts = 0.0,
                reasonCodes = "NO BET — calibrated probability unavailable",
                cfIndexId = "BRTI",
                cfValue = 100.0
            )
        )
        assertTrue(id > 0)
        store.settleLedger("KXBTC15M-OLD", "yes", 30L)
        val row = store.recentLedger(5).first()
        assertEquals("yes", row.settlementResult)
        assertEquals("cfbenchmarks_value", row.settlementSource)
        assertNull(row.calibratedProb)
        val csv = store.ledgerCsv()
        assertTrue(csv.startsWith("timestamp_ms,"))
        assertTrue(csv.contains("KXBTC15M-OLD"))
        assertFalse(csv.contains("BEGIN PRIVATE KEY"))
        val again = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(7, again.version)
        val paper = again.rawQuery("SELECT fill_id FROM paper_fills", null)
        assertTrue(paper.moveToFirst())
        assertEquals("keep-fill", paper.getString(0))
        paper.close()
        again.close()
    }
}
