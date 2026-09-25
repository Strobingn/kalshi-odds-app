package com.dirk.kalshiodds.data.local.archive

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.data.importing.ImportedFill
import com.dirk.kalshiodds.data.local.history.HistorySession
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.results.OddsMidRow

data class SettledWindowRow(
    val ticker: String,
    val series: String,
    val result: String,
    val strikeUsd: Double? = null,
    val openMs: Long? = null,
    val closeMs: Long? = null,
    val importedAtMs: Long = System.currentTimeMillis(),
    val source: String = "kalshi"
)

data class PricePathRow(
    val ticker: String,
    val tMs: Long,
    val yesBid: Double? = null,
    val noBid: Double? = null,
    val mid: Double? = null
)

data class SpotCandleRow(
    val product: String,
    val tMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class BackfillCursorRow(
    val job: String,
    val series: String,
    val cursor: String? = null,
    val minCloseMs: Long? = null,
    val lastTicker: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val status: String = "idle",
    val days: Int = 30,
    val processed: Int = 0
)

data class DataStats(
    val settledCount: Int = 0,
    val minCloseMs: Long? = null,
    val maxCloseMs: Long? = null,
    val btc: Int = 0,
    val eth: Int = 0,
    val sol: Int = 0,
    val other: Int = 0,
    val pathPoints: Int = 0,
    val spotCandles: Int = 0,
    val fills: Int = 0,
    val yesSettled: Int = 0,
    val noSettled: Int = 0
) {
    val dateRangeLabel: String
        get() = if (minCloseMs == null || maxCloseMs == null) {
            "no labeled windows yet"
        } else {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            "${fmt.format(java.util.Date(minCloseMs))} → ${fmt.format(java.util.Date(maxCloseMs))}"
        }
}

interface DataArchive {
    fun upsertSettled(rows: List<SettledWindowRow>)
    fun insertPricePath(rows: List<PricePathRow>)
    fun insertSpotCandles(rows: List<SpotCandleRow>)
    fun insertFills(rows: List<ImportedFill>)
    fun insertBidSnapshots(rows: List<OddsMidRow>)
    fun insertChartTicks(rows: List<ChartTickRow>)
    fun chartTicks(ticker: String, startMs: Long, endMs: Long, limit: Int = 240): List<ChartTickRow>
    fun trimChartTicks(keepTickers: Set<String>, olderThanMs: Long)
    fun bidHistory(ticker: String, sinceMs: Long, limit: Int = 400): List<BidPoint>
    fun pricePath(ticker: String, limit: Int = 180): List<PricePathRow>
    fun spotCloses(product: String, startMs: Long, endMs: Long): List<Double>
    fun stats(): DataStats
    fun settledTickers(): Set<String>
    fun recentSettled(series: String? = null, limit: Int = 8): List<SettledWindowRow>
    fun existingFillIds(): Set<String>
    fun existingSnapshotKeys(): Set<String>
    fun existingAlertIds(): Set<String>
    fun existingTicketKeys(): Set<String>
    fun readCursor(job: String): BackfillCursorRow?
    fun writeCursor(row: BackfillCursorRow)
    fun clearCursor(job: String)
    fun insertSettingsChange(row: SettingsChange)
    fun recentSettingsChanges(limit: Int, offset: Int = 0): List<SettingsChange>
    fun insertSession(row: HistorySession)
    fun closeSession(id: String, endedAtMs: Long, markets: Int = 0, signals: Int = 0, bets: Int = 0, pnlUsd: Double? = null)
    fun recentSessions(limit: Int): List<HistorySession>
}

object ArchiveJobs {
    const val KALSHI = "kalshi-15m"
    const val SPOT = "coinbase-spot"
    const val SUPABASE = "supabase-restore"
}
