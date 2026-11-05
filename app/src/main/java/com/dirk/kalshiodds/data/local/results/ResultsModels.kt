package com.dirk.kalshiodds.data.local.results

/**
 * Durable rows written off the scoring thread. Survive process death.
 * Advisory only — ticket rows are never unsupervised bets.
 */
data class ScoredSnapshotRow(
    val id: Long = 0L,
    val ticker: String,
    val series: String,
    val side: String,
    val edgePp: Double,
    val fairPp: Double,
    val marketPp: Double,
    val regime: String?,
    val uncertainty: Double?,
    val createdAtMs: Long,
    val confidence: Double? = null,
    val tte: String? = null,
    val heavyMl: Boolean = false,
    val note: String? = null
)

data class AlertRow(
    val id: Long = 0L,
    val alertId: String,
    val ticker: String,
    val series: String,
    val side: String,
    val edgePp: Double,
    val fairPp: Double,
    val marketPp: Double,
    val reason: String,
    val regime: String?,
    val createdAtMs: Long
)

data class ScorecardRow(
    val id: Long = 0L,
    val ticker: String,
    val series: String,
    val outcome: String,
    val score: Int?,
    val brier: Double?,
    val edgePp: Double?,
    val policyRoi: Double?,
    val createdAtMs: Long,
    val note: String? = null
)

data class TicketAttemptRow(
    val id: Long = 0L,
    val ticker: String,
    val side: String,
    val stakeUsd: Double,
    val approved: Boolean,
    val result: String,
    val createdAtMs: Long,
    val clientOrderId: String? = null,
    val note: String? = null
)

data class OddsMidRow(
    val id: Long = 0L,
    val ticker: String,
    val mid01: Double,
    val createdAtMs: Long,
    val yesBid: Double? = null,
    val noBid: Double? = null
)

data class ResultsBundle(
    val snapshots: List<ScoredSnapshotRow> = emptyList(),
    val alerts: List<AlertRow> = emptyList(),
    val scorecards: List<ScorecardRow> = emptyList(),
    val tickets: List<TicketAttemptRow> = emptyList()
)

interface ResultsStore {
    fun insertSnapshots(rows: List<ScoredSnapshotRow>)
    fun insertAlert(row: AlertRow)
    fun insertScorecard(row: ScorecardRow)
    fun insertTicket(row: TicketAttemptRow)
    fun insertOddsMids(rows: List<OddsMidRow>)
    fun recentSnapshots(limit: Int = 80): List<ScoredSnapshotRow>
    fun recentAlerts(limit: Int = 40): List<AlertRow>
    fun recentScorecards(limit: Int = 80): List<ScorecardRow>
    fun recentTickets(limit: Int = 40): List<TicketAttemptRow>
    fun recentOddsMids(limit: Int = 800): List<OddsMidRow>
    fun exportBundle(limit: Int = 400): ResultsBundle

    /**
     * The cloud-sync key ([SyncKeys]) of every snapshot, alert and ticket already stored, so a sync can skip rows this
     * device has. Empty means "unknown": the sync then relies on the merge alone.
     */
    fun syncKeys(): Set<String> = emptySet()
}

/** Cloud-sync identities of stored rows; the same strings SupabaseSync.pack uses as record keys. */
object SyncKeys {
    fun snapshot(ticker: String, createdAtMs: Long) = "snapshot:$ticker:$createdAtMs"
    fun alert(alertId: String) = "alert:$alertId"
    fun ticket(ticker: String, createdAtMs: Long, side: String) = "ticket:$ticker:$createdAtMs:$side"
}
