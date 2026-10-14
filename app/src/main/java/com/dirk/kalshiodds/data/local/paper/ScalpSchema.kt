package com.dirk.kalshiodds.data.local.paper

/**
 * Paper-scalp tables. Additive only — never DROP / never ALTER existing columns away.
 * 0.3.38: scalp_trades. 0.3.67: synced + scalper_id indexes, scalp_stats, scalp_daily.
 */
object ScalpSchema {
    const val TABLE = "scalp_trades"
    const val STATS = "scalp_stats"
    const val DAILY = "scalp_daily"
    const val FROM_VERSION = 7
    const val VERSION = 10

    val CREATE = """
        CREATE TABLE IF NOT EXISTS $TABLE (
          id TEXT PRIMARY KEY,
          ticker TEXT NOT NULL,
          side TEXT NOT NULL,
          state TEXT NOT NULL,
          signal_at_ms INTEGER NOT NULL,
          signal_ask REAL,
          fair_at_signal REAL,
          contracts INTEGER,
          entry_price REAL,
          entry_fee_usd REAL,
          entry_at_ms INTEGER,
          exit_decided_at_ms INTEGER,
          exit_reason TEXT,
          sold_contracts INTEGER,
          proceeds_usd REAL,
          exit_fee_usd REAL,
          closed_at_ms INTEGER,
          net_usd REAL,
          note TEXT,
          rule_version TEXT
        )
    """.trimIndent()

    val INDEX = "CREATE INDEX IF NOT EXISTS idx_scalp_ticker ON $TABLE(ticker, signal_at_ms)"
    /** 0.3.67: backfill columns via ALTER (IF NOT EXISTS not available for columns — ignore duplicate). */
    val COL_SYNCED = "ALTER TABLE $TABLE ADD COLUMN synced INTEGER NOT NULL DEFAULT 0"
    val COL_SCALPER = "ALTER TABLE $TABLE ADD COLUMN scalper_id TEXT"
    val IDX_SCALPER = "CREATE INDEX IF NOT EXISTS idx_scalp_scalper_closed ON $TABLE(scalper_id, closed_at_ms DESC)"
    val IDX_SYNCED = "CREATE INDEX IF NOT EXISTS idx_scalp_synced ON $TABLE(synced, closed_at_ms)"
    val IDX_STATE = "CREATE INDEX IF NOT EXISTS idx_scalp_state ON $TABLE(state)"

    val CREATE_STATS = """
        CREATE TABLE IF NOT EXISTS $STATS (
          scalper_id TEXT PRIMARY KEY,
          n INTEGER NOT NULL DEFAULT 0,
          wins INTEGER NOT NULL DEFAULT 0,
          net_usd REAL NOT NULL DEFAULT 0,
          fees_usd REAL NOT NULL DEFAULT 0,
          best_usd REAL,
          worst_usd REAL,
          sum_sq REAL NOT NULL DEFAULT 0,
          last_closed_at_ms INTEGER,
          open_n INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    val CREATE_DAILY = """
        CREATE TABLE IF NOT EXISTS $DAILY (
          scalper_id TEXT NOT NULL,
          day_et TEXT NOT NULL,
          n INTEGER NOT NULL DEFAULT 0,
          wins INTEGER NOT NULL DEFAULT 0,
          net_usd REAL NOT NULL DEFAULT 0,
          fees_usd REAL NOT NULL DEFAULT 0,
          PRIMARY KEY (scalper_id, day_et)
        )
    """.trimIndent()

    fun upgradeSql(fromVersion: Int): List<String> {
        if (fromVersion >= VERSION) return emptyList()
        val out = ArrayList<String>()
        if (fromVersion < 8) { out += CREATE; out += INDEX }
        // 0.3.67 columns/indexes/stats (safe to re-run: CREATE IF NOT EXISTS; ALTER may throw once — caller ignores).
        out += COL_SYNCED; out += COL_SCALPER; out += IDX_SCALPER; out += IDX_SYNCED; out += IDX_STATE
        out += CREATE_STATS; out += CREATE_DAILY
        return out
    }
}
