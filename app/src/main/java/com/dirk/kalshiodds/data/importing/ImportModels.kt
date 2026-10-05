package com.dirk.kalshiodds.data.importing

import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.ScorecardRow
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow

data class ImportSummary(
    val kind: String,
    val imported: Int = 0,
    val skipped: Int = 0,
    val snapshots: Int = 0,
    val alerts: Int = 0,
    val scorecards: Int = 0,
    val tickets: Int = 0,
    val fills: Int = 0,
    val settled: Int = 0,
    val minTsMs: Long? = null,
    val maxTsMs: Long? = null,
    val errors: List<String> = emptyList(),
    val message: String = ""
) {
    val dateRangeLabel: String
        get() = when {
            minTsMs == null || maxTsMs == null -> "—"
            else -> "${formatDay(minTsMs)} → ${formatDay(maxTsMs)}"
        }

    fun plusRange(ts: Long?): ImportSummary {
        if (ts == null || ts <= 0L) return this
        val lo = minTsMs?.let { minOf(it, ts) } ?: ts
        val hi = maxTsMs?.let { maxOf(it, ts) } ?: ts
        return copy(minTsMs = lo, maxTsMs = hi)
    }

    companion object {
        fun formatDay(ms: Long): String {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = ms
            return String.format(
                java.util.Locale.US,
                "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}

data class ImportedFill(
    val id: String,
    val ticker: String,
    val side: String,
    val action: String? = null,
    val count: Double,
    val price: Double?,
    val createdAtMs: Long,
    val source: String
)

data class ImportBatch(
    val snapshots: List<ScoredSnapshotRow> = emptyList(),
    val alerts: List<AlertRow> = emptyList(),
    val scorecards: List<ScorecardRow> = emptyList(),
    val tickets: List<TicketAttemptRow> = emptyList(),
    val fills: List<ImportedFill> = emptyList(),
    val settledTickers: List<Pair<String, String>> = emptyList(),
    val settingsChanges: List<com.dirk.kalshiodds.data.local.history.SettingsChange> = emptyList(),
    val sessions: List<com.dirk.kalshiodds.data.local.history.HistorySession> = emptyList()
)

data class SeenKeys(
    val snapshots: MutableSet<String> = hashSetOf(),
    val alerts: MutableSet<String> = hashSetOf(),
    val scorecards: MutableSet<String> = hashSetOf(),
    val tickets: MutableSet<String> = hashSetOf(),
    val fills: MutableSet<String> = hashSetOf()
) {
    fun snapshotKey(ticker: String, ts: Long, side: String) = "$ticker|$ts|$side"
    fun alertKey(id: String, ticker: String, ts: Long) = id.ifBlank { "$ticker|$ts" }
    fun ticketKey(id: String?, ticker: String, ts: Long) = id?.takeIf { it.isNotBlank() } ?: "$ticker|$ts"
    fun fillKey(id: String) = id
}
