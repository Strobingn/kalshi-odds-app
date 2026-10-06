package com.dirk.kalshiodds.prediction.ledger

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Permanent prediction ledger. The prediction log keeps only the last 400
 * windows (about 4 days); this keeps every settled call (up to [MAX_ROWS],
 * about 2 years of BTC/ETH/SOL windows) in its own SQLite file so the
 * calibration report can be checked over weeks.
 */
class LedgerStore(context: Context) {
    private val db = Helper(context.applicationContext)

    /** Insert or replace by ticker (one row per window). */
    fun upsert(rows: List<LedgerRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) w.insertWithOnConflict(TABLE, null, values(r), SQLiteDatabase.CONFLICT_REPLACE)
            w.execSQL(
                "DELETE FROM $TABLE WHERE ticker NOT IN " +
                    "(SELECT ticker FROM $TABLE ORDER BY settled_at DESC LIMIT $MAX_ROWS)"
            )
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun all(limit: Int = MAX_ROWS): List<LedgerRow> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM $TABLE ORDER BY settled_at ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val out = ArrayList<LedgerRow>(c.count)
            while (c.moveToNext()) out.add(row(c))
            out
        }

    fun count(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun values(r: LedgerRow) = ContentValues().apply {
        put("ticker", r.ticker)
        put("series", r.series)
        put("called_at", r.calledAtMs)
        r.closeTimeMs?.let { put("close_time", it) } ?: putNull("close_time")
        put("model_yes", r.modelYes)
        put("market_mid", r.marketMid)
        r.entryAsk?.let { put("entry_ask", it) } ?: putNull("entry_ask")
        put("picked_side", r.pickedSide)
        r.edgePp?.let { put("edge_pp", it) } ?: putNull("edge_pp")
        r.uncertainty?.let { put("uncertainty", it) } ?: putNull("uncertainty")
        put("regime", r.regime)
        put("tte_bucket", r.tteBucket)
        r.wouldBet?.let { put("would_bet", if (it) 1 else 0) } ?: putNull("would_bet")
        put("model_version", r.modelVersion)
        put("outcome", r.outcome)
        put("settled_at", r.settledAtMs)
    }

    private fun row(c: Cursor): LedgerRow {
        fun s(n: String): String? = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
        fun d(n: String): Double? = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getDouble(it) }
        fun l(n: String): Long? = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
        return LedgerRow(
            ticker = s("ticker").orEmpty(),
            series = s("series").orEmpty(),
            calledAtMs = l("called_at") ?: 0L,
            closeTimeMs = l("close_time"),
            modelYes = d("model_yes") ?: 0.5,
            marketMid = d("market_mid") ?: 0.5,
            entryAsk = d("entry_ask"),
            pickedSide = s("picked_side"),
            edgePp = d("edge_pp"),
            uncertainty = d("uncertainty"),
            regime = s("regime"),
            tteBucket = s("tte_bucket"),
            wouldBet = l("would_bet")?.let { it != 0L },
            modelVersion = s("model_version").orEmpty(),
            outcome = s("outcome").orEmpty(),
            settledAtMs = l("settled_at") ?: 0L
        )
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    ticker TEXT PRIMARY KEY,
                    series TEXT NOT NULL,
                    called_at INTEGER NOT NULL,
                    close_time INTEGER,
                    model_yes REAL NOT NULL,
                    market_mid REAL NOT NULL,
                    entry_ask REAL,
                    picked_side TEXT,
                    edge_pp REAL,
                    uncertainty REAL,
                    regime TEXT,
                    tte_bucket TEXT,
                    would_bet INTEGER,
                    model_version TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    settled_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ledger_settled ON $TABLE(settled_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onCreate(db)
        }
    }

    companion object {
        const val DB_NAME = "claude_prediction_ledger.db"
        const val DB_VERSION = 1
        const val TABLE = "ledger"
        const val MAX_ROWS = 200_000
    }
}
