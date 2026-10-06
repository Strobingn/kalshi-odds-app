package com.dirk.kalshiodds.data.local.ledger

/**
 * Prediction ledger: one row per decision the app makes (BET or NO BET),
 * with the raw/calibrated/final/market probabilities, the book snapshot,
 * the settlement source, and the outcome once Kalshi settles the market.
 *
 * Lives in diphunter_results.db (SQLiteOpenHelper, the app's existing
 * persistence; the app has no Room dependency). Additive only: CREATE
 * TABLE / INDEX IF NOT EXISTS — never DROP, never destructive fallback.
 */
object PredictionLedgerSchema {
    const val TABLE = "prediction_ledger"
    const val FROM_VERSION = 6
    const val VERSION = 7

    enum class Type(val sql: String) { INT("INTEGER"), REAL("REAL"), TEXT("TEXT") }

    data class Column(val name: String, val type: Type)

    /** Order is the CSV order. `id` is separate. */
    val COLUMNS: List<Column> = listOf(
        Column("timestamp_ms", Type.INT),
        Column("model_version", Type.TEXT),
        Column("features_hash", Type.TEXT),
        Column("ticker", Type.TEXT),
        Column("series", Type.TEXT),
        Column("close_time_ms", Type.INT),
        Column("regime_key", Type.TEXT),
        Column("calibration_level", Type.TEXT),
        Column("settlement_rule", Type.TEXT),
        Column("settlement_source", Type.TEXT),
        Column("seconds_remaining", Type.REAL),
        Column("spot", Type.REAL),
        Column("target_strike", Type.REAL),
        Column("z_distance", Type.REAL),
        Column("z_settle", Type.REAL),
        Column("raw_model_prob", Type.REAL),
        Column("calibrated_prob", Type.REAL),
        Column("final_prob", Type.REAL),
        Column("market_mid", Type.REAL),
        Column("side", Type.TEXT),
        Column("bid", Type.REAL),
        Column("ask", Type.REAL),
        Column("yes_ask", Type.REAL),
        Column("no_ask", Type.REAL),
        Column("spread", Type.REAL),
        Column("depth_at_best", Type.REAL),
        Column("yes_depth", Type.REAL),
        Column("no_depth", Type.REAL),
        Column("imbalance", Type.REAL),
        Column("book_age_ms", Type.INT),
        Column("decision", Type.TEXT),
        Column("size_contracts", Type.REAL),
        Column("reason_codes", Type.TEXT),
        Column("settlement_result", Type.TEXT),
        Column("settled_at_ms", Type.INT),
        Column("passive", Type.INT),
        Column("order_price", Type.REAL),
        Column("depth_ahead", Type.REAL),
        Column("quote_churn", Type.REAL),
        Column("p_fill", Type.REAL),
        Column("expected_filled", Type.REAL),
        Column("expected_net", Type.REAL),
        Column("fill_would", Type.INT),
        Column("fill_size", Type.REAL),
        Column("time_to_fill_ms", Type.INT),
        Column("adverse_move", Type.REAL),
        Column("raw_pnl", Type.REAL),
        Column("executable_pnl", Type.REAL),
        Column("replay_executable", Type.INT),
        Column("cf_index_id", Type.TEXT),
        Column("cf_value", Type.REAL),
        Column("cf_avg_60s", Type.REAL),
        Column("cf_final_minute_avg", Type.REAL)
    )

    val CREATE: String = buildString {
        append("CREATE TABLE IF NOT EXISTS $TABLE (\n  id INTEGER PRIMARY KEY AUTOINCREMENT")
        COLUMNS.forEach { c ->
            append(",\n  ${c.name} ${c.type.sql}")
            if (c.name == "timestamp_ms" || c.name == "ticker") append(" NOT NULL")
        }
        append("\n)")
    }

    val INDEXES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS idx_prediction_ledger_ticker ON $TABLE(ticker, timestamp_ms)",
        "CREATE INDEX IF NOT EXISTS idx_prediction_ledger_settled ON $TABLE(settlement_result, timestamp_ms)"
    )

    fun upgradeSql(fromVersion: Int): List<String> {
        if (fromVersion >= VERSION) return emptyList()
        return listOf(CREATE) + INDEXES
    }

    fun isDestructive(sql: String): Boolean {
        val u = sql.trim().uppercase()
        return u.startsWith("DROP ") || u.contains("DROP TABLE") || u.contains("DELETE FROM") ||
            u.contains("FALLBACKTODESTRUCTIVE")
    }

    /** Column → value for insert. Booleans as 0/1. */
    fun values(row: LedgerRow): Map<String, Any?> = mapOf(
        "timestamp_ms" to row.timestampMs,
        "model_version" to row.modelVersion,
        "features_hash" to row.featuresHash,
        "ticker" to row.ticker,
        "series" to row.series,
        "close_time_ms" to row.closeTimeMs,
        "regime_key" to row.regimeKey,
        "calibration_level" to row.calibrationLevel,
        "settlement_rule" to row.settlementRule,
        "settlement_source" to row.settlementSource,
        "seconds_remaining" to row.secondsRemaining,
        "spot" to row.spot,
        "target_strike" to row.targetStrike,
        "z_distance" to row.zDistance,
        "z_settle" to row.zSettle,
        "raw_model_prob" to row.rawModelProb,
        "calibrated_prob" to row.calibratedProb,
        "final_prob" to row.finalProb,
        "market_mid" to row.marketMid,
        "side" to row.side,
        "bid" to row.bid,
        "ask" to row.ask,
        "yes_ask" to row.yesAsk,
        "no_ask" to row.noAsk,
        "spread" to row.spread,
        "depth_at_best" to row.depthAtBest,
        "yes_depth" to row.yesDepth,
        "no_depth" to row.noDepth,
        "imbalance" to row.imbalance,
        "book_age_ms" to row.bookAgeMs,
        "decision" to row.decision,
        "size_contracts" to row.sizeContracts,
        "reason_codes" to row.reasonCodes,
        "settlement_result" to row.settlementResult,
        "settled_at_ms" to row.settledAtMs,
        "passive" to row.passive?.let { if (it) 1 else 0 },
        "order_price" to row.orderPrice,
        "depth_ahead" to row.depthAhead,
        "quote_churn" to row.quoteChurn,
        "p_fill" to row.pFill,
        "expected_filled" to row.expectedFilled,
        "expected_net" to row.expectedNet,
        "fill_would" to row.fillWould?.let { if (it) 1 else 0 },
        "fill_size" to row.fillSize,
        "time_to_fill_ms" to row.timeToFillMs,
        "adverse_move" to row.adverseMove,
        "raw_pnl" to row.rawPnl,
        "executable_pnl" to row.executablePnl,
        "replay_executable" to row.replayExecutable?.let { if (it) 1 else 0 },
        "cf_index_id" to row.cfIndexId,
        "cf_value" to row.cfValue,
        "cf_avg_60s" to row.cfAvg60s,
        "cf_final_minute_avg" to row.cfFinalMinuteAvg
    )

    /** Build a row from a column reader (cursor or map). */
    fun fromReader(id: Long, get: (String) -> Any?): LedgerRow {
        fun d(n: String): Double? = (get(n) as? Number)?.toDouble()
        fun l(n: String): Long? = (get(n) as? Number)?.toLong()
        fun s(n: String): String? = get(n) as? String
        fun b(n: String): Boolean? = (get(n) as? Number)?.let { it.toInt() != 0 }
        return LedgerRow(
            id = id,
            timestampMs = l("timestamp_ms") ?: 0L,
            modelVersion = s("model_version"),
            ticker = s("ticker").orEmpty(),
            series = s("series"),
            settlementRule = s("settlement_rule"),
            settlementSource = s("settlement_source"),
            secondsRemaining = d("seconds_remaining"),
            spot = d("spot"),
            targetStrike = d("target_strike"),
            zDistance = d("z_distance"),
            rawModelProb = d("raw_model_prob"),
            calibratedProb = d("calibrated_prob"),
            marketMid = d("market_mid"),
            bid = d("bid"),
            ask = d("ask"),
            spread = d("spread"),
            depthAtBest = d("depth_at_best"),
            imbalance = d("imbalance"),
            bookAgeMs = l("book_age_ms"),
            decision = s("decision"),
            sizeContracts = d("size_contracts"),
            reasonCodes = s("reason_codes"),
            settlementResult = s("settlement_result"),
            settledAtMs = l("settled_at_ms"),
            passive = b("passive"),
            orderPrice = d("order_price"),
            depthAhead = d("depth_ahead"),
            quoteChurn = d("quote_churn"),
            pFill = d("p_fill"),
            expectedFilled = d("expected_filled"),
            expectedNet = d("expected_net"),
            fillWould = b("fill_would"),
            fillSize = d("fill_size"),
            timeToFillMs = l("time_to_fill_ms"),
            adverseMove = d("adverse_move"),
            rawPnl = d("raw_pnl"),
            executablePnl = d("executable_pnl"),
            replayExecutable = b("replay_executable"),
            cfIndexId = s("cf_index_id"),
            cfValue = d("cf_value"),
            cfAvg60s = d("cf_avg_60s"),
            cfFinalMinuteAvg = d("cf_final_minute_avg"),
            featuresHash = s("features_hash"),
            finalProb = d("final_prob"),
            side = s("side"),
            regimeKey = s("regime_key"),
            calibrationLevel = s("calibration_level"),
            zSettle = d("z_settle"),
            closeTimeMs = l("close_time_ms"),
            yesAsk = d("yes_ask"),
            noAsk = d("no_ask"),
            yesDepth = d("yes_depth"),
            noDepth = d("no_depth")
        )
    }
}

data class LedgerRow(
    val id: Long = 0L,
    val timestampMs: Long,
    val modelVersion: String?,
    val ticker: String,
    val series: String?,
    val settlementRule: String?,
    val settlementSource: String?,
    val secondsRemaining: Double?,
    val spot: Double?,
    val targetStrike: Double?,
    val zDistance: Double?,
    val rawModelProb: Double?,
    val calibratedProb: Double?,
    val marketMid: Double?,
    val bid: Double?,
    val ask: Double?,
    val spread: Double?,
    val depthAtBest: Double?,
    val imbalance: Double?,
    val bookAgeMs: Long?,
    val decision: String?,
    val sizeContracts: Double?,
    val reasonCodes: String?,
    val settlementResult: String? = null,
    val settledAtMs: Long? = null,
    val passive: Boolean? = null,
    val orderPrice: Double? = null,
    val depthAhead: Double? = null,
    val quoteChurn: Double? = null,
    val pFill: Double? = null,
    val expectedFilled: Double? = null,
    val expectedNet: Double? = null,
    val fillWould: Boolean? = null,
    val fillSize: Double? = null,
    val timeToFillMs: Long? = null,
    val adverseMove: Double? = null,
    val rawPnl: Double? = null,
    val executablePnl: Double? = null,
    val replayExecutable: Boolean? = null,
    val cfIndexId: String? = null,
    val cfValue: Double? = null,
    val cfAvg60s: Double? = null,
    val cfFinalMinuteAvg: Double? = null,
    val featuresHash: String? = null,
    val finalProb: Double? = null,
    val side: String? = null,
    val regimeKey: String? = null,
    val calibrationLevel: String? = null,
    val zSettle: Double? = null,
    val closeTimeMs: Long? = null,
    val yesAsk: Double? = null,
    val noAsk: Double? = null,
    val yesDepth: Double? = null,
    val noDepth: Double? = null
) {
    val outcomeYes: Boolean?
        get() = when (settlementResult?.lowercase()) {
            "yes" -> true
            "no" -> false
            else -> null
        }
}

object LedgerCsv {
    val HEADER: List<String> = listOf("id") + PredictionLedgerSchema.COLUMNS.map { it.name }

    fun render(rows: List<LedgerRow>): String {
        val sb = StringBuilder(HEADER.joinToString(",")).append('\n')
        rows.forEach { row ->
            val v = PredictionLedgerSchema.values(row)
            sb.append(row.id)
            PredictionLedgerSchema.COLUMNS.forEach { c -> sb.append(',').append(cell(v[c.name])) }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun cell(v: Any?): String {
        if (v == null) return ""
        val s = v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
