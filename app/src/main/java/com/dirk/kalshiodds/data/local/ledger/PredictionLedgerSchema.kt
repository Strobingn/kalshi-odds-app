package com.dirk.kalshiodds.data.local.ledger

/**
 * Additive prediction ledger. CREATE TABLE / INDEX only — never DROP.
 * Lives in diphunter_results.db so a version bump keeps paper fills
 * and never touches the Kalshi key (that key is not in this file).
 */
object PredictionLedgerSchema {
    const val TABLE = "prediction_ledger"
    const val FROM_VERSION = 6
    const val VERSION = 7

    val CREATE = """
        CREATE TABLE IF NOT EXISTS $TABLE (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          timestamp_ms INTEGER NOT NULL,
          model_version TEXT,
          ticker TEXT NOT NULL,
          series TEXT,
          settlement_rule TEXT,
          settlement_source TEXT,
          seconds_remaining REAL,
          spot REAL,
          target_strike REAL,
          z_distance REAL,
          raw_model_prob REAL,
          calibrated_prob REAL,
          market_mid REAL,
          bid REAL,
          ask REAL,
          spread REAL,
          depth_at_best REAL,
          imbalance REAL,
          book_age_ms INTEGER,
          decision TEXT,
          size_contracts REAL,
          reason_codes TEXT,
          settlement_result TEXT,
          settled_at_ms INTEGER,
          passive INTEGER,
          order_price REAL,
          depth_ahead REAL,
          quote_churn REAL,
          p_fill REAL,
          expected_filled REAL,
          expected_net REAL,
          fill_would INTEGER,
          fill_size REAL,
          time_to_fill_ms INTEGER,
          adverse_move REAL,
          raw_pnl REAL,
          executable_pnl REAL,
          replay_executable INTEGER,
          cf_index_id TEXT,
          cf_value REAL,
          cf_avg_60s REAL,
          cf_final_minute_avg REAL
        )
    """.trimIndent()

    val INDEX = "CREATE INDEX IF NOT EXISTS idx_prediction_ledger_ticker ON $TABLE(ticker, timestamp_ms)"

    fun upgradeSql(fromVersion: Int): List<String> {
        if (fromVersion >= VERSION) return emptyList()
        return listOf(CREATE, INDEX)
    }

    fun isDestructive(sql: String): Boolean {
        val u = sql.trim().uppercase()
        return u.startsWith("DROP ") || u.contains("DROP TABLE") || u.contains("FALLBACKTODESTRUCTIVE")
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
    val cfFinalMinuteAvg: Double? = null
)

object LedgerCsv {
    private val header = listOf(
        "timestamp_ms", "model_version", "ticker", "series", "settlement_rule", "settlement_source",
        "seconds_remaining", "spot", "target_strike", "z_distance", "raw_model_prob", "calibrated_prob",
        "market_mid", "bid", "ask", "spread", "depth_at_best", "imbalance", "book_age_ms",
        "decision", "size_contracts", "reason_codes", "settlement_result", "settled_at_ms",
        "passive", "order_price", "depth_ahead", "quote_churn", "p_fill", "expected_filled",
        "expected_net", "fill_would", "fill_size", "time_to_fill_ms", "adverse_move",
        "raw_pnl", "executable_pnl", "replay_executable", "cf_index_id", "cf_value",
        "cf_avg_60s", "cf_final_minute_avg"
    )

    fun render(rows: List<LedgerRow>): String {
        val body = rows.joinToString("\n") { row ->
            listOf(
                row.timestampMs, row.modelVersion, row.ticker, row.series, row.settlementRule,
                row.settlementSource, row.secondsRemaining, row.spot, row.targetStrike, row.zDistance,
                row.rawModelProb, row.calibratedProb, row.marketMid, row.bid, row.ask, row.spread,
                row.depthAtBest, row.imbalance, row.bookAgeMs, row.decision, row.sizeContracts,
                row.reasonCodes, row.settlementResult, row.settledAtMs, row.passive, row.orderPrice,
                row.depthAhead, row.quoteChurn, row.pFill, row.expectedFilled, row.expectedNet,
                row.fillWould, row.fillSize, row.timeToFillMs, row.adverseMove, row.rawPnl,
                row.executablePnl, row.replayExecutable, row.cfIndexId, row.cfValue, row.cfAvg60s,
                row.cfFinalMinuteAvg
            ).joinToString(",") { cell(it) }
        }
        return header.joinToString(",") + "\n" + body + if (rows.isEmpty()) "" else "\n"
    }

    private fun cell(v: Any?): String {
        if (v == null) return ""
        val s = v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
