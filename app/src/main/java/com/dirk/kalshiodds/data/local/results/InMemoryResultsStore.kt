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
    private val maxTickets: Int = 200,
    private val maxOdds: Int = 1_200,
    private val maxChartTicks: Int = 2_880
) : ResultsStore, com.dirk.kalshiodds.data.local.archive.DataArchive {
    private val nextId = AtomicLong(1L)
    private val snapshots = ArrayDeque<ScoredSnapshotRow>()
    private val alerts = ArrayDeque<AlertRow>()
    private val scorecards = ArrayDeque<ScorecardRow>()
    private val tickets = ArrayDeque<TicketAttemptRow>()
    private val odds = ArrayDeque<OddsMidRow>()
    private val forward = LinkedHashMap<String, ForwardTestRow>()
    private val ticketForwardRows = LinkedHashMap<String, TicketForwardRow>()
    private val settled = LinkedHashMap<String, com.dirk.kalshiodds.data.local.archive.SettledWindowRow>()
    private val path = ArrayDeque<com.dirk.kalshiodds.data.local.archive.PricePathRow>()
    private val spot = ArrayDeque<com.dirk.kalshiodds.data.local.archive.SpotCandleRow>()
    private val fills = LinkedHashMap<String, com.dirk.kalshiodds.data.importing.ImportedFill>()
    private val paperFills = LinkedHashMap<String, com.dirk.kalshiodds.signal.paper.PaperFill>()
    private val cursors = LinkedHashMap<String, com.dirk.kalshiodds.data.local.archive.BackfillCursorRow>()
    private val settingsChanges = ArrayDeque<com.dirk.kalshiodds.data.local.history.SettingsChange>()
    private val sessions = LinkedHashMap<String, com.dirk.kalshiodds.data.local.history.HistorySession>()
    private val chartTickRows = ArrayDeque<com.dirk.kalshiodds.data.local.archive.ChartTickRow>()

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
    override fun insertOddsMids(rows: List<OddsMidRow>) {
        for (row in rows) {
            odds.addLast(row.copy(id = nextId.getAndIncrement()))
            while (odds.size > maxOdds) odds.removeFirst()
        }
    }

    @Synchronized
    override fun recentOddsMids(limit: Int): List<OddsMidRow> =
        odds.toList().takeLast(limit).asReversed()

    @Synchronized
    override fun insertForwardTests(rows: List<ForwardTestRow>) {
        for (row in rows) forward.putIfAbsent(row.ticker, row)
        while (forward.size > 5_000) forward.remove(forward.keys.first())
    }

    @Synchronized
    override fun forwardTests(limit: Int): List<ForwardTestRow> = forward.values.toList()
        .takeLast(limit.coerceIn(1, 5_000)).asReversed().map { row ->
            row.copy(outcome = settled[row.ticker]?.result)
        }

    @Synchronized
    override fun insertTicketForward(rows: List<TicketForwardRow>) {
        for (row in rows) ticketForwardRows.putIfAbsent(row.ticker, row)
        while (ticketForwardRows.size > 5_000) ticketForwardRows.remove(ticketForwardRows.keys.first())
    }

    @Synchronized
    override fun ticketForward(limit: Int): List<TicketForwardRow> = ticketForwardRows.values.toList()
        .takeLast(limit.coerceIn(1, 5_000)).asReversed().map { row ->
            row.copy(outcome = settled[row.ticker]?.result)
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

    @Synchronized
    override fun upsertSettled(rows: List<com.dirk.kalshiodds.data.local.archive.SettledWindowRow>) {
        for (r in rows) settled[r.ticker] = r
    }

    @Synchronized
    override fun insertPricePath(rows: List<com.dirk.kalshiodds.data.local.archive.PricePathRow>) {
        path.addAll(rows)
        while (path.size > 8_000) path.removeFirst()
    }

    @Synchronized
    override fun insertSpotCandles(rows: List<com.dirk.kalshiodds.data.local.archive.SpotCandleRow>) {
        spot.addAll(rows)
        while (spot.size > 12_000) spot.removeFirst()
    }

    @Synchronized
    override fun insertFills(rows: List<com.dirk.kalshiodds.data.importing.ImportedFill>) {
        for (r in rows) if (r.id !in fills) fills[r.id] = r
    }

    @Synchronized
    override fun upsertPaperFills(rows: List<com.dirk.kalshiodds.signal.paper.PaperFill>) {
        for (r in rows) paperFills[r.id] = r
    }

    fun paperFill(id: String): com.dirk.kalshiodds.signal.paper.PaperFill? = paperFills[id]

    override fun insertBidSnapshots(rows: List<OddsMidRow>) = insertOddsMids(rows)

    @Synchronized
    override fun insertChartTicks(rows: List<com.dirk.kalshiodds.data.local.archive.ChartTickRow>) {
        for (row in rows) {
            chartTickRows.removeAll { it.ticker == row.ticker && it.tMs == row.tMs }
            chartTickRows.addLast(row)
        }
        while (chartTickRows.size > maxChartTicks) chartTickRows.removeFirst()
    }

    @Synchronized
    override fun chartTicks(
        ticker: String,
        startMs: Long,
        endMs: Long,
        limit: Int
    ): List<com.dirk.kalshiodds.data.local.archive.ChartTickRow> =
        chartTickRows.filter { it.ticker == ticker && it.tMs in startMs..endMs }
            .sortedBy { it.tMs }
            .takeLast(limit.coerceIn(1, 2_000))

    @Synchronized
    override fun trimChartTicks(keepTickers: Set<String>, olderThanMs: Long) {
        chartTickRows.removeAll { row ->
            (keepTickers.isNotEmpty() && row.ticker !in keepTickers) || row.tMs < olderThanMs
        }
        val byTicker = chartTickRows.groupBy { it.ticker }
        chartTickRows.clear()
        for ((_, rows) in byTicker) {
            rows.sortedBy { it.tMs }.takeLast(240).forEach { chartTickRows.addLast(it) }
        }
    }

    @Synchronized
    override fun bidHistory(ticker: String, sinceMs: Long, limit: Int): List<com.dirk.kalshiodds.chart.BidPoint> {
        val fromChart = chartTickRows.filter { it.ticker == ticker && it.tMs >= sinceMs }
            .map { it.toBidPoint() }
        if (fromChart.size >= 4) return fromChart.sortedBy { it.tMs }.takeLast(limit)
        val fromOdds = odds.filter { it.ticker == ticker && it.createdAtMs >= sinceMs }
            .map {
                com.dirk.kalshiodds.chart.BidPoint(
                    tMs = it.createdAtMs,
                    upBidCents = (it.yesBid ?: it.mid01)?.times(100.0)?.toFloat(),
                    downBidCents = (it.noBid ?: (1.0 - it.mid01))?.times(100.0)?.toFloat()
                )
            }
        val fromPath = path.filter { it.ticker == ticker && it.tMs >= sinceMs }
            .map {
                com.dirk.kalshiodds.chart.BidPoint(
                    tMs = it.tMs,
                    upBidCents = (it.yesBid ?: it.mid)?.times(100.0)?.toFloat(),
                    downBidCents = (it.noBid ?: it.mid?.let { m -> 1.0 - m })?.times(100.0)?.toFloat()
                )
            }
        return (fromChart + fromOdds + fromPath).sortedBy { it.tMs }.takeLast(limit)
    }

    @Synchronized
    override fun pricePath(ticker: String, limit: Int) =
        path.filter { it.ticker == ticker }.takeLast(limit)

    @Synchronized
    override fun spotCloses(product: String, startMs: Long, endMs: Long): List<Double> =
        spot.filter { it.product == product && it.tMs in startMs..endMs }.map { it.close }

    @Synchronized
    override fun stats(): com.dirk.kalshiodds.data.local.archive.DataStats {
        val rows = settled.values
        return com.dirk.kalshiodds.data.local.archive.DataStats(
            settledCount = rows.size,
            minCloseMs = rows.minOfOrNull { it.closeMs ?: Long.MAX_VALUE }?.takeIf { it != Long.MAX_VALUE },
            maxCloseMs = rows.maxOfOrNull { it.closeMs ?: 0L },
            btc = rows.count { it.series.contains("BTC") },
            eth = rows.count { it.series.contains("ETH") },
            sol = rows.count { it.series.contains("SOL") },
            pathPoints = path.size,
            spotCandles = spot.size,
            fills = fills.size,
            yesSettled = rows.count { it.result == "yes" },
            noSettled = rows.count { it.result == "no" }
        ).let { it.copy(other = (it.settledCount - it.btc - it.eth - it.sol).coerceAtLeast(0)) }
    }

    @Synchronized
    override fun settledTickers(): Set<String> = settled.keys.toSet()

    @Synchronized
    override fun recentSettled(series: String?, limit: Int): List<com.dirk.kalshiodds.data.local.archive.SettledWindowRow> {
        val rows = settled.values
            .filter { series == null || it.series.equals(series, true) || it.ticker.startsWith(series.orEmpty(), true) }
            .sortedByDescending { it.closeMs ?: 0L }
        return rows.take(limit.coerceAtLeast(0))
    }

    @Synchronized
    override fun existingFillIds(): Set<String> = fills.keys.toSet()

    @Synchronized
    override fun existingSnapshotKeys(): Set<String> =
        snapshots.map { "${it.ticker}|${it.createdAtMs}" }.toSet()

    @Synchronized
    override fun existingAlertIds(): Set<String> = alerts.map { it.alertId }.toSet()

    @Synchronized
    override fun existingTicketKeys(): Set<String> =
        tickets.map { it.clientOrderId ?: "${it.ticker}|${it.createdAtMs}" }.toSet()

    @Synchronized
    override fun readCursor(job: String) = cursors[job]

    @Synchronized
    override fun writeCursor(row: com.dirk.kalshiodds.data.local.archive.BackfillCursorRow) {
        cursors[row.job] = row
    }

    @Synchronized
    override fun clearCursor(job: String) {
        cursors.remove(job)
    }

    @Synchronized
    override fun insertSettingsChange(row: com.dirk.kalshiodds.data.local.history.SettingsChange) {
        settingsChanges.addLast(row.copy(id = nextId.getAndIncrement()))
        while (settingsChanges.size > 400) settingsChanges.removeFirst()
    }

    @Synchronized
    override fun recentSettingsChanges(limit: Int, offset: Int): List<com.dirk.kalshiodds.data.local.history.SettingsChange> =
        settingsChanges.toList().asReversed().drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0))

    @Synchronized
    override fun insertSession(row: com.dirk.kalshiodds.data.local.history.HistorySession) {
        sessions[row.id] = row
    }

    @Synchronized
    override fun closeSession(
        id: String,
        endedAtMs: Long,
        markets: Int,
        signals: Int,
        bets: Int,
        pnlUsd: Double?
    ) {
        val cur = sessions[id] ?: return
        sessions[id] = cur.copy(
            endedAtMs = endedAtMs,
            markets = markets,
            signals = signals,
            bets = bets,
            pnlUsd = pnlUsd
        )
    }

    @Synchronized
    override fun recentSessions(limit: Int): List<com.dirk.kalshiodds.data.local.history.HistorySession> =
        sessions.values.sortedByDescending { it.startedAtMs }.take(limit.coerceAtLeast(0))
}
