package com.dirk.kalshiodds.signal.archive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import com.dirk.kalshiodds.signal.paper.PaperBookState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The permanent bet archive on the phone: every paper bet the phone's own
 * rules and the paper book took, with its settlement, in its own SQLite file
 * (`bet_archive.db`). Rows are only ever added or updated, never deleted:
 * a tracker's trim or reset leaves them here. Writes run on one background
 * thread. Paper only.
 */
class BetArchiveStore private constructor(context: Context) {
    private val db = Helper(context.applicationContext)
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "bet-archive").apply { isDaemon = true } }
    private val diffs = HashMap<String, BetArchiveDiff>()

    private fun diff(source: String): BetArchiveDiff = synchronized(diffs) {
        diffs.getOrPut(source) { BetArchiveDiff { rows -> upsertNow(rows) } }
    }

    /** A tracker saved its state: write its new and changed bets. */
    fun tracker(source: String, state: LateFavoriteState) {
        io.execute { runCatching { diff(source).offer(BetRows.ofTracker(source, state)) } }
    }

    /** The paper book saved its state: write its new and changed fills, reset runs included. */
    fun paper(state: PaperBookState) {
        io.execute { runCatching { diff(BetRows.PAPER_BOOK).offer(BetRows.ofPaper(state)) } }
    }

    private fun upsertNow(rows: List<BetRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) w.insertWithOnConflict(TABLE, null, values(r), SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Every archived bet, oldest first. Call off the main thread. */
    fun all(): List<BetRow> =
        db.readableDatabase.rawQuery("SELECT * FROM $TABLE ORDER BY placed_at ASC", null).use { c ->
            val out = ArrayList<BetRow>(c.count)
            while (c.moveToNext()) out += row(c)
            out
        }

    fun count(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun values(r: BetRow) = ContentValues().apply {
        put("id", r.id)
        put("source", r.source)
        put("ticker", r.ticker)
        put("side", r.side)
        put("price", r.price)
        put("contracts", r.contracts)
        put("cost_usd", r.costUsd)
        put("fee_usd", r.feeUsd)
        put("placed_at", r.placedAtMs)
        if (r.secondsLeft != null) put("seconds_left", r.secondsLeft) else putNull("seconds_left")
        put("result", r.result)
        if (r.won != null) put("won", if (r.won) 1 else 0) else putNull("won")
        if (r.pnlUsd != null) put("pnl_usd", r.pnlUsd) else putNull("pnl_usd")
        if (r.settledAtMs != null) put("settled_at", r.settledAtMs) else putNull("settled_at")
        put("note", r.note)
    }

    private fun row(c: Cursor): BetRow {
        fun i(n: String) = c.getColumnIndexOrThrow(n)
        fun longOrNull(n: String) = if (c.isNull(i(n))) null else c.getLong(i(n))
        return BetRow(
            id = c.getString(i("id")),
            source = c.getString(i("source")),
            ticker = c.getString(i("ticker")),
            side = c.getString(i("side")),
            price = c.getDouble(i("price")),
            contracts = c.getInt(i("contracts")),
            costUsd = c.getDouble(i("cost_usd")),
            feeUsd = c.getDouble(i("fee_usd")),
            placedAtMs = c.getLong(i("placed_at")),
            secondsLeft = longOrNull("seconds_left"),
            result = if (c.isNull(i("result"))) null else c.getString(i("result")),
            won = if (c.isNull(i("won"))) null else c.getInt(i("won")) == 1,
            pnlUsd = if (c.isNull(i("pnl_usd"))) null else c.getDouble(i("pnl_usd")),
            settledAtMs = longOrNull("settled_at"),
            note = c.getString(i("note")) ?: ""
        )
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (id TEXT PRIMARY KEY, source TEXT NOT NULL, ticker TEXT NOT NULL, side TEXT NOT NULL, " +
                    "price REAL NOT NULL, contracts INTEGER NOT NULL, cost_usd REAL NOT NULL, fee_usd REAL NOT NULL, " +
                    "placed_at INTEGER NOT NULL, seconds_left INTEGER, result TEXT, won INTEGER, pnl_usd REAL, " +
                    "settled_at INTEGER, note TEXT)"
            )
            db.execSQL("CREATE INDEX bets_placed ON $TABLE (placed_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val DB_NAME = "bet_archive.db"
        private const val TABLE = "bets"

        @Volatile
        private var instance: BetArchiveStore? = null

        fun of(context: Context): BetArchiveStore =
            instance ?: synchronized(this) { instance ?: BetArchiveStore(context).also { instance = it } }
    }
}
