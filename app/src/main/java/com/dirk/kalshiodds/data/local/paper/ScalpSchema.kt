package com.dirk.kalshiodds.data.local.paper

/**
 * Paper-scalp tables. Additive only — never DROP / never ALTER existing columns away.
 * 0.3.38: scalp_trades. 0.3.67: synced + scalper_id + scalp_stats + scalp_daily.
 * 0.3.68: avg-win/loss/cost/stop-saved columns, scalp_exits, scalp_windows.
 */
object ScalpSchema {
    const val TABLE = "scalp_trades"
    const val STATS = "scalp_stats"
    const val DAILY = "scalp_daily"
    const val EXITS = "scalp_exits"
    const val WINDOWS = "scalp_windows"
    const val FROM_VERSION = 7
    const val VERSION = 11

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
          open_n INTEGER NOT NULL DEFAULT 0,
          sum_win REAL NOT NULL DEFAULT 0,
          sum_loss REAL NOT NULL DEFAULT 0,
          n_loss INTEGER NOT NULL DEFAULT 0,
          neg_sum REAL NOT NULL DEFAULT 0,
          neg_n INTEGER NOT NULL DEFAULT 0,
          cost_sum REAL NOT NULL DEFAULT 0,
          stop_saved_sum REAL NOT NULL DEFAULT 0,
          stop_saved_n INTEGER NOT NULL DEFAULT 0,
          no_fills INTEGER NOT NULL DEFAULT 0
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
          sum_sq REAL NOT NULL DEFAULT 0,
          sum_win REAL NOT NULL DEFAULT 0,
          sum_loss REAL NOT NULL DEFAULT 0,
          n_loss INTEGER NOT NULL DEFAULT 0,
          neg_sum REAL NOT NULL DEFAULT 0,
          neg_n INTEGER NOT NULL DEFAULT 0,
          cost_sum REAL NOT NULL DEFAULT 0,
          best_usd REAL,
          worst_usd REAL,
          PRIMARY KEY (scalper_id, day_et)
        )
    """.trimIndent()

    // 0.3.68 columns (ALTER ADD; caller ignores "duplicate column")
    val STATS_COLS = listOf(
        "ALTER TABLE $STATS ADD COLUMN sum_win REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN sum_loss REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN n_loss INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN neg_sum REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN neg_n INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN cost_sum REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN stop_saved_sum REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN stop_saved_n INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE $STATS ADD COLUMN no_fills INTEGER NOT NULL DEFAULT 0",
    )
    val DAILY_COLS = listOf(
        "ALTER TABLE $DAILY ADD COLUMN sum_sq REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $DAILY ADD COLUMN sum_win REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $DAILY ADD COLUMN sum_loss REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $DAILY ADD COLUMN n_loss INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE $DAILY ADD COLUMN neg_sum REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $DAILY ADD COLUMN neg_n INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE $DAILY ADD COLUMN cost_sum REAL NOT NULL DEFAULT 0",
        "ALTER TABLE $DAILY ADD COLUMN best_usd REAL",
        "ALTER TABLE $DAILY ADD COLUMN worst_usd REAL",
    )
    val CREATE_EXITS = """
        CREATE TABLE IF NOT EXISTS $EXITS (
          scalper_id TEXT NOT NULL,
          reason TEXT NOT NULL,
          n INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY (scalper_id, reason)
        )
    """.trimIndent()
    val CREATE_WINDOWS = """
        CREATE TABLE IF NOT EXISTS $WINDOWS (
          window_key TEXT NOT NULL,
          scalper_id TEXT NOT NULL,
          n INTEGER NOT NULL DEFAULT 0,
          net_usd REAL NOT NULL DEFAULT 0,
          close_ms INTEGER,
          PRIMARY KEY (window_key, scalper_id)
        )
    """.trimIndent()
    val IDX_WIN_CLOSE = "CREATE INDEX IF NOT EXISTS idx_scalp_win_close ON $WINDOWS(close_ms DESC)"

    /** Per-row aggregate generation: rows with agg_v < AGG_V are (re)counted by the background indexer. */
    const val AGG_V = 2
    val COL_AGG = "ALTER TABLE $TABLE ADD COLUMN agg_v INTEGER NOT NULL DEFAULT 0"
    val IDX_AGG = "CREATE INDEX IF NOT EXISTS idx_scalp_agg ON $TABLE(agg_v)"

    /**
     * 0.3.68 one-time on v10 → v11: MOVE (rename, never drop) the 0.3.67 aggregate tables aside; fresh tables are then
     * rebuilt from every trade row by the background indexer (agg_v = 0 on all rows).
     */
    fun moveV10Sql(): List<String> = listOf(
        "ALTER TABLE $STATS RENAME TO ${STATS}_v0367",
        "ALTER TABLE $DAILY RENAME TO ${DAILY}_v0367"
    )

    fun upgradeSql(fromVersion: Int): List<String> {
        if (fromVersion >= VERSION) return emptyList()
        val out = ArrayList<String>()
        if (fromVersion < 8) { out += CREATE; out += INDEX }
        out += COL_SYNCED; out += COL_SCALPER; out += IDX_SCALPER; out += IDX_SYNCED; out += IDX_STATE
        out += CREATE_STATS; out += CREATE_DAILY
        out += STATS_COLS; out += DAILY_COLS
        out += CREATE_EXITS; out += CREATE_WINDOWS; out += IDX_WIN_CLOSE
        out += COL_AGG; out += IDX_AGG
        return out
    }
}
