package com.dirk.kalshiodds.data.local.history

/**
 * Additive History schema used by [com.dirk.kalshiodds.data.local.results.SqliteResultsStore].
 * 0.3.6 was DB v3. 0.3.7 is v4: only CREATE TABLE / INDEX — never DROP.
 */
object ArchiveSchema {
    const val V036 = 3
    const val CURRENT = 4

    const val SETTINGS_TABLE = "settings_history"
    const val SESSION_TABLE = "sessions"

    val CREATE_SETTINGS = """
        CREATE TABLE IF NOT EXISTS $SETTINGS_TABLE (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          created_at_ms INTEGER NOT NULL,
          key TEXT,
          old_value TEXT,
          new_value TEXT,
          snapshot_json TEXT
        )
    """.trimIndent()

    val CREATE_SESSIONS = """
        CREATE TABLE IF NOT EXISTS $SESSION_TABLE (
          id TEXT PRIMARY KEY,
          started_at_ms INTEGER NOT NULL,
          ended_at_ms INTEGER,
          markets INTEGER,
          signals INTEGER,
          bets INTEGER,
          pnl_usd REAL
        )
    """.trimIndent()

    val INDEX_SETTINGS = "CREATE INDEX IF NOT EXISTS idx_settings_created ON $SETTINGS_TABLE(created_at_ms)"

    fun upgradeSql(fromVersion: Int): List<String> {
        if (fromVersion >= CURRENT) return emptyList()
        val out = ArrayList<String>()
        if (fromVersion < CURRENT) {
            out += CREATE_SETTINGS
            out += CREATE_SESSIONS
            out += INDEX_SETTINGS
        }
        return out
    }

    fun isDestructive(sql: String): Boolean {
        val u = sql.trim().uppercase()
        return u.startsWith("DROP ") || u.contains("DROP TABLE") || u.contains("FALLBACKTODESTRUCTIVE")
    }
}
