package com.dirk.kalshiodds.data.local.paper

/**
 * Additive 0.3.16 paper-fill table. New AI % / source columns are nullable
 * so pre-0.3.16 rows (or a JSON restore without those keys) stay loadable.
 * CREATE TABLE / INDEX / ALTER only — never DROP.
 */
object PaperFillSchema {
    const val TABLE = "paper_fills"
    const val FROM_VERSION = 5
    const val VERSION = 6

    val CREATE = """
        CREATE TABLE IF NOT EXISTS $TABLE (
          fill_id TEXT PRIMARY KEY,
          ticker TEXT,
          side TEXT,
          stake_usd REAL,
          contracts INTEGER,
          limit_price REAL,
          source TEXT,
          created_at_ms INTEGER,
          settled INTEGER,
          outcome TEXT,
          won INTEGER,
          pnl_usd REAL,
          note TEXT,
          win_target_usd REAL,
          ai_pct REAL,
          ai_confidence REAL,
          market_pct REAL,
          pick_source TEXT,
          kelly_f REAL,
          kelly_fraction REAL,
          bankroll_after_usd REAL
        )
    """.trimIndent()

    val INDEX = "CREATE INDEX IF NOT EXISTS idx_paper_fills_ticker ON $TABLE(ticker, created_at_ms)"

    /** Keep old rows: add the four new columns if a leaner table already exists. */
    val ADD_AI_PCT = "ALTER TABLE $TABLE ADD COLUMN ai_pct REAL"
    val ADD_AI_CONFIDENCE = "ALTER TABLE $TABLE ADD COLUMN ai_confidence REAL"
    val ADD_MARKET_PCT = "ALTER TABLE $TABLE ADD COLUMN market_pct REAL"
    val ADD_PICK_SOURCE = "ALTER TABLE $TABLE ADD COLUMN pick_source TEXT"
    val ADD_KELLY_F = "ALTER TABLE $TABLE ADD COLUMN kelly_f REAL"
    val ADD_KELLY_FRACTION = "ALTER TABLE $TABLE ADD COLUMN kelly_fraction REAL"
    val ADD_BANKROLL_AFTER = "ALTER TABLE $TABLE ADD COLUMN bankroll_after_usd REAL"

    fun upgradeSql(fromVersion: Int): List<String> {
        if (fromVersion >= VERSION) return emptyList()
        return listOf(CREATE, INDEX)
    }

    fun nullableColumnSql(): List<String> = listOf(
        ADD_AI_PCT, ADD_AI_CONFIDENCE, ADD_MARKET_PCT, ADD_PICK_SOURCE,
        ADD_KELLY_F, ADD_KELLY_FRACTION, ADD_BANKROLL_AFTER
    )

    fun isDestructive(sql: String): Boolean {
        val u = sql.trim().uppercase()
        return u.startsWith("DROP ") || u.contains("DROP TABLE") || u.contains("FALLBACKTODESTRUCTIVE")
    }
}
