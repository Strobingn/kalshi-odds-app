package com.dirk.kalshiodds.signal.scalp

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import java.util.TimeZone

/**
 * SQLite-backed scalper ledger, following the SqliteResultsStore /
 * AsyncResultsWriter pattern (hand-rolled SQLite, no Room). Two tables:
 *
 * - `scalp_positions` — one row per position, updated on exit
 * - `scalp_trades`    — append-only, one row per entry/exit with fee + pnl
 *
 * Scalp volume is tiny (≤ a few trades/hour by guardrail), so writes go
 * straight to SQLite on the caller's thread inside a transaction — no
 * batching queue is warranted. Reads are served from a small in-memory
 * cache ([openPositions], [recentTrades]) that is updated before every
 * write returns, so the engine's read path is synchronous and never
 * touches the disk. All failures are swallowed (runCatching) — a dead
 * ledger must never crash the tick path.
 */
class ScalpPositionStore(context: Context) : ScalpLedger {

    private val db = Helper(context.applicationContext)

    // ---- synchronous in-memory cache --------------------------------------

    private val cacheLock = Any()
    private var cachedOpen: List<ScalpPosition> = emptyList()
    private var cachedTrades: List<ScalpTradeRow> = emptyList()

    /** Synchronous read of the open position, if any. */
    override fun openPosition(): ScalpPosition? = synchronized(cacheLock) { cachedOpen.firstOrNull() }

    override fun openPositions(): List<ScalpPosition> = synchronized(cacheLock) { cachedOpen.toList() }

    /** Newest-first flat ledger rows (bounded cache). */
    fun recentTrades(limit: Int = 200): List<ScalpTradeRow> =
        synchronized(cacheLock) { cachedTrades.take(limit) }

    /**
     * Completed entries in `[nowMs − hourMs, nowMs]` — the trades-per-hour
     * guardrail window. Counts ENTER rows.
     */
    override fun entriesSince(nowMs: Long, windowMs: Long): Int =
        synchronized(cacheLock) {
            cachedTrades.count { it.action == ACTION_ENTER && it.createdAtMs >= nowMs - windowMs }
        }

    /** Realized P&L in cents of EXIT rows whose day (device TZ) matches [nowMs]. */
    override fun realizedPnlCentsToday(nowMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = nowMs }
        val dayStart = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return synchronized(cacheLock) {
            cachedTrades
                .filter { it.action == ACTION_EXIT && it.createdAtMs >= dayStart }
                .sumOf { (it.pnlCents ?: 0).toLong() }
        }
    }

    // ---- writes ------------------------------------------------------------

    /** Persist a fresh open position and refresh the cache. Returns the row. */
    override fun recordEnter(position: ScalpPosition, entryFeeCents: Int, clientOrderId: String?): ScalpPosition {
        val stored = position.copy(entryFeeCents = entryFeeCents, clientOrderId = clientOrderId)
        runCatching {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                w.insertWithOnConflict(TABLE_POSITIONS, null, positionValues(stored), SQLiteDatabase.CONFLICT_REPLACE)
                w.insert(TABLE_TRADES, null, tradeValues(stored.id, stored, ACTION_ENTER, null, null, entryFeeCents))
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        synchronized(cacheLock) {
            cachedOpen = cachedOpen + stored
            cachedTrades = (listOf(
                ScalpTradeRow(
                    id = -1L,
                    positionId = stored.id,
                    ticker = stored.ticker,
                    action = ACTION_ENTER,
                    priceCents = stored.entryPriceCents,
                    contracts = stored.contracts,
                    feeCents = entryFeeCents,
                    pnlCents = null,
                    reason = null,
                    mode = stored.mode.name,
                    clientOrderId = clientOrderId,
                    createdAtMs = stored.entryTimeMs
                )
            ) + cachedTrades).take(MAX_CACHE)
        }
        return stored
    }

    /** Persist an exit (price, reason, pnl) and mark the position closed. */
    override fun recordExit(
        positionId: String,
        exitPriceCents: Int,
        exitTimeMs: Long,
        reason: ExitReason,
        pnlCents: Int,
        exitFeeCents: Int
    ) {
        runCatching {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("status", ScalpPositionStatus.CLOSED.name)
                    put("exit_price_cents", exitPriceCents)
                    put("exit_time_ms", exitTimeMs)
                    put("exit_reason", reason.name)
                    put("pnl_cents", pnlCents)
                    put("exit_fee_cents", exitFeeCents)
                }
                w.update(TABLE_POSITIONS, cv, "id = ?", arrayOf(positionId))
                val pos = synchronized(cacheLock) { cachedOpen.firstOrNull { it.id == positionId } }
                if (pos != null) {
                    w.insert(
                        TABLE_TRADES,
                        null,
                        tradeValues(positionId, pos, ACTION_EXIT, reason, pnlCents, exitFeeCents)
                            .apply { put("price_cents", exitPriceCents) }
                            .apply { put("created_at_ms", exitTimeMs) }
                    )
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        synchronized(cacheLock) {
            val pos = cachedOpen.firstOrNull { it.id == positionId }
            cachedOpen = cachedOpen.filterNot { it.id == positionId }
            if (pos != null) {
                cachedTrades = (listOf(
                    ScalpTradeRow(
                        id = -1L,
                        positionId = positionId,
                        ticker = pos.ticker,
                        action = ACTION_EXIT,
                        priceCents = exitPriceCents,
                        contracts = pos.contracts,
                        feeCents = exitFeeCents,
                        pnlCents = pnlCents,
                        reason = reason.name,
                        mode = pos.mode.name,
                        clientOrderId = pos.clientOrderId,
                        createdAtMs = exitTimeMs
                    )
                ) + cachedTrades).take(MAX_CACHE)
            }
        }
    }

    /** Attach live-order ids to an open position after a successful place. */
    override fun attachOrderIds(positionId: String, clientOrderId: String, orderId: String?) {
        runCatching {
            val cv = ContentValues().apply {
                put("client_order_id", clientOrderId)
                orderId?.let { put("order_id", it) }
            }
            db.writableDatabase.update(TABLE_POSITIONS, cv, "id = ?", arrayOf(positionId))
        }
        synchronized(cacheLock) {
            cachedOpen = cachedOpen.map {
                if (it.id == positionId) it.copy(clientOrderId = clientOrderId, orderId = orderId) else it
            }
        }
    }

    override fun hydrateFromDisk() {
        runCatching {
            val open = queryPositions("status = ?", arrayOf(ScalpPositionStatus.OPEN.name))
            val trades = queryTrades(MAX_CACHE)
            synchronized(cacheLock) {
                cachedOpen = open
                cachedTrades = trades
            }
        }
    }

    // ---- sqlite ------------------------------------------------------------

    private fun positionValues(p: ScalpPosition) = ContentValues().apply {
        put("id", p.id)
        put("ticker", p.ticker)
        put("side", p.side)
        put("entry_price_cents", p.entryPriceCents)
        put("contracts", p.contracts)
        put("entry_time_ms", p.entryTimeMs)
        put("mode", p.mode.name)
        put("status", p.status.name)
        put("exit_price_cents", p.exitPriceCents)
        put("exit_time_ms", p.exitTimeMs)
        put("exit_reason", p.exitReason?.name)
        put("pnl_cents", p.pnlCents)
        put("client_order_id", p.clientOrderId)
        put("order_id", p.orderId)
        put("entry_fee_cents", p.entryFeeCents)
        put("exit_fee_cents", p.exitFeeCents)
    }

    private fun tradeValues(
        positionId: String,
        pos: ScalpPosition,
        action: String,
        reason: ExitReason?,
        pnlCents: Int?,
        feeCents: Int
    ) = ContentValues().apply {
        put("position_id", positionId)
        put("ticker", pos.ticker)
        put("action", action)
        put("price_cents", pos.entryPriceCents)
        put("contracts", pos.contracts)
        put("fee_cents", feeCents)
        put("pnl_cents", pnlCents)
        put("reason", reason?.name)
        put("mode", pos.mode.name)
        put("client_order_id", pos.clientOrderId)
        put("created_at_ms", pos.entryTimeMs)
    }

    private fun queryPositions(where: String?, args: Array<String>?): List<ScalpPosition> {
        val out = ArrayList<ScalpPosition>()
        db.readableDatabase.query(TABLE_POSITIONS, null, where, args, null, null, "entry_time_ms DESC")
            .use { c -> while (c.moveToNext()) out.add(cursorToPosition(c)) }
        return out
    }

    private fun queryTrades(limit: Int): List<ScalpTradeRow> {
        val out = ArrayList<ScalpTradeRow>()
        db.readableDatabase.query(
            TABLE_TRADES, null, null, null, null, null, "created_at_ms DESC", limit.toString()
        ).use { c -> while (c.moveToNext()) out.add(cursorToTrade(c)) }
        return out
    }

    private fun cursorToPosition(c: Cursor) = ScalpPosition(
        id = c.str("id"),
        ticker = c.str("ticker"),
        side = c.str("side"),
        entryPriceCents = c.long("entry_price_cents").toInt(),
        contracts = c.long("contracts").toInt(),
        entryTimeMs = c.long("entry_time_ms"),
        mode = if (c.str("mode") == ScalpMode.LIVE.name) ScalpMode.LIVE else ScalpMode.PAPER,
        status = if (c.str("status") == ScalpPositionStatus.CLOSED.name) {
            ScalpPositionStatus.CLOSED
        } else {
            ScalpPositionStatus.OPEN
        },
        exitPriceCents = c.longOrNull("exit_price_cents")?.toInt(),
        exitTimeMs = c.longOrNull("exit_time_ms"),
        exitReason = c.strOrNull("exit_reason")?.let {
            runCatching { ExitReason.valueOf(it) }.getOrNull()
        },
        pnlCents = c.longOrNull("pnl_cents")?.toInt(),
        clientOrderId = c.strOrNull("client_order_id"),
        orderId = c.strOrNull("order_id"),
        entryFeeCents = c.longOrNull("entry_fee_cents")?.toInt() ?: 0,
        exitFeeCents = c.longOrNull("exit_fee_cents")?.toInt() ?: 0
    )

    private fun cursorToTrade(c: Cursor) = ScalpTradeRow(
        id = c.long("id"),
        positionId = c.str("position_id"),
        ticker = c.str("ticker"),
        action = c.str("action"),
        priceCents = c.long("price_cents").toInt(),
        contracts = c.long("contracts").toInt(),
        feeCents = c.longOrNull("fee_cents")?.toInt() ?: 0,
        pnlCents = c.longOrNull("pnl_cents")?.toInt(),
        reason = c.strOrNull("reason"),
        mode = c.str("mode"),
        clientOrderId = c.strOrNull("client_order_id"),
        createdAtMs = c.long("created_at_ms")
    )

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun Cursor.strOrNull(col: String): String? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long = getColumnIndexOrThrow(col).let { if (isNull(it)) 0L else getLong(it) }
    private fun Cursor.longOrNull(col: String): Long? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            createTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 → v2 placeholder: additive-only migrations, never DROP.
            createTables(db)
        }

        private fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_POSITIONS (
                  id TEXT PRIMARY KEY,
                  ticker TEXT NOT NULL,
                  side TEXT,
                  entry_price_cents INTEGER NOT NULL,
                  contracts INTEGER NOT NULL,
                  entry_time_ms INTEGER NOT NULL,
                  mode TEXT,
                  status TEXT,
                  exit_price_cents INTEGER,
                  exit_time_ms INTEGER,
                  exit_reason TEXT,
                  pnl_cents INTEGER,
                  client_order_id TEXT,
                  order_id TEXT,
                  entry_fee_cents INTEGER,
                  exit_fee_cents INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_TRADES (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  position_id TEXT NOT NULL,
                  ticker TEXT NOT NULL,
                  action TEXT NOT NULL,
                  price_cents INTEGER NOT NULL,
                  contracts INTEGER NOT NULL,
                  fee_cents INTEGER,
                  pnl_cents INTEGER,
                  reason TEXT,
                  mode TEXT,
                  client_order_id TEXT,
                  created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_scalp_trades_time ON $TABLE_TRADES(created_at_ms)")
        }
    }

    companion object {
        const val DB_NAME = "diphunter_scalp.db"
        const val DB_VERSION = 1
        const val TABLE_POSITIONS = "scalp_positions"
        const val TABLE_TRADES = "scalp_trades"
        const val ACTION_ENTER = "ENTER"
        const val ACTION_EXIT = "EXIT"
        private const val MAX_CACHE = 500
    }
}
