package com.dirk.kalshiodds.signal.scalp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * One completed scalp round trip (entry + exit or settlement).
 * All money fields are USD; pnlUsd is net of Kalshi fees on both legs.
 */
@Serializable
data class ScalpTrade(
    val id: String,
    val ticker: String,
    val series: String,
    val side: String,
    val entryPrice: Double,
    val entryTimeMs: Long,
    val contracts: Int,
    val costUsd: Double,
    val exitPrice: Double? = null,
    val exitTimeMs: Long? = null,
    val proceedsUsd: Double? = null,
    val feeUsd: Double? = null,
    val pnlUsd: Double? = null,
    val mode: String = "PAPER",
    val outcome: String? = null
)

data class ScalpStats(
    val trades: Int = 0,
    val wins: Int = 0,
    val pnlUsd: Double = 0.0,
    val winRate: Double? = null,
    val avgPnlUsd: Double? = null,
    val bestUsd: Double? = null,
    val worstUsd: Double? = null
)

/**
 * Append-only scalp trade log, mirrored to SQLite so the Scalp tab survives
 * process death. One row per completed round trip plus the open position.
 */
class ScalpTradeLog(context: Context) {
    private val helper = Helper(context.applicationContext)
    private val _trades = MutableStateFlow<List<ScalpTrade>>(emptyList())
    val trades: StateFlow<List<ScalpTrade>> = _trades

    fun load(limit: Int = 200) {
        _trades.value = queryRecent(limit)
    }

    fun recordEntry(trade: ScalpTrade) {
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT OR REPLACE INTO scalp_trades (id, ticker, series, side, entry_price, entry_time_ms, contracts, cost_usd, mode) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf(trade.id, trade.ticker, trade.series, trade.side, trade.entryPrice, trade.entryTimeMs, trade.contracts, trade.costUsd, trade.mode)
            )
        }
        refresh()
    }

    fun recordExit(
        id: String,
        exitPrice: Double,
        exitTimeMs: Long,
        proceedsUsd: Double,
        feeUsd: Double,
        pnlUsd: Double,
        outcome: String
    ) {
        helper.writableDatabase.use { db ->
            db.execSQL(
                "UPDATE scalp_trades SET exit_price=?, exit_time_ms=?, proceeds_usd=?, fee_usd=?, pnl_usd=?, outcome=? WHERE id=?",
                arrayOf(exitPrice, exitTimeMs, proceedsUsd, feeUsd, pnlUsd, outcome, id)
            )
        }
        refresh()
    }

    fun openTrade(ticker: String): ScalpTrade? =
        _trades.value.firstOrNull { it.ticker.equals(ticker, true) && it.exitTimeMs == null }

    fun stats(sinceMs: Long = 0L): ScalpStats {
        val closed = _trades.value.filter { it.pnlUsd != null && (it.exitTimeMs ?: 0L) >= sinceMs }
        if (closed.isEmpty()) return ScalpStats()
        val pnls = closed.map { it.pnlUsd!! }
        val wins = pnls.count { it > 0.0 }
        return ScalpStats(
            trades = closed.size,
            wins = wins,
            pnlUsd = pnls.sum(),
            winRate = wins.toDouble() / closed.size,
            avgPnlUsd = pnls.average(),
            bestUsd = pnls.max(),
            worstUsd = pnls.min()
        )
    }

    private fun refresh(limit: Int = 200) {
        _trades.value = queryRecent(limit)
    }

    private fun queryRecent(limit: Int): List<ScalpTrade> {
        val out = ArrayList<ScalpTrade>()
        helper.readableDatabase.use { db ->
            db.rawQuery(
                "SELECT id, ticker, series, side, entry_price, entry_time_ms, contracts, cost_usd, exit_price, exit_time_ms, proceeds_usd, fee_usd, pnl_usd, mode, outcome FROM scalp_trades ORDER BY entry_time_ms DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ScalpTrade(
                            id = c.getString(0),
                            ticker = c.getString(1),
                            series = c.getString(2),
                            side = c.getString(3),
                            entryPrice = c.getDouble(4),
                            entryTimeMs = c.getLong(5),
                            contracts = c.getInt(6),
                            costUsd = c.getDouble(7),
                            exitPrice = c.getDouble(8).takeIf { !c.isNull(8) },
                            exitTimeMs = c.getLong(9).takeIf { !c.isNull(9) },
                            proceedsUsd = c.getDouble(10).takeIf { !c.isNull(10) },
                            feeUsd = c.getDouble(11).takeIf { !c.isNull(11) },
                            pnlUsd = c.getDouble(12).takeIf { !c.isNull(12) },
                            mode = c.getString(13),
                            outcome = c.getString(14)
                        )
                    )
                }
            }
        }
        return out
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "scalp_trades.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE scalp_trades (
                    id TEXT PRIMARY KEY,
                    ticker TEXT NOT NULL,
                    series TEXT,
                    side TEXT NOT NULL,
                    entry_price REAL NOT NULL,
                    entry_time_ms INTEGER NOT NULL,
                    contracts INTEGER NOT NULL,
                    cost_usd REAL NOT NULL,
                    exit_price REAL,
                    exit_time_ms INTEGER,
                    proceeds_usd REAL,
                    fee_usd REAL,
                    pnl_usd REAL,
                    mode TEXT NOT NULL,
                    outcome TEXT
                )"""
            )
            db.execSQL("CREATE INDEX idx_scalp_entry_time ON scalp_trades(entry_time_ms)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
