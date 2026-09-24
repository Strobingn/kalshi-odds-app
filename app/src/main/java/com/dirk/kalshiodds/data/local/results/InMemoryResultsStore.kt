package com.dirk.kalshiodds.data.local.results

import java.util.concurrent.atomic.AtomicLong

/**
 * JVM-safe store used by unit tests and as a fallback when SQLite is
 * unavailable. Same contract as [SqliteResultsStore].
 */
class InMemoryResultsStore(
    private val maxSnapshots: Int = 800,
    private val maxAlerts: Int = 200,
    private val maxScorecards: Int = 400,
    private val maxTickets: Int = 200
) : ResultsStore {
    private val nextId = AtomicLong(1L)
    private val snapshots = ArrayDeque<ScoredSnapshotRow>()
    private val alerts = ArrayDeque<AlertRow>()
    private val scorecards = ArrayDeque<ScorecardRow>()
    private val tickets = ArrayDeque<TicketAttemptRow>()

    @Synchronized
    override fun insertSnapshots(rows: List<ScoredSnapshotRow>) {
        for (row in rows) {
            snapshots.addLast(row.copy(id = nextId.getAndIncrement()))
            while (snapshots.size > maxSnapshots) snapshots.removeFirst()
        }
    }

    @Synchronized
    override fun insertAlert(row: AlertRow) {
        alerts.addLast(row.copy(id = nextId.getAndIncrement()))
        while (alerts.size > maxAlerts) alerts.removeFirst()
    }

    @Synchronized
    override fun insertScorecard(row: ScorecardRow) {
        scorecards.addLast(row.copy(id = nextId.getAndIncrement()))
        while (scorecards.size > maxScorecards) scorecards.removeFirst()
    }

    @Synchronized
    override fun insertTicket(row: TicketAttemptRow) {
        tickets.addLast(row.copy(id = nextId.getAndIncrement()))
        while (tickets.size > maxTickets) tickets.removeFirst()
    }

    @Synchronized
    override fun recentSnapshots(limit: Int): List<ScoredSnapshotRow> =
        snapshots.toList().takeLast(limit).asReversed()

    @Synchronized
    override fun recentAlerts(limit: Int): List<AlertRow> =
        alerts.toList().takeLast(limit).asReversed()

    @Synchronized
    override fun recentScorecards(limit: Int): List<ScorecardRow> =
        scorecards.toList().takeLast(limit).asReversed()

    @Synchronized
    override fun recentTickets(limit: Int): List<TicketAttemptRow> =
        tickets.toList().takeLast(limit).asReversed()

    @Synchronized
    override fun exportBundle(limit: Int): ResultsBundle = ResultsBundle(
        snapshots = snapshots.toList().takeLast(limit).asReversed(),
        alerts = alerts.toList().takeLast(limit).asReversed(),
        scorecards = scorecards.toList().takeLast(limit).asReversed(),
        tickets = tickets.toList().takeLast(limit).asReversed()
    )
}
