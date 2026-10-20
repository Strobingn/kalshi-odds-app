package com.dirk.kalshiodds.signal.paper

/**
 * Pure ledger aggregates for the Scalp screen. Only non-void settled fills
 * contribute to P&L, so an open position never looks like a completed win.
 */
object ScalpAnalytics {
    data class Bucket(
        val key: String,
        val label: String,
        val settled: Int,
        val open: Int,
        val avgNetPerContractUsd: Double?,
        val totalNetUsd: Double
    )

    fun byEntryPrice(fills: List<PaperFill>): List<Bucket> = grouped(
        fills = fills,
        definitions = listOf(
            "10-30" to "10–30¢",
            "30-50" to "30–50¢",
            "50-70" to "50–70¢",
            "70-90" to "70–<90¢"
        )
    ) { fill ->
        when {
            fill.limitPrice < 0.10 || fill.limitPrice >= 0.90 -> null // legacy/out-of-policy rows
            fill.limitPrice < 0.30 -> "10-30"
            fill.limitPrice < 0.50 -> "30-50"
            fill.limitPrice < 0.70 -> "50-70"
            else -> "70-90"
        }
    }

    fun byTimeRemaining(fills: List<PaperFill>): List<Bucket> = grouped(
        fills = fills,
        definitions = listOf(
            "12-15" to "12–15m",
            "9-12" to "9–12m",
            "6-9" to "6–9m",
            "4-6" to "4–6m",
            "under4" to "<4m"
        )
    ) { fill ->
        val seconds = fill.strategyTimeToCloseSec ?: return@grouped null
        when {
            seconds >= 12 * 60L -> "12-15"
            seconds >= 9 * 60L -> "9-12"
            seconds >= 6 * 60L -> "6-9"
            seconds >= 4 * 60L -> "4-6"
            else -> "under4"
        }
    }

    fun byEntryEdge(fills: List<PaperFill>): List<Bucket> = grouped(
        fills = fills,
        definitions = listOf(
            "0-5" to "0–5pp",
            "5-10" to "5–10pp",
            "10-20" to "10–20pp",
            "20plus" to "20+pp"
        )
    ) { fill ->
        val edge = fill.strategyEntryEdgePp ?: return@grouped null
        when {
            edge < 5.0 -> "0-5"
            edge < 10.0 -> "5-10"
            edge < 20.0 -> "10-20"
            else -> "20plus"
        }
    }

    private fun grouped(
        fills: List<PaperFill>,
        definitions: List<Pair<String, String>>,
        keyOf: (PaperFill) -> String?
    ): List<Bucket> = definitions.map { (key, label) ->
        val rows = fills.filter { keyOf(it) == key }
        val settled = rows.filter { it.settled && it.outcome != "void" && it.pnlUsd != null && it.contracts > 0 }
        val total = settled.sumOf { it.pnlUsd ?: 0.0 }
        val contracts = settled.sumOf { it.contracts }
        Bucket(
            key = key,
            label = label,
            settled = settled.size,
            open = rows.count { !it.settled },
            avgNetPerContractUsd = if (contracts > 0) total / contracts.toDouble() else null,
            totalNetUsd = total
        )
    }
}
