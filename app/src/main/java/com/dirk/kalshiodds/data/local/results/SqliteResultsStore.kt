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
class SqliteResultsStore(context: Context) : ResultsStore, com.dirk.kalshiodds.data.local.archive.DataArchive {
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

    override fun insertSettlementIndex(rows: List<SettlementIndexRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                w.insertWithOnConflict(
                    TABLE_SETTLEMENT_INDEX,
                    null,
                    ContentValues().apply {
                        put("index_id", r.indexId)
                        put("source_ts_ms", r.sourceTsMs)
                        put("value_usd", r.valueUsd)
                        put("final_minute_average_usd", r.finalMinuteAverageUsd)
                        put("final_minute_samples", r.finalMinuteSamples)
                        put("received_at_ms", r.receivedAtMs)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            // Four weeks at a 15-second cadence for three indices is < 500k rows.
            w.delete(TABLE_SETTLEMENT_INDEX, "source_ts_ms < ?", arrayOf((System.currentTimeMillis() - SETTLEMENT_INDEX_RETENTION_MS).toString()))
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    override fun insertForwardTests(rows: List<ForwardTestRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues().apply {
                    put("ticker", r.ticker); put("series", r.series); put("captured_at_ms", r.capturedAtMs)
                    put("model_yes", r.modelYes); put("market_yes", r.marketYes); put("side", r.side)
                    put("book_ask", r.bookAsk); put("size_at_ask", r.sizeAtAsk)
                    put("contracts", r.contracts); put("all_in_usd", r.allInUsd); put("fee_usd", r.feeUsd)
                    put("quote_qualified", if (r.quoteQualified) 1 else 0)
                }
                w.insertWithOnConflict(TABLE_FORWARD, null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            w.execSQL("DELETE FROM $TABLE_FORWARD WHERE ticker NOT IN " +
                "(SELECT ticker FROM $TABLE_FORWARD ORDER BY captured_at_ms DESC LIMIT $MAX_FORWARD)")
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    override fun forwardTests(limit: Int): List<ForwardTestRow> {
        val out = ArrayList<ForwardTestRow>()
        db.readableDatabase.rawQuery(
            "SELECT f.*, s.result AS outcome FROM $TABLE_FORWARD f " +
                "LEFT JOIN $TABLE_SETTLED s ON s.ticker = f.ticker " +
                "ORDER BY f.captured_at_ms DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, MAX_FORWARD).toString())
        ).use { c ->
            while (c.moveToNext()) out.add(ForwardTestRow(
                ticker = c.str("ticker"), series = c.str("series"),
                capturedAtMs = c.long("captured_at_ms"), modelYes = c.dbl("model_yes"),
                marketYes = c.dbl("market_yes"), side = c.str("side"),
                bookAsk = c.dblOrNull("book_ask"), sizeAtAsk = c.dblOrNull("size_at_ask"),
                contracts = c.intOrNull("contracts"), allInUsd = c.dblOrNull("all_in_usd"),
                feeUsd = c.dblOrNull("fee_usd"), quoteQualified = c.long("quote_qualified") == 1L,
                outcome = c.strOrNull("outcome")
            ))
        }
        return out
    }

    override fun insertTicketForward(rows: List<TicketForwardRow>) {
        if (rows.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues().apply {
                    put("ticker", r.ticker); put("series", r.series); put("captured_at_ms", r.capturedAtMs)
                    put("build_code", r.buildCode); put("kind", r.kind); put("model_source", r.modelSource)
                    put("side", r.side); put("model_yes", r.modelYes); put("market_yes", r.marketYes)
                    put("ask", r.ask); put("visible_contracts", r.visibleContracts)
                    put("contracts", r.contracts); put("all_in_usd", r.allInUsd)
                    put("fee_usd", r.feeUsd); put("fee_rate", r.feeRate)
                    put("modeled_net_usd", r.modeledNetUsd)
                }
                w.insertWithOnConflict(TABLE_TICKET_FORWARD, null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            w.execSQL("DELETE FROM $TABLE_TICKET_FORWARD WHERE ticker NOT IN " +
                "(SELECT ticker FROM $TABLE_TICKET_FORWARD ORDER BY captured_at_ms DESC LIMIT $MAX_FORWARD)")
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    override fun ticketForward(limit: Int): List<TicketForwardRow> {
        val out = ArrayList<TicketForwardRow>()
        db.readableDatabase.rawQuery(
            "SELECT f.*, s.result AS outcome FROM $TABLE_TICKET_FORWARD f " +
                "LEFT JOIN $TABLE_SETTLED s ON s.ticker = f.ticker " +
                "ORDER BY f.captured_at_ms DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, MAX_FORWARD).toString())
        ).use { c ->
            while (c.moveToNext()) out.add(TicketForwardRow(
                ticker = c.str("ticker"), series = c.str("series"),
                capturedAtMs = c.long("captured_at_ms"), buildCode = c.intOrNull("build_code") ?: 0,
                kind = c.str("kind"), modelSource = c.str("model_source"), side = c.str("side"),
                modelYes = c.dbl("model_yes"), marketYes = c.dbl("market_yes"), ask = c.dbl("ask"),
                visibleContracts = c.dbl("visible_contracts"), contracts = c.intOrNull("contracts") ?: 0,
                allInUsd = c.dbl("all_in_usd"), feeUsd = c.dbl("fee_usd"), feeRate = c.dbl("fee_rate"),
                modeledNetUsd = c.dbl("modeled_net_usd"), outcome = c.strOrNull("outcome")
            ))
        }
        return out
    }

    override fun recentSnapshots(limit: Int): List<ScoredSnapshotRow> =
        query(TABLE_SNAP, limit) { cursorToSnap(it) }

    override fun recentAlerts(limit: Int): List<AlertRow> =
        query(TABLE_ALERT, limit) { cursorToAlert(it) }

    override fun recentScorecards(limit: Int): List<ScorecardRow> =
        query(TABLE_CARD, limit) { cursorToCard(it) }

    override fun recentTickets(limit: Int): List<TicketAttemptRow> =
        query(TABLE_TICKET, limit) { cursorToTicket(it) }

    override fun recentOddsMids(limit: Int): List<OddsMidRow> =
        query(TABLE_ODDS, limit) { cursorToOdds(it) }

    override fun settlementIndexRows(limit: Int): List<SettlementIndexRow> {
        val out = ArrayList<SettlementIndexRow>()
        db.readableDatabase.query(
            TABLE_SETTLEMENT_INDEX,
            null,
            null,
            null,
            null,
            null,
            "source_ts_ms DESC",
            limit.coerceIn(1, MAX_SETTLEMENT_INDEX_EXPORT).toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += SettlementIndexRow(
                    indexId = c.str("index_id"),
                    sourceTsMs = c.long("source_ts_ms"),
                    valueUsd = c.dbl("value_usd"),
                    finalMinuteAverageUsd = c.dblOrNull("final_minute_average_usd"),
                    finalMinuteSamples = c.intOrNull("final_minute_samples") ?: 0,
                    receivedAtMs = c.long("received_at_ms")
                )
            }
        }
        return out.asReversed()
    }

    override fun exportBundle(limit: Int): ResultsBundle = ResultsBundle(
        snapshots = recentSnapshots(limit),
        alerts = recentAlerts(limit),
        scorecards = recentScorecards(limit),
        tickets = recentTickets(limit),
        settlementIndex = settlementIndexRows(limit.coerceAtMost(50_000))
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
                        put("source", r.source)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            w.execSQL(
                "DELETE FROM $TABLE_CHART WHERE id NOT IN (SELECT id FROM $TABLE_CHART ORDER BY t_ms DESC LIMIT $MAX_CHART)"
            )
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun chartTicks(
        ticker: String,
        startMs: Long,
        endMs: Long,
        limit: Int
    ): List<com.dirk.kalshiodds.data.local.archive.ChartTickRow> {
        val out = ArrayList<com.dirk.kalshiodds.data.local.archive.ChartTickRow>(limit)
        db.readableDatabase.query(
            TABLE_CHART,
            null,
            "ticker = ? AND t_ms >= ? AND t_ms <= ?",
            arrayOf(ticker, startMs.toString(), endMs.toString()),
            null,
            null,
            "t_ms ASC",
            limit.coerceIn(1, 2_000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    com.dirk.kalshiodds.data.local.archive.ChartTickRow(
                        ticker = c.str("ticker"),
                        tMs = c.long("t_ms"),
                        yesBid = c.dblOrNull("yes_bid"),
                        noBid = c.dblOrNull("no_bid"),
                        yesAsk = c.dblOrNull("yes_ask"),
                        noAsk = c.dblOrNull("no_ask"),
                        spotUsd = c.dblOrNull("spot_usd"),
                        source = c.strOrNull("source") ?: com.dirk.kalshiodds.data.local.archive.ChartTickRow.SOURCE_LIVE
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

    override fun existingFillIds(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT fill_id FROM $TABLE_FILL", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    override fun existingSnapshotKeys(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT ticker, created_at_ms FROM $TABLE_SNAP", null).use { c ->
            while (c.moveToNext()) out.add("${c.getString(0)}|${c.getLong(1)}")
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
            createOddsTable(db)
            createArchiveTables(db)
            createHistoryTables(db)
            createChartTickTable(db)
            createForwardTable(db)
            createTicketForwardTable(db)
            createSettlementIndexTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createOddsTable(db)
            if (oldVersion < 3) {
                addBidColumns(db)
                createArchiveTables(db)
            }
            if (oldVersion < 4) createHistoryTables(db)
            if (oldVersion < 5) createChartTickTable(db)
            if (oldVersion < 6) createForwardTable(db)
            if (oldVersion < 7) createTicketForwardTable(db)
            if (oldVersion < 8) createSettlementIndexTable(db)
        }

        private fun createForwardTable(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_FORWARD (
                    ticker TEXT PRIMARY KEY, series TEXT NOT NULL, captured_at_ms INTEGER NOT NULL,
                    model_yes REAL NOT NULL, market_yes REAL NOT NULL, side TEXT NOT NULL,
                    book_ask REAL, size_at_ask REAL, contracts INTEGER, all_in_usd REAL,
                    fee_usd REAL, quote_qualified INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_forward_time ON $TABLE_FORWARD(captured_at_ms)")
        }

        private fun createTicketForwardTable(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_TICKET_FORWARD (
                    ticker TEXT PRIMARY KEY, series TEXT NOT NULL, captured_at_ms INTEGER NOT NULL,
                    build_code INTEGER NOT NULL, kind TEXT NOT NULL, model_source TEXT NOT NULL,
                    side TEXT NOT NULL, model_yes REAL NOT NULL, market_yes REAL NOT NULL,
                    ask REAL NOT NULL, visible_contracts REAL NOT NULL, contracts INTEGER NOT NULL,
                    all_in_usd REAL NOT NULL, fee_usd REAL NOT NULL, fee_rate REAL NOT NULL,
                    modeled_net_usd REAL NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ticket_forward_time ON $TABLE_TICKET_FORWARD(captured_at_ms)")
        }

        private fun createSettlementIndexTable(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_SETTLEMENT_INDEX (
                    index_id TEXT NOT NULL,
                    source_ts_ms INTEGER NOT NULL,
                    value_usd REAL NOT NULL,
                    final_minute_average_usd REAL,
                    final_minute_samples INTEGER NOT NULL,
                    received_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(index_id, source_ts_ms)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_index_time ON $TABLE_SETTLEMENT_INDEX(source_ts_ms)")
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
        const val DB_VERSION = 8
        const val TABLE_SETTINGS = "settings_history"
        const val TABLE_SESSION = "sessions"
        const val MAX_SETTINGS = 400
        const val TABLE_SNAP = "scored_snapshots"
        const val TABLE_ALERT = "alerts_fired"
        const val TABLE_CARD = "scorecard_rows"
        const val TABLE_TICKET = "ticket_attempts"
        const val TABLE_ODDS = "odds_mids"
        const val TABLE_SETTLED = "settled_windows"
        const val TABLE_PATH = "price_path"
        const val TABLE_SPOT = "spot_candles"
        const val TABLE_FILL = "imported_fills"
        const val TABLE_CURSOR = "backfill_cursor"
        const val TABLE_CHART = com.dirk.kalshiodds.data.local.chart.ChartTickSchema.TABLE
        const val TABLE_FORWARD = "forward_test"
        const val TABLE_TICKET_FORWARD = "ticket_forward_test"
        const val TABLE_SETTLEMENT_INDEX = "settlement_index"
        const val MAX_FORWARD = 5_000
        const val MAX_SNAP = 1_200
        const val MAX_ALERT = 400
        const val MAX_CARD = 600
        const val MAX_TICKET = 300
        const val MAX_ODDS = 2_400
        const val MAX_PATH = 8_000
        const val MAX_SPOT = 12_000
        const val MAX_CHART = 2_880
        const val MAX_SETTLEMENT_INDEX_EXPORT = 500_000
        const val SETTLEMENT_INDEX_RETENTION_MS = 28L * 24L * 60L * 60L * 1_000L
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
