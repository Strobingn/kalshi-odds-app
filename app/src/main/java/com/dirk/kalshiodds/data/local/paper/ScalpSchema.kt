package com.dirk.kalshiodds.data.local.paper

/**
 * Additive 0.3.38 paper-scalp table (DB v8). CREATE TABLE / INDEX IF NOT EXISTS only — never DROP,
 * never ALTER an existing table. Older installs keep every row they had.
 */
object ScalpSchema {
    const val TABLE = "scalp_trades"
    const val FROM_VERSION = 7
    const val VERSION = 8

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

    fun upgradeSql(fromVersion: Int): List<String> =
        if (fromVersion >= VERSION) emptyList() else listOf(CREATE, INDEX)
}
