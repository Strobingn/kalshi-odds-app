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
class SqliteResultsStore(context: Context) : ResultsDatabase, com.dirk.kalshiodds.decision.LedgerSink, com.dirk.kalshiodds.decision.ScalpPersistence, com.dirk.kalshiodds.signal.paper.PaperOrderPersistence {
    private val db = Helper(context.applicationContext)

    /** Single background writer for retention deletes. Never runs on a UI caller. */
    private val backgroundWriter by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "results-db-writer").apply { isDaemon = true }
        }
    }
    private val chartCapAtMs = java.util.concurrent.atomic.AtomicLong(0L)

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

    override fun insertOddsMids(rows: List<OddsMidRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insert(TABLE_ODDS, null, oddsValues(r))
            }
            prune(w, TABLE_ODDS, MAX_ODDS)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun recentSnapshots(limit: Int): List<ScoredSnapshotRow> =
        query(TABLE_SNAP, limit) { cursorToSnap(it) }

    /**
     * Oldest row still inside the newest [lookback] snapshots, for a forward page scan.
     * Null when the table is smaller than [lookback] (start at the beginning).
     */
    fun snapshotSyncFloor(lookback: Int): Pair<Long, Long>? {
        val offset = lookback.coerceAtLeast(1) - 1
        db.readableDatabase.rawQuery(
            "SELECT created_at_ms, id FROM $TABLE_SNAP ORDER BY created_at_ms DESC, id DESC LIMIT 1 OFFSET $offset",
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getLong(0) to c.getLong(1)
        }
    }

    /** Keyset page, oldest-first, strictly after ([afterCreatedAtMs], [afterId]). */
    fun snapshotPageAfter(afterCreatedAtMs: Long, afterId: Long, limit: Int): List<ScoredSnapshotRow> {
        val cap = limit.coerceIn(1, SNAPSHOT_PAGE)
        val out = ArrayList<ScoredSnapshotRow>(cap)
        db.readableDatabase.query(
            TABLE_SNAP,
            null,
            "created_at_ms > ? OR (created_at_ms = ? AND id > ?)",
            arrayOf(afterCreatedAtMs.toString(), afterCreatedAtMs.toString(), afterId.toString()),
            null,
            null,
            "created_at_ms ASC, id ASC",
            cap.toString()
        ).use { c ->
            while (c.moveToNext()) out.add(cursorToSnap(c))
        }
        return out
    }

    override fun recentAlerts(limit: Int): List<AlertRow> =
        query(TABLE_ALERT, limit) { cursorToAlert(it) }

    override fun recentScorecards(limit: Int): List<ScorecardRow> =
        query(TABLE_CARD, limit) { cursorToCard(it) }

    override fun recentTickets(limit: Int): List<TicketAttemptRow> =
        query(TABLE_TICKET, limit) { cursorToTicket(it) }

    override fun save(order: PendingClientOrder) {
        val values = ContentValues().apply {
            put("order_key", order.key)
            put("client_order_id", order.clientOrderId)
            put("attempted", if (order.attempted) 1 else 0)
            put("ticker", order.ticker)
            put("side", order.side)
            put("kind", order.kind)
            put("updated_at_ms", order.updatedAtMs)
        }
        db.writableDatabase.insertWithOnConflict(
            TABLE_PENDING,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun find(key: String): PendingClientOrder? {
        db.readableDatabase.query(
            TABLE_PENDING,
            null,
            "order_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return PendingClientOrder(
                key = c.str("order_key"),
                clientOrderId = c.str("client_order_id"),
                attempted = c.long("attempted") != 0L,
                ticker = c.strOrNull("ticker").orEmpty(),
                side = c.strOrNull("side").orEmpty(),
                kind = c.strOrNull("kind").orEmpty(),
                updatedAtMs = c.long("updated_at_ms")
            )
        }
    }

    override fun clear(key: String) {
        db.writableDatabase.delete(TABLE_PENDING, "order_key = ?", arrayOf(key))
    }

    override fun recentOddsMids(limit: Int): List<OddsMidRow> =
        query(TABLE_ODDS, limit) { cursorToOdds(it) }

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

    private fun oddsValues(r: OddsMidRow) = ContentValues().apply {
        put("ticker", r.ticker)
        put("mid01", r.mid01)
        put("created_at_ms", r.createdAtMs)
        put("yes_bid", r.yesBid)
        put("no_bid", r.noBid)
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

    private fun cursorToOdds(c: Cursor) = OddsMidRow(
        id = c.long("id"),
        ticker = c.str("ticker"),
        mid01 = c.dbl("mid01"),
        createdAtMs = c.long("created_at_ms"),
        yesBid = c.dblOrNull("yes_bid"),
        noBid = c.dblOrNull("no_bid")
    )

    override fun upsertSettled(rows: List<com.dirk.kalshiodds.data.local.archive.SettledWindowRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insertWithOnConflict(
                    TABLE_SETTLED,
                    null,
                    ContentValues().apply {
                        put("ticker", r.ticker)
                        put("series", r.series)
                        put("result", r.result)
                        put("strike", r.strikeUsd)
                        put("open_ms", r.openMs)
                        put("close_ms", r.closeMs)
                        put("imported_at_ms", r.importedAtMs)
                        put("source", r.source)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun insertPricePath(rows: List<com.dirk.kalshiodds.data.local.archive.PricePathRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insertWithOnConflict(
                    TABLE_PATH,
                    null,
                    ContentValues().apply {
                        put("ticker", r.ticker)
                        put("t_ms", r.tMs)
                        put("yes_bid", r.yesBid)
                        put("no_bid", r.noBid)
                        put("mid", r.mid)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            w.execSQL(
                "DELETE FROM $TABLE_PATH WHERE id NOT IN (SELECT id FROM $TABLE_PATH ORDER BY id DESC LIMIT $MAX_PATH)"
            )
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun insertSpotCandles(rows: List<com.dirk.kalshiodds.data.local.archive.SpotCandleRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insertWithOnConflict(
                    TABLE_SPOT,
                    null,
                    ContentValues().apply {
                        put("product", r.product)
                        put("t_ms", r.tMs)
                        put("open", r.open)
                        put("high", r.high)
                        put("low", r.low)
                        put("close", r.close)
                        put("volume", r.volume)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            w.execSQL(
                "DELETE FROM $TABLE_SPOT WHERE id NOT IN (SELECT id FROM $TABLE_SPOT ORDER BY id DESC LIMIT $MAX_SPOT)"
            )
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun insertFills(rows: List<com.dirk.kalshiodds.data.importing.ImportedFill>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insertWithOnConflict(
                    TABLE_FILL,
                    null,
                    ContentValues().apply {
                        put("fill_id", r.id)
                        put("ticker", r.ticker)
                        put("side", r.side)
                        put("action", r.action)
                        put("count", r.count)
                        put("price", r.price)
                        put("created_at_ms", r.createdAtMs)
                        put("source", r.source)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /**
     * Every settled paper fill ever written (no 80-row truncation): id to scorecard row.
     * Paged by rowid so a large history never loads one giant cursor window.
     */
    fun settledPaperRows(): List<Pair<String, com.dirk.kalshiodds.decision.HonestScorecard.Row>> {
        val out = ArrayList<Pair<String, com.dirk.kalshiodds.decision.HonestScorecard.Row>>()
        val table = com.dirk.kalshiodds.data.local.paper.PaperFillSchema.TABLE
        var last = -1L
        while (true) {
            var n = 0
            db.readableDatabase.rawQuery(
                "SELECT rowid, fill_id, ticker, limit_price, stake_usd, pnl_usd, outcome, won, market_pct FROM $table " +
                    "WHERE settled = 1 AND pnl_usd IS NOT NULL AND rowid > ? ORDER BY rowid LIMIT 2000",
                arrayOf(last.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    n += 1
                    last = c.getLong(0)
                    val outcome = if (c.isNull(6)) null else c.getString(6)
                    if (outcome.equals("void", true)) continue
                    val price = c.getDouble(3)
                    if (price <= 0.0) continue
                    val stake = c.getDouble(4)
                    val pnl = c.getDouble(5)
                    val wonFlag = if (c.isNull(7)) pnl > 1e-9 else c.getInt(7) == 1
                    val marketPct = if (c.isNull(8)) null else c.getDouble(8)
                    if (com.dirk.kalshiodds.signal.feedback.ScorecardPickGuard.isFakeSettled(
                            entryAsk = price,
                            stakeUsd = stake,
                            won = wonFlag,
                            marketUnit = null,
                            marketPct = marketPct
                        )
                    ) {
                        continue
                    }
                    out += c.getString(1) to com.dirk.kalshiodds.decision.HonestScorecard.Row(
                        ask = price,
                        pnlUsd = pnl,
                        stakeUsd = stake,
                        market = c.getString(2).uppercase()
                    )
                }
            }
            if (n < 2000) break
        }
        return out
    }

    override fun deleteFakePaperFills(): Int {
        val table = com.dirk.kalshiodds.data.local.paper.PaperFillSchema.TABLE
        return runCatching {
            db.writableDatabase.delete(
                table,
                """
                (limit_price IS NULL OR limit_price < ? OR limit_price > ?)
                OR (won = 1 AND (stake_usd IS NULL OR stake_usd <= ?))
                OR (market_pct IS NOT NULL AND (market_pct <= ? OR market_pct >= ?))
                """.trimIndent(),
                arrayOf("0.02", "0.98", "0.000000001", "0.000001", "99.999")
            )
        }.getOrDefault(0)
    }

    override fun upsertPaperFills(rows: List<com.dirk.kalshiodds.signal.paper.PaperFill>) {
        if (rows.isEmpty()) return
        val table = com.dirk.kalshiodds.data.local.paper.PaperFillSchema.TABLE
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insertWithOnConflict(
                    table,
                    null,
                    ContentValues().apply {
                        put("fill_id", r.id)
                        put("ticker", r.ticker)
                        put("side", r.side)
                        put("stake_usd", r.stakeUsd)
                        put("contracts", r.contracts)
                        put("limit_price", r.limitPrice)
                        put("source", r.source)
                        put("created_at_ms", r.createdAtMs)
                        put("settled", if (r.settled) 1 else 0)
                        put("outcome", r.outcome)
                        put("won", r.won?.let { if (it) 1 else 0 })
                        put("pnl_usd", r.pnlUsd)
                        put("note", r.note)
                        put("win_target_usd", r.winTargetUsd)
                        put("ai_pct", r.aiPct)
                        put("ai_confidence", r.aiConfidence)
                        put("market_pct", r.marketPct)
                        put("pick_source", r.pickSource)
                        put("kelly_f", r.kellyF)
                        put("kelly_fraction", r.kellyFraction)
                        put("bankroll_after_usd", r.bankrollAfterUsd)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun insertBidSnapshots(rows: List<OddsMidRow>) = insertOddsMids(rows)

    override fun insertChartTicks(rows: List<com.dirk.kalshiodds.data.local.archive.ChartTickRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insertWithOnConflict(
                    TABLE_CHART,
                    null,
                    ContentValues().apply {
                        put("ticker", r.ticker)
                        put("t_ms", r.tMs)
                        put("yes_bid", r.yesBid)
                        put("no_bid", r.noBid)
                        put("yes_ask", r.yesAsk)
                        put("no_ask", r.noAsk)
                        put("spot_usd", r.spotUsd)
                        r.fairYesPp?.let { put("fair_yes_pp", it) }
                        put("source", r.source)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        scheduleChartCap()
    }

    /** Retention delete is off the insert transaction so a chart read is not stuck behind it. */
    private fun scheduleChartCap() {
        val now = System.currentTimeMillis()
        val prev = chartCapAtMs.get()
        if (prev != 0L && now - prev < CHART_CAP_INTERVAL_MS) return
        if (!chartCapAtMs.compareAndSet(prev, now)) return
        backgroundWriter.execute {
            runCatching {
                val writer = db.writableDatabase
                writer.beginTransaction()
                try {
                    writer.execSQL(
                        "DELETE FROM $TABLE_CHART WHERE id NOT IN (SELECT id FROM $TABLE_CHART ORDER BY t_ms DESC LIMIT $MAX_CHART)"
                    )
                    writer.setTransactionSuccessful()
                } finally {
                    writer.endTransaction()
                }
            }
        }
    }

    /**
     * Newest [limit] prints in [startMs, endMs], oldest-first.
     * `idx_chart_ticks_ticker (ticker, t_ms)` serves the inner DESC scan. Hard cap [CHART_QUERY_MAX].
     */
    override fun chartTicks(
        ticker: String,
        startMs: Long,
        endMs: Long,
        limit: Int
    ): List<com.dirk.kalshiodds.data.local.archive.ChartTickRow> {
        val cap = limit.coerceIn(1, CHART_QUERY_MAX)
        val out = ArrayList<com.dirk.kalshiodds.data.local.archive.ChartTickRow>(cap)
        db.readableDatabase.rawQuery(
            """
            SELECT ticker, t_ms, yes_bid, no_bid, yes_ask, no_ask, spot_usd, fair_yes_pp, source FROM (
              SELECT ticker, t_ms, yes_bid, no_bid, yes_ask, no_ask, spot_usd, fair_yes_pp, source
              FROM $TABLE_CHART
              WHERE ticker = ? AND t_ms >= ? AND t_ms <= ?
              ORDER BY t_ms DESC
              LIMIT $cap
            ) ORDER BY t_ms ASC
            """.trimIndent(),
            arrayOf(ticker, startMs.toString(), endMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.dirk.kalshiodds.data.local.archive.ChartTickRow(
                        ticker = c.getString(0),
                        tMs = c.getLong(1),
                        yesBid = c.dblAt(2),
                        noBid = c.dblAt(3),
                        yesAsk = c.dblAt(4),
                        noAsk = c.dblAt(5),
                        spotUsd = c.dblAt(6),
                        fairYesPp = c.dblAt(7),
                        source = c.getString(8) ?: com.dirk.kalshiodds.data.local.archive.ChartTickRow.SOURCE_LIVE
                    )
                )
            }
        }
        return out
    }

    override fun trimChartTicks(keepTickers: Set<String>, olderThanMs: Long) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (keepTickers.isNotEmpty()) {
                val placeholders = keepTickers.joinToString(",") { "?" }
                val args = keepTickers.map { it }.toTypedArray() + olderThanMs.toString()
                w.execSQL(
                    "DELETE FROM $TABLE_CHART WHERE ticker NOT IN ($placeholders) OR t_ms < ?",
                    args
                )
            } else {
                w.execSQL("DELETE FROM $TABLE_CHART WHERE t_ms < ?", arrayOf(olderThanMs.toString()))
            }
            w.execSQL(
                """
                DELETE FROM $TABLE_CHART WHERE id NOT IN (
                  SELECT id FROM $TABLE_CHART
                  WHERE ticker IN (SELECT DISTINCT ticker FROM $TABLE_CHART)
                  ORDER BY t_ms DESC
                  LIMIT $MAX_CHART
                )
                """.trimIndent()
            )
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun bidHistory(ticker: String, sinceMs: Long, limit: Int): List<com.dirk.kalshiodds.chart.BidPoint> {
        val out = ArrayList<com.dirk.kalshiodds.chart.BidPoint>(limit)
        db.readableDatabase.query(
            TABLE_CHART,
            arrayOf("t_ms", "yes_bid", "no_bid", "spot_usd"),
            "ticker = ? AND t_ms >= ?",
            arrayOf(ticker, sinceMs.toString()),
            null,
            null,
            "t_ms ASC",
            limit.coerceIn(1, 2_000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.dirk.kalshiodds.chart.BidPoint(
                        tMs = c.long("t_ms"),
                        upBidCents = c.dblOrNull("yes_bid")?.times(100.0)?.toFloat(),
                        downBidCents = c.dblOrNull("no_bid")?.times(100.0)?.toFloat(),
                        spotUsd = c.dblOrNull("spot_usd")
                    )
                )
            }
        }
        if (out.size >= 4) return out.sortedBy { it.tMs }
        db.readableDatabase.query(
            TABLE_ODDS,
            arrayOf("created_at_ms", "yes_bid", "no_bid", "mid01"),
            "ticker = ? AND created_at_ms >= ?",
            arrayOf(ticker, sinceMs.toString()),
            null,
            null,
            "created_at_ms ASC",
            limit.coerceIn(1, 2_000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                val yes = c.dblOrNull("yes_bid")
                val no = c.dblOrNull("no_bid")
                val mid = c.dblOrNull("mid01")
                out.add(
                    com.dirk.kalshiodds.chart.BidPoint(
                        tMs = c.long("created_at_ms"),
                        upBidCents = yes?.times(100.0)?.toFloat() ?: mid?.times(100.0)?.toFloat(),
                        downBidCents = no?.times(100.0)?.toFloat()
                            ?: mid?.let { ((1.0 - it) * 100.0).toFloat() }
                    )
                )
            }
        }
        if (out.size < 4) {
            db.readableDatabase.query(
                TABLE_PATH,
                arrayOf("t_ms", "yes_bid", "no_bid", "mid"),
                "ticker = ? AND t_ms >= ?",
                arrayOf(ticker, sinceMs.toString()),
                null,
                null,
                "t_ms ASC",
                limit.coerceIn(1, 2_000).toString()
            ).use { c ->
                while (c.moveToNext()) {
                    val yes = c.dblOrNull("yes_bid")
                    val no = c.dblOrNull("no_bid")
                    val mid = c.dblOrNull("mid")
                    out.add(
                        com.dirk.kalshiodds.chart.BidPoint(
                            tMs = c.long("t_ms"),
                            upBidCents = yes?.times(100.0)?.toFloat() ?: mid?.times(100.0)?.toFloat(),
                            downBidCents = no?.times(100.0)?.toFloat()
                                ?: mid?.let { ((1.0 - it) * 100.0).toFloat() }
                        )
                    )
                }
            }
        }
        return out.sortedBy { it.tMs }
    }

    override fun pricePath(ticker: String, limit: Int): List<com.dirk.kalshiodds.data.local.archive.PricePathRow> {
        val out = ArrayList<com.dirk.kalshiodds.data.local.archive.PricePathRow>(limit)
        db.readableDatabase.query(
            TABLE_PATH,
            null,
            "ticker = ?",
            arrayOf(ticker),
            null,
            null,
            "t_ms DESC",
            limit.coerceIn(1, 2_000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.dirk.kalshiodds.data.local.archive.PricePathRow(
                        ticker = c.str("ticker"),
                        tMs = c.long("t_ms"),
                        yesBid = c.dblOrNull("yes_bid"),
                        noBid = c.dblOrNull("no_bid"),
                        mid = c.dblOrNull("mid")
                    )
                )
            }
        }
        return out.asReversed()
    }

    override fun spotCloses(product: String, startMs: Long, endMs: Long): List<Double> {
        val out = ArrayList<Double>()
        db.readableDatabase.query(
            TABLE_SPOT,
            arrayOf("close"),
            "product = ? AND t_ms >= ? AND t_ms <= ?",
            arrayOf(product, startMs.toString(), endMs.toString()),
            null,
            null,
            "t_ms ASC",
            "400"
        ).use { c ->
            while (c.moveToNext()) out.add(c.dbl("close"))
        }
        return out
    }

    override fun stats(): com.dirk.kalshiodds.data.local.archive.DataStats {
        val r = db.readableDatabase
        fun count(sql: String): Int = r.rawQuery(sql, null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        fun longOrNull(sql: String): Long? = r.rawQuery(sql, null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
        return com.dirk.kalshiodds.data.local.archive.DataStats(
            settledCount = count("SELECT COUNT(*) FROM $TABLE_SETTLED"),
            minCloseMs = longOrNull("SELECT MIN(close_ms) FROM $TABLE_SETTLED"),
            maxCloseMs = longOrNull("SELECT MAX(close_ms) FROM $TABLE_SETTLED"),
            btc = count("SELECT COUNT(*) FROM $TABLE_SETTLED WHERE series LIKE '%BTC%'"),
            eth = count("SELECT COUNT(*) FROM $TABLE_SETTLED WHERE series LIKE '%ETH%'"),
            sol = count("SELECT COUNT(*) FROM $TABLE_SETTLED WHERE series LIKE '%SOL%'"),
            pathPoints = count("SELECT COUNT(*) FROM $TABLE_PATH"),
            spotCandles = count("SELECT COUNT(*) FROM $TABLE_SPOT"),
            fills = count("SELECT COUNT(*) FROM $TABLE_FILL"),
            yesSettled = count("SELECT COUNT(*) FROM $TABLE_SETTLED WHERE result = 'yes'"),
            noSettled = count("SELECT COUNT(*) FROM $TABLE_SETTLED WHERE result = 'no'")
        ).let { s ->
            s.copy(other = (s.settledCount - s.btc - s.eth - s.sol).coerceAtLeast(0))
        }
    }

    override fun settledTickers(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT ticker FROM $TABLE_SETTLED", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    override fun recentSettled(series: String?, limit: Int): List<com.dirk.kalshiodds.data.local.archive.SettledWindowRow> {
        val sql = if (series.isNullOrBlank()) {
            "SELECT ticker, series, result, strike, open_ms, close_ms, imported_at_ms, source FROM $TABLE_SETTLED ORDER BY close_ms DESC LIMIT ?"
        } else {
            "SELECT ticker, series, result, strike, open_ms, close_ms, imported_at_ms, source FROM $TABLE_SETTLED WHERE series = ? OR ticker LIKE ? ORDER BY close_ms DESC LIMIT ?"
        }
        val args = if (series.isNullOrBlank()) {
            arrayOf(limit.toString())
        } else {
            arrayOf(series, "$series%", limit.toString())
        }
        val out = ArrayList<com.dirk.kalshiodds.data.local.archive.SettledWindowRow>()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.dirk.kalshiodds.data.local.archive.SettledWindowRow(
                        ticker = c.getString(0),
                        series = c.getString(1) ?: "",
                        result = c.getString(2) ?: "",
                        strikeUsd = if (c.isNull(3)) null else c.getDouble(3),
                        openMs = if (c.isNull(4)) null else c.getLong(4),
                        closeMs = if (c.isNull(5)) null else c.getLong(5),
                        importedAtMs = if (c.isNull(6)) 0L else c.getLong(6),
                        source = c.getString(7) ?: "kalshi"
                    )
                )
            }
        }
        return out
    }

    override fun settledWindow(ticker: String): com.dirk.kalshiodds.data.local.archive.SettledWindowRow? {
        db.readableDatabase.rawQuery(
            "SELECT ticker, series, result, strike, open_ms, close_ms, imported_at_ms, source FROM $TABLE_SETTLED WHERE ticker = ? LIMIT 1",
            arrayOf(ticker)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return settledRow(c)
        }
    }

    private fun settledRow(c: Cursor) = com.dirk.kalshiodds.data.local.archive.SettledWindowRow(
        ticker = c.getString(0),
        series = c.getString(1) ?: "",
        result = c.getString(2) ?: "",
        strikeUsd = if (c.isNull(3)) null else c.getDouble(3),
        openMs = if (c.isNull(4)) null else c.getLong(4),
        closeMs = if (c.isNull(5)) null else c.getLong(5),
        importedAtMs = if (c.isNull(6)) 0L else c.getLong(6),
        source = c.getString(7) ?: "kalshi"
    )

    override fun existingFillIds(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT fill_id FROM $TABLE_FILL", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    override fun existingSnapshotKeys(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery(
            "SELECT ticker, created_at_ms, side FROM $TABLE_SNAP",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val side = if (c.isNull(2)) "" else c.getString(2).orEmpty()
                out.add("${c.getString(0)}|${c.getLong(1)}|$side")
            }
        }
        return out
    }

    override fun existingAlertIds(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT alert_id FROM $TABLE_ALERT", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0).orEmpty())
        }
        return out
    }

    override fun existingTicketKeys(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery(
            "SELECT COALESCE(client_order_id, ticker || '|' || created_at_ms) FROM $TABLE_TICKET",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0).orEmpty())
        }
        return out
    }

    override fun readCursor(job: String): com.dirk.kalshiodds.data.local.archive.BackfillCursorRow? {
        db.readableDatabase.query(
            TABLE_CURSOR,
            null,
            "job = ?",
            arrayOf(job),
            null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return com.dirk.kalshiodds.data.local.archive.BackfillCursorRow(
                job = c.str("job"),
                series = c.str("series"),
                cursor = c.strOrNull("cursor"),
                minCloseMs = c.long("min_close_ms").takeIf { it > 0L },
                lastTicker = c.strOrNull("last_ticker"),
                updatedAtMs = c.long("updated_at_ms"),
                status = c.str("status"),
                days = c.long("days").toInt().coerceAtLeast(1),
                processed = c.long("processed").toInt()
            )
        }
    }

    override fun writeCursor(row: com.dirk.kalshiodds.data.local.archive.BackfillCursorRow) {
        db.writableDatabase.insertWithOnConflict(
            TABLE_CURSOR,
            null,
            ContentValues().apply {
                put("job", row.job)
                put("series", row.series)
                put("cursor", row.cursor)
                put("min_close_ms", row.minCloseMs)
                put("last_ticker", row.lastTicker)
                put("updated_at_ms", row.updatedAtMs)
                put("status", row.status)
                put("days", row.days)
                put("processed", row.processed)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun clearCursor(job: String) {
        db.writableDatabase.delete(TABLE_CURSOR, "job = ?", arrayOf(job))
    }

    override fun insertSettingsChange(row: com.dirk.kalshiodds.data.local.history.SettingsChange) {
        db.writableDatabase.insert(
            TABLE_SETTINGS,
            null,
            ContentValues().apply {
                put("created_at_ms", row.createdAtMs)
                put("key", row.key)
                put("old_value", row.oldValue)
                put("new_value", row.newValue)
                put("snapshot_json", row.snapshotJson)
            }
        )
        prune(db.writableDatabase, TABLE_SETTINGS, MAX_SETTINGS)
    }

    override fun recentSettingsChanges(limit: Int, offset: Int): List<com.dirk.kalshiodds.data.local.history.SettingsChange> {
        val out = ArrayList<com.dirk.kalshiodds.data.local.history.SettingsChange>()
        db.readableDatabase.rawQuery(
            "SELECT id, created_at_ms, key, old_value, new_value, snapshot_json FROM $TABLE_SETTINGS ORDER BY created_at_ms DESC LIMIT ? OFFSET ?",
            arrayOf(limit.coerceIn(1, 400).toString(), offset.coerceAtLeast(0).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.dirk.kalshiodds.data.local.history.SettingsChange(
                        id = c.getLong(0),
                        createdAtMs = c.getLong(1),
                        key = c.getString(2).orEmpty(),
                        oldValue = c.getString(3).orEmpty(),
                        newValue = c.getString(4).orEmpty(),
                        snapshotJson = c.getString(5)
                    )
                )
            }
        }
        return out
    }

    override fun insertSession(row: com.dirk.kalshiodds.data.local.history.HistorySession) {
        db.writableDatabase.insertWithOnConflict(
            TABLE_SESSION,
            null,
            ContentValues().apply {
                put("id", row.id)
                put("started_at_ms", row.startedAtMs)
                put("ended_at_ms", row.endedAtMs)
                put("markets", row.markets)
                put("signals", row.signals)
                put("bets", row.bets)
                put("pnl_usd", row.pnlUsd)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun closeSession(
        id: String,
        endedAtMs: Long,
        markets: Int,
        signals: Int,
        bets: Int,
        pnlUsd: Double?
    ) {
        db.writableDatabase.execSQL(
            "UPDATE $TABLE_SESSION SET ended_at_ms = ?, markets = ?, signals = ?, bets = ?, pnl_usd = ? WHERE id = ?",
            arrayOf(endedAtMs, markets, signals, bets, pnlUsd, id)
        )
    }

    override fun repairInterruptedSessions(currentSessionId: String, recoveredAtMs: Long): Int =
        db.writableDatabase.compileStatement(
            "UPDATE $TABLE_SESSION SET ended_at_ms = MAX(started_at_ms, ?) WHERE id != ? AND ended_at_ms IS NULL"
        ).use { statement ->
            statement.bindLong(1, recoveredAtMs)
            statement.bindString(2, currentSessionId)
            statement.executeUpdateDelete()
        }

    override fun recentSessions(limit: Int): List<com.dirk.kalshiodds.data.local.history.HistorySession> {
        val out = ArrayList<com.dirk.kalshiodds.data.local.history.HistorySession>()
        db.readableDatabase.rawQuery(
            "SELECT id, started_at_ms, ended_at_ms, markets, signals, bets, pnl_usd FROM $TABLE_SESSION ORDER BY started_at_ms DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 200).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.dirk.kalshiodds.data.local.history.HistorySession(
                        id = c.getString(0),
                        startedAtMs = c.getLong(1),
                        endedAtMs = if (c.isNull(2)) null else c.getLong(2),
                        markets = c.getInt(3),
                        signals = c.getInt(4),
                        bets = c.getInt(5),
                        pnlUsd = if (c.isNull(6)) null else c.getDouble(6)
                    )
                )
            }
        }
        return out
    }

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

    override fun insertLedger(row: com.dirk.kalshiodds.data.local.ledger.LedgerRow): Long =
        insertLedgerOn(db.writableDatabase, row)

    /**
     * One transaction for a forecast tick (up to one row per coin). Retention delete is
     * scheduled on [backgroundWriter], not inside this transaction and not on the caller.
     */
    fun insertLedgerBatch(rows: List<com.dirk.kalshiodds.data.local.ledger.LedgerRow>): Int {
        if (rows.isEmpty()) return 0
        val writer = db.writableDatabase
        var ok = true
        writer.beginTransaction()
        try {
            for (row in rows) {
                if (insertLedgerOn(writer, row) < 0L) {
                    ok = false
                    break
                }
            }
            if (ok) writer.setTransactionSuccessful()
        } finally {
            writer.endTransaction()
        }
        if (!ok) return 0
        rows.filter { it.modelVersion == com.dirk.kalshiodds.decision.ScalpForecastRecorder.MODEL }
            .maxOfOrNull { it.timestampMs }
            ?.let { scheduleForecastPrune(it) }
        return rows.size
    }

    private fun insertLedgerOn(writer: SQLiteDatabase, row: com.dirk.kalshiodds.data.local.ledger.LedgerRow): Long {
        val values = ContentValues()
        for ((k, v) in com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.values(row)) {
            when (v) {
                null -> values.putNull(k)
                is Long -> values.put(k, v)
                is Int -> values.put(k, v)
                is Double -> if (v.isFinite()) values.put(k, v) else values.putNull(k)
                is String -> values.put(k, v)
                else -> values.put(k, v.toString())
            }
        }
        return writer.insert(
            com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE,
            null,
            values
        )
    }

    private val forecastPruneAtMs = java.util.concurrent.atomic.AtomicLong(0L)

    /** At most once an hour, on the background writer. Other models are never deleted. */
    private fun scheduleForecastPrune(nowMs: Long) {
        val prev = forecastPruneAtMs.get()
        if (prev != 0L && nowMs - prev < 3_600_000L) return
        if (!forecastPruneAtMs.compareAndSet(prev, nowMs)) return
        backgroundWriter.execute { runCatching { pruneWindowForecasts(nowMs) } }
    }

    /**
     * Delete [com.dirk.kalshiodds.decision.ScalpForecastRecorder.MODEL] rows older than
     * [keepMs] (~45 days). Decision-ledger rows stay.
     */
    @androidx.annotation.VisibleForTesting
    fun pruneWindowForecasts(nowMs: Long, keepMs: Long = FORECAST_KEEP_MS): Int =
        db.writableDatabase.delete(
            com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE,
            "model_version = ? AND timestamp_ms < ?",
            arrayOf(
                com.dirk.kalshiodds.decision.ScalpForecastRecorder.MODEL,
                (nowMs - keepMs).toString()
            )
        )

    /** 0.3.38 paper scalps: insert or replace one row by id. Never deletes. */
    override fun upsertScalp(trade: com.dirk.kalshiodds.decision.ScalpTrade) {
        var synced = 0
        var archived = 0
        db.readableDatabase.rawQuery("SELECT state, synced, IFNULL(archived,0) FROM ${com.dirk.kalshiodds.data.local.paper.ScalpSchema.TABLE} WHERE id = ?", arrayOf(trade.id)).use { c ->
            if (c.moveToFirst()) {
                val prevState = c.getString(0)
                val prevSynced = c.getInt(1)
                archived = c.getInt(2)
                val opened = trade.state == com.dirk.kalshiodds.decision.ScalpState.OPEN && prevState != "OPEN"
                val closed = trade.state == com.dirk.kalshiodds.decision.ScalpState.CLOSED && prevState != "CLOSED"
                synced = if (opened || closed) 0 else prevSynced
            }
        }
        val v = ContentValues().apply {
            put("id", trade.id)
            put("ticker", trade.ticker)
            put("side", trade.side)
            put("state", trade.state.name)
            put("signal_at_ms", trade.signalAtMs)
            put("signal_ask", trade.signalAsk)
            put("fair_at_signal", trade.fairAtSignal)
            put("contracts", trade.contracts)
            trade.entryPrice?.let { put("entry_price", it) } ?: putNull("entry_price")
            put("entry_fee_usd", trade.entryFeeUsd)
            trade.entryAtMs?.let { put("entry_at_ms", it) } ?: putNull("entry_at_ms")
            trade.exitDecidedAtMs?.let { put("exit_decided_at_ms", it) } ?: putNull("exit_decided_at_ms")
            put("exit_reason", trade.exitReason)
            put("sold_contracts", trade.soldContracts)
            put("proceeds_usd", trade.proceedsUsd)
            put("exit_fee_usd", trade.exitFeeUsd)
            trade.closedAtMs?.let { put("closed_at_ms", it) } ?: putNull("closed_at_ms")
            trade.netUsd?.let { put("net_usd", it) } ?: putNull("net_usd")
            put("note", trade.note)
            put("rule_version", trade.ruleVersion)
            put("scalper_id", com.dirk.kalshiodds.decision.ScalpModels.scalperIdOf(trade) ?: "")
            put("synced", synced)
            put("archived", archived)
        }
        db.writableDatabase.insertWithOnConflict(
            com.dirk.kalshiodds.data.local.paper.ScalpSchema.TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ---------------- 0.3.67 scalp ledger: indexed SQLite + incremental stats ----------------
    private val ST = com.dirk.kalshiodds.data.local.paper.ScalpSchema

    /** Upsert + (on the close transition) every aggregate table, in ONE transaction. */
    override fun upsertScalpAndStats(trade: com.dirk.kalshiodds.decision.ScalpTrade, prev: com.dirk.kalshiodds.decision.ScalpTrade?) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            var prevState: String? = null
            var prevAgg = 0
            var exists = false
            w.rawQuery("SELECT state, agg_v FROM ${ST.TABLE} WHERE id = ?", arrayOf(trade.id)).use { c ->
                if (c.moveToFirst()) { exists = true; prevState = c.getString(0); prevAgg = c.getInt(1) }
            }
            upsertScalp(trade)
            // A row not yet indexed by this generation is left for the background indexer (it counts it once, whole).
            if (!exists || prevAgg >= ST.AGG_V) {
                applyAgg(w, trade, wasOpen = exists && prevState != "CLOSED" && prevState != "NO_FILL",
                    wasClosed = exists && prevState == "CLOSED", wasNoFill = exists && prevState == "NO_FILL")
                w.execSQL("UPDATE ${ST.TABLE} SET agg_v = ? WHERE id = ?", arrayOf<Any>(ST.AGG_V, trade.id))
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    /** Apply one row's contribution (transitions only) to scalp_stats / scalp_daily / scalp_exits / scalp_windows. */
    private fun applyAgg(w: SQLiteDatabase, t: com.dirk.kalshiodds.decision.ScalpTrade, wasOpen: Boolean, wasClosed: Boolean, wasNoFill: Boolean) {
        val sid = com.dirk.kalshiodds.decision.ScalpModels.scalperIdOf(t) ?: return
        val nowOpen = t.state != com.dirk.kalshiodds.decision.ScalpState.CLOSED && t.state != com.dirk.kalshiodds.decision.ScalpState.NO_FILL
        w.execSQL("INSERT OR IGNORE INTO ${ST.STATS}(scalper_id) VALUES (?)", arrayOf(sid))
        if (nowOpen && !wasOpen) w.execSQL("UPDATE ${ST.STATS} SET open_n = open_n + 1 WHERE scalper_id = ?", arrayOf(sid))
        if (!nowOpen && wasOpen) w.execSQL("UPDATE ${ST.STATS} SET open_n = MAX(open_n - 1, 0) WHERE scalper_id = ?", arrayOf(sid))
        if (t.state == com.dirk.kalshiodds.decision.ScalpState.NO_FILL && !wasNoFill) w.execSQL("UPDATE ${ST.STATS} SET no_fills = no_fills + 1 WHERE scalper_id = ?", arrayOf(sid))
        if (wasClosed) return
        val d = com.dirk.kalshiodds.decision.ScalpCloseDelta.of(t) ?: return
        val win = if (d.win) 1 else 0
        val lose = 1 - win
        val neg = if (d.net < 0) 1 else 0
        val ss = d.stopSaved
        w.execSQL(
            "UPDATE ${ST.STATS} SET n = n + 1, wins = wins + ?, net_usd = net_usd + ?, fees_usd = fees_usd + ?, " +
                "best_usd = CASE WHEN best_usd IS NULL OR ? > best_usd THEN ? ELSE best_usd END, " +
                "worst_usd = CASE WHEN worst_usd IS NULL OR ? < worst_usd THEN ? ELSE worst_usd END, " +
                "sum_sq = sum_sq + ?, last_closed_at_ms = MAX(COALESCE(last_closed_at_ms, 0), ?), " +
                "sum_win = sum_win + ?, sum_loss = sum_loss + ?, n_loss = n_loss + ?, neg_sum = neg_sum + ?, neg_n = neg_n + ?, " +
                "cost_sum = cost_sum + ?, stop_saved_sum = stop_saved_sum + ?, stop_saved_n = stop_saved_n + ? WHERE scalper_id = ?",
            arrayOf<Any>(win, d.net, d.fees, d.net, d.net, d.net, d.net, d.net * d.net, d.closedAtMs,
                win * d.net, lose * d.net, lose, neg * d.net, neg, d.cost, ss ?: 0.0, if (ss != null) 1 else 0, sid)
        )
        w.execSQL("INSERT OR IGNORE INTO ${ST.DAILY}(scalper_id, day_et) VALUES (?, ?)", arrayOf(sid, d.day))
        w.execSQL(
            "UPDATE ${ST.DAILY} SET n = n + 1, wins = wins + ?, net_usd = net_usd + ?, fees_usd = fees_usd + ?, sum_sq = sum_sq + ?, " +
                "sum_win = sum_win + ?, sum_loss = sum_loss + ?, n_loss = n_loss + ?, neg_sum = neg_sum + ?, neg_n = neg_n + ?, cost_sum = cost_sum + ?, " +
                "best_usd = CASE WHEN best_usd IS NULL OR ? > best_usd THEN ? ELSE best_usd END, " +
                "worst_usd = CASE WHEN worst_usd IS NULL OR ? < worst_usd THEN ? ELSE worst_usd END WHERE scalper_id = ? AND day_et = ?",
            arrayOf<Any>(win, d.net, d.fees, d.net * d.net, win * d.net, lose * d.net, lose, neg * d.net, neg, d.cost, d.net, d.net, d.net, d.net, sid, d.day)
        )
        w.execSQL("INSERT OR IGNORE INTO ${ST.EXITS}(scalper_id, reason) VALUES (?, ?)", arrayOf(sid, d.reason))
        w.execSQL("UPDATE ${ST.EXITS} SET n = n + 1 WHERE scalper_id = ? AND reason = ?", arrayOf(sid, d.reason))
        w.execSQL("INSERT OR IGNORE INTO ${ST.WINDOWS}(window_key, scalper_id, close_ms) VALUES (?, ?, ?)", arrayOf<Any?>(d.windowKey, sid, d.windowCloseMs))
        w.execSQL("UPDATE ${ST.WINDOWS} SET n = n + 1, net_usd = net_usd + ? WHERE window_key = ? AND scalper_id = ?", arrayOf<Any>(d.net, d.windowKey, sid))
    }

    override fun loadStats(): List<com.dirk.kalshiodds.decision.ScalpStat> {
        val out = ArrayList<com.dirk.kalshiodds.decision.ScalpStat>()
        runCatching {
            db.readableDatabase.rawQuery("SELECT scalper_id, n, wins, net_usd, fees_usd, best_usd, worst_usd, sum_sq, last_closed_at_ms, open_n, " +
                "sum_win, sum_loss, n_loss, neg_sum, neg_n, cost_sum, stop_saved_sum, stop_saved_n, no_fills FROM ${ST.STATS}", null).use { c ->
                while (c.moveToNext()) out += com.dirk.kalshiodds.decision.ScalpStat(
                    c.getString(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getDouble(4),
                    if (c.isNull(5)) null else c.getDouble(5), if (c.isNull(6)) null else c.getDouble(6),
                    c.getDouble(7), if (c.isNull(8)) null else c.getLong(8), c.getInt(9),
                    c.getDouble(10), c.getDouble(11), c.getInt(12), c.getDouble(13), c.getInt(14), c.getDouble(15), c.getDouble(16), c.getInt(17), c.getInt(18))
            }
        }
        return out
    }

    override fun loadDaily(dayEt: String): List<com.dirk.kalshiodds.decision.ScalpDaily> = dailyRows("day_et = ?", arrayOf(dayEt))

    override fun loadDailySince(dayEtInclusive: String): List<com.dirk.kalshiodds.decision.ScalpDaily> =
        dailyRows("day_et >= ?", arrayOf(dayEtInclusive))

    private fun dailyRows(where: String, args: Array<String>): List<com.dirk.kalshiodds.decision.ScalpDaily> {
        val out = ArrayList<com.dirk.kalshiodds.decision.ScalpDaily>()
        runCatching {
            db.readableDatabase.rawQuery("SELECT scalper_id, day_et, n, wins, net_usd, fees_usd, sum_sq, sum_win, sum_loss, n_loss, neg_sum, neg_n, cost_sum, best_usd, worst_usd " +
                "FROM ${ST.DAILY} WHERE $where", args).use { c ->
                while (c.moveToNext()) out += com.dirk.kalshiodds.decision.ScalpDaily(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getDouble(4), c.getDouble(5),
                    c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getInt(9), c.getDouble(10), c.getInt(11), c.getDouble(12),
                    if (c.isNull(13)) null else c.getDouble(13), if (c.isNull(14)) null else c.getDouble(14))
            }
        }
        return out
    }

    override fun loadExits(): List<com.dirk.kalshiodds.decision.ScalpExitAgg> {
        val out = ArrayList<com.dirk.kalshiodds.decision.ScalpExitAgg>()
        runCatching {
            db.readableDatabase.rawQuery("SELECT scalper_id, reason, n FROM ${ST.EXITS}", null).use { c ->
                while (c.moveToNext()) out += com.dirk.kalshiodds.decision.ScalpExitAgg(c.getString(0), c.getString(1), c.getInt(2))
            }
        }
        return out
    }

    override fun loadWindows(limitWindows: Int): List<com.dirk.kalshiodds.decision.ScalpWindowAgg> {
        val out = ArrayList<com.dirk.kalshiodds.decision.ScalpWindowAgg>()
        runCatching {
            // 0.3.68: one pass down the close_ms index; stop at the first row of window limit+1 (no GROUP BY over every window).
            val keys = HashSet<String>()
            db.readableDatabase.rawQuery("SELECT window_key, scalper_id, n, net_usd, close_ms FROM ${ST.WINDOWS} ORDER BY close_ms DESC LIMIT ${(limitWindows + 1) * (com.dirk.kalshiodds.decision.ScalpModels.COUNT + 4)}", null).use { c ->
                while (c.moveToNext()) {
                    val k = c.getString(0)
                    if (keys.add(k) && keys.size > limitWindows) break
                    out += com.dirk.kalshiodds.decision.ScalpWindowAgg(k, c.getString(1), c.getInt(2), c.getDouble(3), if (c.isNull(4)) null else c.getLong(4))
                }
            }
        }
        return out
    }

    private fun scalpRows(sql: String, args: Array<String>?): List<com.dirk.kalshiodds.decision.ScalpTrade> {
        val out = ArrayList<com.dirk.kalshiodds.decision.ScalpTrade>()
        db.readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += scalpFromCursor(c) }
        return out
    }

    override fun loadActiveScalps(): List<com.dirk.kalshiodds.decision.ScalpTrade> = runCatching {
        // Open rows stay in memory. Recently closed rows are a bounded page (entry rules), not the whole table.
        val since = System.currentTimeMillis() - 20 * 60_000L
        val open = scalpRows(
            "SELECT * FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0 AND state NOT IN ('CLOSED','NO_FILL') ORDER BY signal_at_ms DESC LIMIT $ACTIVE_OPEN_CAP",
            null
        )
        val recent = scalpRows(
            "SELECT * FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0 AND state IN ('CLOSED','NO_FILL') AND closed_at_ms >= ? ORDER BY closed_at_ms DESC LIMIT $ACTIVE_RECENT_CAP",
            arrayOf(since.toString())
        )
        (open + recent).distinctBy { it.id }
    }.getOrDefault(emptyList())

    /** Keyset paging, newest first, index (scalper_id, closed_at_ms DESC). */
    override fun pageTrades(scalperId: String, afterClosedAtMs: Long?, afterId: String?, limit: Int): List<com.dirk.kalshiodds.decision.ScalpTrade> = runCatching {
        val ids = com.dirk.kalshiodds.decision.KeptScalpers.storedIds(scalperId)
        val marks = ids.joinToString(",") { "?" }
        if (afterClosedAtMs == null) scalpRows("SELECT * FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0 AND scalper_id IN ($marks) AND state = 'CLOSED' ORDER BY closed_at_ms DESC, id DESC LIMIT $limit", ids.toTypedArray())
        else scalpRows("SELECT * FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0 AND scalper_id IN ($marks) AND state = 'CLOSED' AND (closed_at_ms < ? OR (closed_at_ms = ? AND id < ?)) ORDER BY closed_at_ms DESC, id DESC LIMIT $limit",
            (ids + afterClosedAtMs.toString() + afterClosedAtMs.toString() + (afterId ?: "")).toTypedArray())
    }.getOrDefault(emptyList())

    override fun windowPrimaryTrades(ticker: String, limit: Int): List<com.dirk.kalshiodds.decision.ScalpTrade> =
        scalpRows("SELECT * FROM ${ST.TABLE} INDEXED BY idx_scalp_ticker WHERE IFNULL(archived,0)=0 AND ticker = ? AND scalper_id IS NOT NULL AND scalper_id != '' ORDER BY signal_at_ms DESC LIMIT ${limit.coerceIn(1, 600)}", arrayOf(ticker.uppercase()))
            .filter { it.isPrimary }.sortedBy { it.signalAtMs }

    override fun windowTrades(scalperId: String, ticker: String): List<com.dirk.kalshiodds.decision.ScalpTrade> {
        val ids = com.dirk.kalshiodds.decision.KeptScalpers.storedIds(scalperId)
        val marks = ids.joinToString(",") { "?" }
        return scalpRows("SELECT * FROM ${ST.TABLE} INDEXED BY idx_scalp_ticker WHERE IFNULL(archived,0)=0 AND ticker = ? AND scalper_id IN ($marks) ORDER BY signal_at_ms",
            arrayOf(ticker.uppercase(), *ids.toTypedArray()))
    }

    /** Uploader: unsynced closed primaries by index (synced, closed_at_ms), oldest first. */
    override fun unsyncedClosed(limit: Int): List<com.dirk.kalshiodds.decision.ScalpTrade> = runCatching {
        scalpRows("SELECT * FROM ${ST.TABLE} WHERE synced = 0 AND state IN ('OPEN','CLOSED') AND scalper_id IS NOT NULL AND scalper_id != '' ORDER BY COALESCE(entry_at_ms, closed_at_ms, signal_at_ms) ASC LIMIT $limit", null)
    }.getOrDefault(emptyList())

    override fun markSynced(ids: List<String>) {
        if (ids.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            ids.chunked(500).forEach { ch ->
                w.execSQL("UPDATE ${ST.TABLE} SET synced = 1 WHERE id IN (${ch.joinToString(",") { "?" }})", ch.toTypedArray())
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    /** One-time: everything closed at or before the 0.3.65/0.3.66 ack watermark is already in Supabase. */
    fun markSyncedUpTo(closedAtMs: Long) {
        runCatching { db.writableDatabase.execSQL("UPDATE ${ST.TABLE} SET synced = 1 WHERE synced = 0 AND state = 'CLOSED' AND closed_at_ms <= ?", arrayOf<Any>(closedAtMs)) }
    }

    /** 0.3.67/0.3.68 background indexer: rows with agg_v < AGG_V → tagged + counted whole, [limit] per transaction. */
    override fun migrateStatsChunk(limit: Int): Int {
        val w = db.writableDatabase
        val rows = runCatching { scalpRows("SELECT * FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0 AND agg_v < ${ST.AGG_V} LIMIT $limit", null) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return 0
        w.beginTransaction()
        try {
            val mark = w.compileStatement("UPDATE ${ST.TABLE} SET scalper_id = ?, agg_v = ${ST.AGG_V} WHERE id = ? AND agg_v < ${ST.AGG_V}")
            for (t in rows) {
                val sid = com.dirk.kalshiodds.decision.ScalpModels.scalperIdOf(t)
                mark.clearBindings(); mark.bindString(1, sid ?: ""); mark.bindString(2, t.id)
                if (mark.executeUpdateDelete() != 1) continue // counted meanwhile by the live path
                if (sid != null) applyAgg(w, t, wasOpen = false, wasClosed = false, wasNoFill = false)
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        return rows.size
    }

    override fun tradeCount(): Int = runCatching {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${ST.TABLE}", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    override fun archiveDisplayedRun(flattened: List<com.dirk.kalshiodds.decision.ScalpTrade>, atMs: Long, note: String, cashUsd: Double): Int {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            flattened.forEach { upsertScalp(it) }
            var n = 0
            w.rawQuery("SELECT COUNT(*) FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0", null).use { c ->
                if (c.moveToFirst()) n = c.getInt(0)
            }
            w.execSQL("UPDATE ${ST.TABLE} SET archived = 1, agg_v = ${ST.AGG_V} WHERE IFNULL(archived,0)=0")
            w.execSQL("DELETE FROM ${ST.STATS}")
            w.execSQL("DELETE FROM ${ST.DAILY}")
            w.execSQL("DELETE FROM ${ST.EXITS}")
            w.execSQL("DELETE FROM ${ST.WINDOWS}")
            val v = ContentValues().apply {
                put("at_ms", atMs)
                put("note", note)
                put("archived_n", n)
                put("cash_usd", cashUsd)
                put("synced", 0)
            }
            w.insertWithOnConflict(ST.RESETS, null, v, SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
            return n
        } finally { w.endTransaction() }
    }

    override fun unsyncedScalpResets(): List<com.dirk.kalshiodds.decision.ScalpResetMarker> = runCatching {
        val out = ArrayList<com.dirk.kalshiodds.decision.ScalpResetMarker>()
        db.readableDatabase.rawQuery("SELECT at_ms, note, archived_n, cash_usd, synced FROM ${ST.RESETS} WHERE synced = 0 ORDER BY at_ms", null).use { c ->
            while (c.moveToNext()) out += com.dirk.kalshiodds.decision.ScalpResetMarker(
                c.getLong(0), c.getString(1) ?: "", c.getInt(2), c.getDouble(3), c.getInt(4) != 0
            )
        }
        out
    }.getOrDefault(emptyList())

    override fun markScalpResetsSynced(atMs: List<Long>) {
        if (atMs.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            atMs.chunked(500).forEach { ch ->
                w.execSQL("UPDATE ${ST.RESETS} SET synced = 1 WHERE at_ms IN (${ch.joinToString(",") { "?" }})", ch.toTypedArray())
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    fun unmigratedCount(): Int = runCatching {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${ST.TABLE} WHERE agg_v < ${ST.AGG_V}", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    override fun statsMigrated(): Boolean = unmigratedCount() == 0
    @androidx.annotation.VisibleForTesting internal fun writableForTest(): SQLiteDatabase = db.writableDatabase

    private val paperOrderJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun upsertPaperOrder(order: com.dirk.kalshiodds.signal.paper.PaperOrder) {
        val v = android.content.ContentValues().apply {
            put("id", order.id)
            put("ticker", order.ticker)
            put("status", order.status)
            put("source", order.source)
            put("created_at_ms", order.createdAtMs)
            put("updated_at_ms", order.updatedAtMs)
            put("json", paperOrderJson.encodeToString(com.dirk.kalshiodds.signal.paper.PaperOrder.serializer(), order))
        }
        db.writableDatabase.insertWithOnConflict(
            com.dirk.kalshiodds.data.local.paper.PaperOrderSchema.TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun loadPaperOrders(): List<com.dirk.kalshiodds.signal.paper.PaperOrder> {
        val out = ArrayList<com.dirk.kalshiodds.signal.paper.PaperOrder>()
        runCatching {
            db.readableDatabase.query(
                com.dirk.kalshiodds.data.local.paper.PaperOrderSchema.TABLE, arrayOf("json"), null, null, null, null, "created_at_ms DESC", "2000"
            ).use { c ->
                while (c.moveToNext()) {
                    runCatching { paperOrderJson.decodeFromString(com.dirk.kalshiodds.signal.paper.PaperOrder.serializer(), c.getString(0)) }
                        .getOrNull()?.let { out += it }
                }
            }
        }
        return out
    }

    override fun loadScalps(): List<com.dirk.kalshiodds.decision.ScalpTrade> = runCatching {
        // 0.3.67: bounded (tuner input only); the UI never loads the whole table.
        scalpRows("SELECT * FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0 AND state = 'CLOSED' ORDER BY closed_at_ms DESC LIMIT 6000", null)
    }.getOrDefault(emptyList())

    private fun scalpFromCursor(c: android.database.Cursor): com.dirk.kalshiodds.decision.ScalpTrade {
        val state = runCatching { com.dirk.kalshiodds.decision.ScalpState.valueOf(c.str("state")) }
            .getOrDefault(com.dirk.kalshiodds.decision.ScalpState.NO_FILL)
        return com.dirk.kalshiodds.decision.ScalpTrade(
            id = c.str("id"),
            ticker = c.str("ticker"),
            side = c.str("side"),
            state = state,
            signalAtMs = c.long("signal_at_ms"),
            signalAsk = c.dbl("signal_ask"),
            fairAtSignal = c.dbl("fair_at_signal"),
            contracts = c.intOrNull("contracts") ?: 0,
            entryPrice = c.dblOrNull("entry_price"),
            entryFeeUsd = c.dbl("entry_fee_usd"),
            entryAtMs = c.dblOrNull("entry_at_ms")?.toLong(),
            exitDecidedAtMs = c.dblOrNull("exit_decided_at_ms")?.toLong(),
            exitReason = c.strOrNull("exit_reason"),
            soldContracts = c.intOrNull("sold_contracts") ?: 0,
            proceedsUsd = c.dbl("proceeds_usd"),
            exitFeeUsd = c.dbl("exit_fee_usd"),
            closedAtMs = c.dblOrNull("closed_at_ms")?.toLong(),
            netUsd = c.dblOrNull("net_usd"),
            note = c.strOrNull("note").orEmpty(),
            ruleVersion = c.strOrNull("rule_version") ?: com.dirk.kalshiodds.decision.ScalpRule.VERSION
        )
    }

    /**
     * Settled 15-minute window-forecast rows in [sinceCloseMs, untilCloseMs], reduced
     * to the latest timestamp at or before close for each coin and window.
     *
     * Only [com.dirk.kalshiodds.decision.ScalpForecastRecorder.MODEL]. Decision-pipeline
     * rows for the same 15-minute tickers are a different model and must not win the
     * "last forecast" slot. `settlement_result` is stored lowercase by [settleLedger],
     * so the predicate matches the `(settlement_result, timestamp_ms)` index.
     * `timestamp_ms >= sinceClose - 15 min` keeps that index from walking the whole table.
     */
    fun calibrationLedgerRows(sinceCloseMs: Long, untilCloseMs: Long): List<com.dirk.kalshiodds.data.local.ledger.LedgerRow> =
        runCatching {
            val coin = coinKeySql("series", "ticker")
            val sinceTs = sinceCloseMs - CALIBRATION_LEAD_MS
            val model = com.dirk.kalshiodds.decision.ScalpForecastRecorder.MODEL
            val table = com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE
            val sql = """
                SELECT p.* FROM $table p
                INNER JOIN (
                  SELECT coin_key, close_ms, MAX(ts) AS ts FROM (
                    SELECT $coin AS coin_key, close_time_ms AS close_ms, timestamp_ms AS ts
                    FROM $table
                    WHERE settlement_result IN ('yes','no')
                      AND model_version = ?
                      AND final_prob IS NOT NULL
                      AND close_time_ms IS NOT NULL
                      AND close_time_ms >= ? AND close_time_ms <= ?
                      AND timestamp_ms >= ?
                      AND timestamp_ms <= close_time_ms
                      AND (UPPER(IFNULL(series, '')) LIKE '%15M%' OR UPPER(ticker) LIKE '%15M%')
                  )
                  GROUP BY coin_key, close_ms
                ) g
                ON p.close_time_ms = g.close_ms AND p.timestamp_ms = g.ts AND ${coinKeySql("p.series", "p.ticker")} = g.coin_key
                WHERE p.settlement_result IN ('yes','no')
                  AND p.model_version = ?
                  AND p.final_prob IS NOT NULL
                  AND p.timestamp_ms >= ?
                  AND p.timestamp_ms <= p.close_time_ms
            """.trimIndent()
            val args = arrayOf(
                model,
                sinceCloseMs.toString(),
                untilCloseMs.toString(),
                sinceTs.toString(),
                model,
                sinceTs.toString()
            )
            val out = ArrayList<com.dirk.kalshiodds.data.local.ledger.LedgerRow>()
            db.readableDatabase.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) out += cursorToLedger(c)
            }
            out
        }.getOrDefault(emptyList())

    /** Closed scalps held to settlement (the note carries a settlement fill). Bounded. */
    fun settlementScalps(sinceMs: Long, limit: Int = 4_000): List<com.dirk.kalshiodds.decision.ScalpTrade> = runCatching {
        scalpRows(
            "SELECT * FROM ${ST.TABLE} WHERE IFNULL(archived,0)=0 AND state = 'CLOSED' AND closed_at_ms >= ? AND note LIKE '%scalpFill=%' AND note LIKE '%,X%' " +
                "ORDER BY closed_at_ms DESC LIMIT ${limit.coerceIn(1, 8_000)}",
            arrayOf(sinceMs.toString())
        )
    }.getOrDefault(emptyList())

    /**
     * Recent closed scalps for heatmaps, side bars, and family stacks.
     * The note is omitted. Shadows (`|S`) stay out so they do not crowd the limit.
     */
    fun closedScalpsForCharts(sinceMs: Long, limit: Int = 8_000): List<com.dirk.kalshiodds.decision.ScalpTrade> = runCatching {
        scalpRows(
            "SELECT id, ticker, side, state, signal_at_ms, signal_ask, fair_at_signal, contracts, entry_price, entry_fee_usd, " +
                "entry_at_ms, NULL AS exit_decided_at_ms, exit_reason, sold_contracts, proceeds_usd, exit_fee_usd, closed_at_ms, " +
                "net_usd, '' AS note, rule_version FROM ${ST.TABLE} " +
                "WHERE IFNULL(archived,0)=0 AND state = 'CLOSED' AND closed_at_ms >= ? AND net_usd IS NOT NULL " +
                "AND (rule_version IS NULL OR rule_version LIKE '%|P' OR INSTR(rule_version, '|') = 0) " +
                "ORDER BY closed_at_ms DESC LIMIT ${limit.coerceIn(1, 12_000)}",
            arrayOf(sinceMs.toString())
        ).filter { it.isPrimary }
    }.getOrDefault(emptyList())

    private fun coinKeySql(series: String, ticker: String): String =
        "CASE WHEN UPPER(IFNULL($series, '') || ' ' || $ticker) LIKE '%SOL%' THEN 'SOL' " +
            "WHEN UPPER(IFNULL($series, '') || ' ' || $ticker) LIKE '%ETH%' THEN 'ETH' ELSE 'BTC' END"

    /** Fill in the outcome on every unsettled prediction for [ticker]. Never deletes rows. */
    fun settleLedger(ticker: String, result: String, atMs: Long): Int {
        val values = ContentValues().apply {
            put("settlement_result", result.lowercase())
            put("settled_at_ms", atMs)
        }
        return db.writableDatabase.update(
            com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE,
            values,
            "ticker = ? AND settlement_result IS NULL",
            arrayOf(ticker.uppercase())
        )
    }

    private fun queryLedger(
        selection: String?,
        args: Array<String>?,
        order: String,
        limit: Int
    ): List<com.dirk.kalshiodds.data.local.ledger.LedgerRow> {
        val out = ArrayList<com.dirk.kalshiodds.data.local.ledger.LedgerRow>()
        db.readableDatabase.query(
            com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE,
            null,
            selection,
            args,
            null,
            null,
            order,
            limit.coerceAtLeast(1).toString()
        ).use { c ->
            while (c.moveToNext()) out.add(cursorToLedger(c))
        }
        return out
    }

    fun recentLedger(limit: Int): List<com.dirk.kalshiodds.data.local.ledger.LedgerRow> =
        queryLedger(null, null, "timestamp_ms DESC", limit)

    /**
     * Settled decision-model predictions newest first, for [com.dirk.kalshiodds.decision.RegimeCalibration.fit].
     * Window-forecast rows are excluded so they cannot fill [com.dirk.kalshiodds.decision.DecisionRuntime.MAX_FIT_ROWS]
     * or move the fit.
     */
    override fun settledLedger(sinceMs: Long, limit: Int): List<com.dirk.kalshiodds.data.local.ledger.LedgerRow> =
        queryLedger(
            "settlement_result IN ('yes','no') AND timestamp_ms >= ? AND IFNULL(model_version, '') != ?",
            arrayOf(sinceMs.toString(), com.dirk.kalshiodds.decision.ScalpForecastRecorder.MODEL),
            "timestamp_ms DESC",
            limit
        )

    /** Tickers with an unsettled prediction whose window has closed — fed to the settlement poller. */
    override fun unsettledLedgerTickers(nowMs: Long, sinceMs: Long, limit: Int): List<String> {
        val out = ArrayList<String>()
        db.readableDatabase.rawQuery(
            "SELECT ticker, MAX(close_time_ms) AS c FROM ${com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE} " +
                "WHERE settlement_result IS NULL AND close_time_ms IS NOT NULL AND close_time_ms <= ? AND close_time_ms >= ? " +
                "GROUP BY ticker ORDER BY c DESC LIMIT ?",
            arrayOf(nowMs.toString(), sinceMs.toString(), limit.coerceAtLeast(1).toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun ledgerCount(): Long =
        android.database.DatabaseUtils.queryNumEntries(
            db.readableDatabase,
            com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE
        )

    /** Full export — no truncation (paged internally). */
    /** Small DBs / tests only — builds a String. Use [writeLedgerCsv] for exports. */
    fun ledgerCsv(): String = java.io.StringWriter().also { writeLedgerCsv(it) }.toString()

    /**
     * 0.3.69: stream the ledger as CSV. Keyset pages (id > last, [pageSize] rows) are written straight to [out] and
     * dropped, so memory stays at one page no matter how many rows. Returns rows written.
     */
    fun writeLedgerCsv(out: Appendable, pageSize: Int = 1_000): Long {
        com.dirk.kalshiodds.data.local.ledger.LedgerCsv.writeHeader(out)
        var lastId = 0L
        var n = 0L
        while (true) {
            val page = queryLedger("id > ?", arrayOf(lastId.toString()), "id ASC", pageSize)
            if (page.isEmpty()) break
            page.forEach { com.dirk.kalshiodds.data.local.ledger.LedgerCsv.writeRow(out, it) }
            n += page.size
            lastId = page.last().id
            if (page.size < pageSize) break
        }
        return n
    }

    private fun cursorToLedger(c: Cursor): com.dirk.kalshiodds.data.local.ledger.LedgerRow {
        val types = com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.COLUMNS.associate { it.name to it.type }
        return com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.fromReader(c.long("id")) { name ->
            val i = c.getColumnIndex(name)
            if (i < 0 || c.isNull(i)) {
                null
            } else {
                when (types[name]) {
                    com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.Type.INT -> c.getLong(i)
                    com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.Type.REAL -> c.getDouble(i)
                    else -> c.getString(i)
                }
            }
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }

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
            createOddsTable(db)
            createArchiveTables(db)
            createHistoryTables(db)
            createChartTickTable(db)
            createPaperFillTable(db)
            createPendingOrdersTable(db)
            createLedgerTable(db)
            createScalpTable(db)
            createPaperOrderTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createOddsTable(db)
            if (oldVersion < 3) {
                addBidColumns(db)
                createArchiveTables(db)
            }
            if (oldVersion < 4) createHistoryTables(db)
            if (oldVersion < 5) createChartTickTable(db)
            if (oldVersion < 6) createPaperFillTable(db)
            if (oldVersion < 7) createLedgerTable(db)
            if (oldVersion < 8) createScalpTable(db)
            if (oldVersion < 9) createPaperOrderTable(db)
            if (oldVersion < 10) createScalpTable(db)
            // 0.3.68: move (rename) the 0.3.67 aggregates aside; fresh tables rebuilt by the background indexer.
            if (oldVersion == 10) com.dirk.kalshiodds.data.local.paper.ScalpSchema.moveV10Sql().forEach { runCatching { db.execSQL(it) } }
            if (oldVersion < 11) createScalpTable(db)
            if (oldVersion < 12) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_prediction_ledger_close ON " +
                        "${com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.TABLE}(close_time_ms)"
                )
            }
            if (oldVersion < 13) runCatching { db.execSQL(com.dirk.kalshiodds.data.local.chart.ChartTickSchema.ADD_FAIR) }
            applyPaperFillColumns(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            runCatching { db.execSQL(com.dirk.kalshiodds.data.local.chart.ChartTickSchema.ADD_FAIR) }
            runCatching { db.execSQL(com.dirk.kalshiodds.data.local.chart.ChartTickSchema.INDEX) }
            applyPaperFillColumns(db)
            createPendingOrdersTable(db)
            createScalpTable(db)
            createPaperOrderTable(db)
        }

        private fun createPaperOrderTable(db: SQLiteDatabase) {
            for (sql in com.dirk.kalshiodds.data.local.paper.PaperOrderSchema.upgradeSql(
                com.dirk.kalshiodds.data.local.paper.PaperOrderSchema.FROM_VERSION
            )) {
                db.execSQL(sql)
            }
        }

        private fun createScalpTable(db: SQLiteDatabase) {
            for (sql in com.dirk.kalshiodds.data.local.paper.ScalpSchema.upgradeSql(
                com.dirk.kalshiodds.data.local.paper.ScalpSchema.FROM_VERSION
            )) {
                // 0.3.67: ALTER ADD COLUMN throws when it already exists — ignore that one statement.
                runCatching { db.execSQL(sql) }
            }
        }

        private fun createPendingOrdersTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PENDING (
                  order_key TEXT PRIMARY KEY,
                  client_order_id TEXT NOT NULL,
                  attempted INTEGER NOT NULL,
                  ticker TEXT,
                  side TEXT,
                  kind TEXT,
                  updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        private fun createLedgerTable(db: SQLiteDatabase) {
            for (sql in com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.upgradeSql(
                com.dirk.kalshiodds.data.local.ledger.PredictionLedgerSchema.FROM_VERSION
            )) {
                db.execSQL(sql)
            }
        }

        private fun createPaperFillTable(db: SQLiteDatabase) {
            for (sql in com.dirk.kalshiodds.data.local.paper.PaperFillSchema.upgradeSql(5)) {
                db.execSQL(sql)
            }
            applyPaperFillColumns(db)
        }

        private fun applyPaperFillColumns(db: SQLiteDatabase) {
            for (sql in com.dirk.kalshiodds.data.local.paper.PaperFillSchema.nullableColumnSql()) {
                runCatching { db.execSQL(sql) }
            }
        }

        private fun createChartTickTable(db: SQLiteDatabase) {
            for (sql in com.dirk.kalshiodds.data.local.chart.ChartTickSchema.upgradeSql(4)) {
                db.execSQL(sql)
            }
        }

        private fun createOddsTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_ODDS (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ticker TEXT NOT NULL,
                  mid01 REAL NOT NULL,
                  created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_odds_ticker_time ON $TABLE_ODDS(ticker, created_at_ms)")
            addBidColumns(db)
        }

        private fun addBidColumns(db: SQLiteDatabase) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_ODDS ADD COLUMN yes_bid REAL") }
            runCatching { db.execSQL("ALTER TABLE $TABLE_ODDS ADD COLUMN no_bid REAL") }
        }

        private fun createArchiveTables(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SETTLED (
                  ticker TEXT PRIMARY KEY,
                  series TEXT,
                  result TEXT,
                  strike REAL,
                  open_ms INTEGER,
                  close_ms INTEGER,
                  imported_at_ms INTEGER,
                  source TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PATH (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  ticker TEXT NOT NULL,
                  t_ms INTEGER NOT NULL,
                  yes_bid REAL,
                  no_bid REAL,
                  mid REAL,
                  UNIQUE(ticker, t_ms)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SPOT (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  product TEXT NOT NULL,
                  t_ms INTEGER NOT NULL,
                  open REAL, high REAL, low REAL, close REAL, volume REAL,
                  UNIQUE(product, t_ms)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_FILL (
                  fill_id TEXT PRIMARY KEY,
                  ticker TEXT,
                  side TEXT,
                  action TEXT,
                  count REAL,
                  price REAL,
                  created_at_ms INTEGER,
                  source TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_CURSOR (
                  job TEXT PRIMARY KEY,
                  series TEXT,
                  cursor TEXT,
                  min_close_ms INTEGER,
                  last_ticker TEXT,
                  updated_at_ms INTEGER,
                  status TEXT,
                  days INTEGER,
                  processed INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_settled_close ON $TABLE_SETTLED(close_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_path_ticker ON $TABLE_PATH(ticker, t_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_spot_prod ON $TABLE_SPOT(product, t_ms)")
        }

        private fun createHistoryTables(db: SQLiteDatabase) {
            for (sql in com.dirk.kalshiodds.data.local.history.ArchiveSchema.upgradeSql(3)) {
                db.execSQL(sql)
            }
        }
    }

    companion object {
        const val DB_NAME = "diphunter_results.db"
        const val DB_VERSION = 13
        /** A 15-minute window. Calibration ignores ledger rows written earlier than this before the close range. */
        const val CALIBRATION_LEAD_MS = 900_000L
        /** Window-forecast rows older than this are the only ledger rows prune deletes. */
        const val FORECAST_KEEP_MS = 45L * 86_400_000L
        /** Indexed chartTicks hard cap. Callers asking for more still get the newest rows only. */
        const val CHART_QUERY_MAX = 2_000
        const val CHART_CAP_INTERVAL_MS = 60_000L
        const val ACTIVE_OPEN_CAP = 400
        const val ACTIVE_RECENT_CAP = 800
        const val SNAPSHOT_PAGE = 200
        const val TABLE_SETTINGS = "settings_history"
        const val TABLE_SESSION = "sessions"
        const val MAX_SETTINGS = 400
        const val TABLE_SNAP = "scored_snapshots"
        const val TABLE_ALERT = "alerts_fired"
        const val TABLE_CARD = "scorecard_rows"
        const val TABLE_TICKET = "ticket_attempts"
        const val TABLE_PENDING = "pending_client_orders"
        const val TABLE_ODDS = "odds_mids"
        const val TABLE_SETTLED = "settled_windows"
        const val TABLE_PATH = "price_path"
        const val TABLE_SPOT = "spot_candles"
        const val TABLE_FILL = "imported_fills"
        const val TABLE_CURSOR = "backfill_cursor"
        const val TABLE_CHART = com.dirk.kalshiodds.data.local.chart.ChartTickSchema.TABLE
        const val MAX_SNAP = 1_200
        const val MAX_ALERT = 400
        const val MAX_CARD = 600
        const val MAX_TICKET = 300
        const val MAX_ODDS = 2_400
        const val MAX_PATH = 8_000
        const val MAX_SPOT = 12_000
        const val MAX_CHART = 2_880
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
private fun Cursor.dblAt(index: Int): Double? {
    if (index < 0 || isNull(index)) return null
    return getDouble(index)
}
private fun Cursor.intOrNull(col: String): Int? {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return null
    return getInt(i)
}
