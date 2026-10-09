package com.dirk.kalshiodds.data.local.paper

/**
 * Additive 0.3.43 paper limit-order table (DB v9). CREATE TABLE / INDEX IF NOT EXISTS only — never DROP,
 * never ALTER an existing table. One JSON row per order (PaperOrder), keyed by id.
 */
object PaperOrderSchema {
    const val TABLE = "paper_orders"
    const val FROM_VERSION = 8
    const val VERSION = 9

    val CREATE = """
        CREATE TABLE IF NOT EXISTS $TABLE (
          id TEXT PRIMARY KEY,
          ticker TEXT NOT NULL,
          status TEXT NOT NULL,
          source TEXT,
          created_at_ms INTEGER NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          json TEXT NOT NULL
        )
    """.trimIndent()

    val INDEX = "CREATE INDEX IF NOT EXISTS idx_paper_orders_status ON $TABLE(status, ticker)"

    fun upgradeSql(fromVersion: Int): List<String> =
        if (fromVersion >= VERSION) emptyList() else listOf(CREATE, INDEX)
}
