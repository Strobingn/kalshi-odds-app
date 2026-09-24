package com.dirk.kalshiodds.data.local.results

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Room-style SQLite schema (no annotation processor) so results survive
 * a process kill. Writes are serialized by SQLite; callers should use
 * [AsyncResultsWriter] so scoring never blocks.
 */
class SqliteResultsStore(context: Context) : ResultsStore {
    private val db = Helper(context.applicationContext)

    override fun insertSnapshots(rows: List<ScoredSnapshotRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insert(TABLE_SNAP, null, snapValues(r))
            }
            prune(w, TABLE_SNAP, MAX_SNAP)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun insertAlert(row: AlertRow) {
        val w = db.writableDatabase
        w.insert(TABLE_ALERT, null, alertValues(row))
        prune(w, TABLE_ALERT, MAX_ALERT)
    }

    override fun insertScorecard(row: ScorecardRow) {
        val w = db.writableDatabase
        w.insert(TABLE_CARD, null, cardValues(row))
        prune(w, TABLE_CARD, MAX_CARD)
    }

    override fun insertTicket(row: TicketAttemptRow) {
        val w = db.writableDatabase
        w.insert(TABLE_TICKET, null, ticketValues(row))
        prune(w, TABLE_TICKET, MAX_TICKET)
    }

    override fun recentSnapshots(limit: Int): List<ScoredSnapshotRow> =
        query(TABLE_SNAP, limit) { cursorToSnap(it) }

    override fun recentAlerts(limit: Int): List<AlertRow> =
        query(TABLE_ALERT, limit) { cursorToAlert(it) }

    override fun recentScorecards(limit: Int): List<ScorecardRow> =
        query(TABLE_CARD, limit) { cursorToCard(it) }

    override fun recentTickets(limit: Int): List<TicketAttemptRow> =
        query(TABLE_TICKET, limit) { cursorToTicket(it) }

    override fun exportBundle(limit: Int): ResultsBundle = ResultsBundle(
        snapshots = recentSnapshots(limit),
        alerts = recentAlerts(limit),
        scorecards = recentScorecards(limit),
        tickets = recentTickets(limit)
    )

    private fun <T> query(table: String, limit: Int, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(limit)
        db.readableDatabase.query(
            table,
            null,
            null,
            null,
            null,
            null,
            "created_at_ms DESC",
            limit.coerceIn(1, 2_000).toString()
        ).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    private fun prune(w: SQLiteDatabase, table: String, keep: Int) {
        w.execSQL(
            "DELETE FROM $table WHERE id NOT IN (SELECT id FROM $table ORDER BY id DESC LIMIT $keep)"
        )
    }

    private fun snapValues(r: ScoredSnapshotRow) = ContentValues().apply {
        put("ticker", r.ticker)
        put("series", r.series)
        put("side", r.side)
        put("edge_pp", r.edgePp)
        put("fair_pp", r.fairPp)
        put("market_pp", r.marketPp)
        put("regime", r.regime)
        put("uncertainty", r.uncertainty)
        put("confidence", r.confidence)
        put("tte", r.tte)
        put("heavy_ml", if (r.heavyMl) 1 else 0)
        put("note", r.note)
        put("created_at_ms", r.createdAtMs)
    }

    private fun alertValues(r: AlertRow) = ContentValues().apply {
        put("alert_id", r.alertId)
        put("ticker", r.ticker)
        put("series", r.series)
        put("side", r.side)
        put("edge_pp", r.edgePp)
        put("fair_pp", r.fairPp)
        put("market_pp", r.marketPp)
        put("reason", r.reason)
        put("regime", r.regime)
        put("created_at_ms", r.createdAtMs)
    }

    private fun cardValues(r: ScorecardRow) = ContentValues().apply {
        put("ticker", r.ticker)
        put("series", r.series)
        put("outcome", r.outcome)
        put("score", r.score)
        put("brier", r.brier)
        put("edge_pp", r.edgePp)
        put("policy_roi", r.policyRoi)
        put("note", r.note)
        put("created_at_ms", r.createdAtMs)
    }

    private fun ticketValues(r: TicketAttemptRow) = ContentValues().apply {
        put("ticker", r.ticker)
        put("side", r.side)
        put("stake_usd", r.stakeUsd)
        put("approved", if (r.approved) 1 else 0)
        put("result", r.result)
        put("client_order_id", r.clientOrderId)
        put("note", r.note)
        put("created_at_ms", r.createdAtMs)
    }

    private fun cursorToSnap(c: Cursor) = ScoredSnapshotRow(
        id = c.long("id"),
        ticker = c.str("ticker"),
        series = c.str("series"),
        side = c.str("side"),
        edgePp = c.dbl("edge_pp"),
        fairPp = c.dbl("fair_pp"),
        marketPp = c.dbl("market_pp"),
        regime = c.strOrNull("regime"),
        uncertainty = c.dblOrNull("uncertainty"),
        createdAtMs = c.long("created_at_ms"),
        confidence = c.dblOrNull("confidence"),
        tte = c.strOrNull("tte"),
        heavyMl = c.long("heavy_ml") != 0L,
        note = c.strOrNull("note")
    )

    private fun cursorToAlert(c: Cursor) = AlertRow(
        id = c.long("id"),
        alertId = c.str("alert_id"),
        ticker = c.str("ticker"),
        series = c.str("series"),
        side = c.str("side"),
        edgePp = c.dbl("edge_pp"),
        fairPp = c.dbl("fair_pp"),
        marketPp = c.dbl("market_pp"),
        reason = c.str("reason"),
        regime = c.strOrNull("regime"),
        createdAtMs = c.long("created_at_ms")
    )

    private fun cursorToCard(c: Cursor) = ScorecardRow(
        id = c.long("id"),
        ticker = c.str("ticker"),
        series = c.str("series"),
        outcome = c.str("outcome"),
        score = c.intOrNull("score"),
        brier = c.dblOrNull("brier"),
        edgePp = c.dblOrNull("edge_pp"),
        policyRoi = c.dblOrNull("policy_roi"),
        createdAtMs = c.long("created_at_ms"),
        note = c.strOrNull("note")
    )

    private fun cursorToTicket(c: Cursor) = TicketAttemptRow(
        id = c.long("id"),
        ticker = c.str("ticker"),
        side = c.str("side"),
        stakeUsd = c.dbl("stake_usd"),
        approved = c.long("approved") != 0L,
        result = c.str("result"),
        createdAtMs = c.long("created_at_ms"),
        clientOrderId = c.strOrNull("client_order_id"),
        note = c.strOrNull("note")
    )

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_SNAP (
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
            db.execSQL(
                """
                CREATE TABLE $TABLE_ALERT (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  alert_id TEXT,
                  ticker TEXT,
                  series TEXT,
                  side TEXT,
                  edge_pp REAL,
                  fair_pp REAL,
                  market_pp REAL,
                  reason TEXT,
                  regime TEXT,
                  created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE $TABLE_CARD (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ticker TEXT,
                  series TEXT,
                  outcome TEXT,
                  score INTEGER,
                  brier REAL,
                  edge_pp REAL,
                  policy_roi REAL,
                  note TEXT,
                  created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE $TABLE_TICKET (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ticker TEXT,
                  side TEXT,
                  stake_usd REAL,
                  approved INTEGER,
                  result TEXT,
                  client_order_id TEXT,
                  note TEXT,
                  created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_snap_created ON $TABLE_SNAP(created_at_ms)")
            db.execSQL("CREATE INDEX idx_alert_created ON $TABLE_ALERT(created_at_ms)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 is the first schema.
        }
    }

    companion object {
        const val DB_NAME = "diphunter_results.db"
        const val DB_VERSION = 1
        const val TABLE_SNAP = "scored_snapshots"
        const val TABLE_ALERT = "alerts_fired"
        const val TABLE_CARD = "scorecard_rows"
        const val TABLE_TICKET = "ticket_attempts"
        const val MAX_SNAP = 1_200
        const val MAX_ALERT = 400
        const val MAX_CARD = 600
        const val MAX_TICKET = 300
    }
}

private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)).orEmpty()
private fun Cursor.strOrNull(col: String): String? {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return null
    return getString(i)
}
private fun Cursor.long(col: String): Long {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return 0L
    return getLong(i)
}
private fun Cursor.dbl(col: String): Double {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return 0.0
    return getDouble(i)
}
private fun Cursor.dblOrNull(col: String): Double? {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return null
    return getDouble(i)
}
private fun Cursor.intOrNull(col: String): Int? {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return null
    return getInt(i)
}
