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

/** Sampled CF Benchmarks settlement-index observation for later training. */
data class SettlementIndexRow(
    val indexId: String,
    val sourceTsMs: Long,
    val valueUsd: Double,
    val finalMinuteAverageUsd: Double? = null,
    val finalMinuteSamples: Int = 0,
    val receivedAtMs: Long
)

/** First qualifying live signal for a market, retained independently of rolling predictions. */
data class ForwardTestRow(
    val ticker: String,
    val series: String,
    val capturedAtMs: Long,
    val modelYes: Double,
    val marketYes: Double,
    val side: String,
    val bookAsk: Double?,
    val sizeAtAsk: Double?,
    val contracts: Int?,
    val allInUsd: Double?,
    val feeUsd: Double?,
    val quoteQualified: Boolean,
    val outcome: String? = null
)

/** First live, book-backed automatic ticket suggestion per market. Never an actual fill. */
data class TicketForwardRow(
    val ticker: String,
    val series: String,
    val capturedAtMs: Long,
    val buildCode: Int,
    val kind: String,
    val modelSource: String,
    val side: String,
    val modelYes: Double,
    val marketYes: Double,
    val ask: Double,
    val visibleContracts: Double,
    val contracts: Int,
    val allInUsd: Double,
    val feeUsd: Double,
    val feeRate: Double,
    val modeledNetUsd: Double,
    val outcome: String? = null
)

data class ResultsBundle(
    val snapshots: List<ScoredSnapshotRow> = emptyList(),
    val alerts: List<AlertRow> = emptyList(),
    val scorecards: List<ScorecardRow> = emptyList(),
    val tickets: List<TicketAttemptRow> = emptyList(),
    val settlementIndex: List<SettlementIndexRow> = emptyList()
)

interface ResultsStore {
    fun insertSnapshots(rows: List<ScoredSnapshotRow>)
    fun insertAlert(row: AlertRow)
    fun insertScorecard(row: ScorecardRow)
    fun insertTicket(row: TicketAttemptRow)
    fun insertOddsMids(rows: List<OddsMidRow>)
    fun insertSettlementIndex(rows: List<SettlementIndexRow>)
    fun insertForwardTests(rows: List<ForwardTestRow>)
    fun insertTicketForward(rows: List<TicketForwardRow>)
    fun recentSnapshots(limit: Int = 80): List<ScoredSnapshotRow>
    fun recentAlerts(limit: Int = 40): List<AlertRow>
    fun recentScorecards(limit: Int = 80): List<ScorecardRow>
    fun recentTickets(limit: Int = 40): List<TicketAttemptRow>
    fun recentOddsMids(limit: Int = 800): List<OddsMidRow>
    fun settlementIndexRows(limit: Int = 50_000): List<SettlementIndexRow>
    fun forwardTests(limit: Int = 5_000): List<ForwardTestRow>
    fun ticketForward(limit: Int = 5_000): List<TicketForwardRow>
    fun exportBundle(limit: Int = 400): ResultsBundle
}
