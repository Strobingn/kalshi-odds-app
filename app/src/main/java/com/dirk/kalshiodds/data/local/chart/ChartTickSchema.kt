package com.dirk.kalshiodds.data.local.chart

/**
 * Additive 0.3.9 chart-tick table. CREATE TABLE / INDEX only — never DROP.
 */
object ChartTickSchema {
    const val TABLE = "chart_ticks"
    const val FROM_VERSION = 4
    const val VERSION = 5

    val CREATE = """
        CREATE TABLE IF NOT EXISTS $TABLE (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          ticker TEXT NOT NULL,
          t_ms INTEGER NOT NULL,
          yes_bid REAL,
          no_bid REAL,
          yes_ask REAL,
          no_ask REAL,
          spot_usd REAL,
          source TEXT,
          UNIQUE(ticker, t_ms)
        )
    """.trimIndent()

    val INDEX = "CREATE INDEX IF NOT EXISTS idx_chart_ticks_ticker ON $TABLE(ticker, t_ms)"

    fun upgradeSql(fromVersion: Int): List<String> {
        if (fromVersion >= VERSION) return emptyList()
        return listOf(CREATE, INDEX)
    }

    fun isDestructive(sql: String): Boolean {
        val u = sql.trim().uppercase()
        return u.startsWith("DROP ") || u.contains("DROP TABLE") || u.contains("FALLBACKTODESTRUCTIVE")
    }
}
