package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ForecastUnits
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import java.time.ZoneId
import java.util.Locale

/**
 * Full-scorecard presentation. Counts, empty state, and every section
 * line come from [ScorecardLedger] (settled prediction-log rows +
 * settled paper fills). Empty-state copy is shown only when that
 * combined settled list is empty.
 */
object ScorecardCopy {
    const val TITLE = "Scorecard"
    const val SUBTITLE = "Post-settlement paper record from stored settled rows. Voids are excluded from W-L."
    const val NO_SETTLED = HomeScorecardSummary.NO_SETTLED
    const val TIME_TITLE = "By time of day"
    const val SIDE_TITLE = "By side"
    const val PRICE_TITLE = "By entry price"
    const val CONF_TITLE = "By AI confidence"
    const val AI_TITLE = "AI picks"
    const val MANUAL_TITLE = "Manual Paper UP/DOWN"
    const val COMBINED_TITLE = "Combined"
    const val NO_BET_TITLE = "NO BET would-have-been"
    const val PICKS_TITLE = "Every settled pick"
    const val PNL_TITLE = "Running P&L"
    const val WON = "WIN"
    const val LOST = "LOSS"
    const val EM_DASH = "—"
    val ET_ZONE: ZoneId = ScorecardLedger.ET_ZONE

    data class Bucket(
        val key: String,
        val label: String,
        val wins: Int,
        val losses: Int,
        val settledCount: Int,
        val hitRate: Double?,
        val pnlUsd: Double = 0.0,
        val line: String
    )

    data class RecentPick(
        val ticker: String,
        val coin: String,
        val side: String,
        val won: Boolean,
        val settledAtMs: Long,
        val line: String,
        val row: ScorecardLedger.PickRow? = null
    )

    data class View(
        val summary: HomeScorecardSummary,
        val timeOfDay: List<Bucket>,
        val recent: List<RecentPick>,
        val ledger: ScorecardLedger.Snapshot,
        val bySide: List<Bucket>,
        val byPrice: List<Bucket>,
        val byConfidence: List<Bucket>
    ) {
        val settledCount: Int get() = ledger.combined.settledCount
        val showsEmptyState: Boolean get() = showsEmptyState(settledCount)
        fun emptyState(): String? = emptyState(settledCount)

        fun allLines(recentExpanded: Boolean = true): List<String> {
            val lines = mutableListOf(SUBTITLE)
            emptyState()?.let { lines += it }
            if (!showsEmptyState) {
                lines += recordLine(summary)
                lines += winRateLine(summary)
                lines += paperPnlLine(summary)
                lines += settledCountLine(summary)
                lines += COMBINED_TITLE
                lines += recordLine(ledger.combined)
                lines += AI_TITLE
                lines += recordLine(ledger.ai)
                lines += MANUAL_TITLE
                lines += recordLine(ledger.manual)
            }
            lines += SIDE_TITLE
            lines += bySide.map { it.line }
            lines += PRICE_TITLE
            lines += byPrice.map { it.line }
            lines += TIME_TITLE
            lines += timeOfDay.map { it.line }
            lines += CONF_TITLE
            lines += byConfidence.map { it.line }
            if (recent.isNotEmpty()) {
                lines += PICKS_TITLE
                if (recentExpanded) lines += recent.map { it.line }
            }
            return lines
        }
    }

    val EMPTY: View = of(emptyList(), 0.0)

    fun settledPicks(entries: List<PredictionLogEntry>): List<PredictionLogEntry> =
        ScorecardMetrics.settledScoredPicks(entries)

    fun showsEmptyState(settledCount: Int): Boolean = settledCount <= 0

    fun emptyState(settledCount: Int): String? =
        if (showsEmptyState(settledCount)) NO_SETTLED else null

    fun of(
        entries: List<PredictionLogEntry>,
        paperPnlUsd: Double,
        zoneId: ZoneId = ET_ZONE
    ): View = of(
        entries = entries,
        fills = emptyList(),
        paperPnlUsd = paperPnlUsd,
        windows = emptyList(),
        zoneId = zoneId
    )

    fun of(
        entries: List<PredictionLogEntry>,
        paper: PaperBookState,
        windows: List<SettledWindowRow> = emptyList(),
        zoneId: ZoneId = ET_ZONE
    ): View = of(
        entries = entries,
        fills = paper.fills + paper.archived.flatMap { it.fills },
        paperPnlUsd = paper.realizedPnlUsd,
        windows = windows,
        zoneId = zoneId
    )

    fun of(
        entries: List<PredictionLogEntry>,
        fills: List<PaperFill>,
        paperPnlUsd: Double,
        windows: List<SettledWindowRow> = emptyList(),
        zoneId: ZoneId = ET_ZONE
    ): View {
        val ledger = ScorecardLedger.of(entries, fills, windows, zoneId)
        val summary = HomeScorecardSummary(
            wins = ledger.combined.wins,
            losses = ledger.combined.losses,
            hitRate = ledger.combined.hitRate,
            paperPnlUsd = if (fills.isEmpty()) paperPnlUsd else ledger.combined.money.pnlUsd,
            settledCount = ledger.combined.settledCount
        )
        return View(
            summary = summary,
            timeOfDay = ledger.byTime.map { toBucket(it) },
            recent = ledger.picks.filter { !it.noBetWouldHave }.map { recentPick(it) },
            ledger = ledger,
            bySide = ledger.bySide.map { toBucket(it) },
            byPrice = ledger.byPrice.map { toBucket(it) },
            byConfidence = ledger.byConfidence.map { toBucket(it) }
        )
    }

    fun timeBuckets(
        settled: List<PredictionLogEntry>,
        zoneId: ZoneId = ET_ZONE
    ): List<Bucket> {
        val defs = (0 until 6).map { i ->
            val start = i * 4
            val key = "%02d-%02d".format(start, start + 4)
            val label = "%d–%d ET".format(start, start + 4)
            key to label
        }
        val groups = settled.groupBy { ScorecardMetrics.etBucketKey(settledAt(it), zoneId) }
        return defs.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) }
    }

    fun bucket(key: String, label: String, rows: List<PredictionLogEntry>): Bucket {
        val stats = ScorecardMetrics.window(rows)
        val losses = (stats.total - stats.hits).coerceAtLeast(0)
        return Bucket(
            key = key,
            label = label,
            wins = stats.hits,
            losses = losses,
            settledCount = stats.total,
            hitRate = stats.hitRate,
            line = bucketLine(label, stats.hits, losses, stats.total, stats.hitRate)
        )
    }

    fun bucketLine(
        label: String,
        wins: Int,
        losses: Int,
        settledCount: Int,
        hitRate: Double?,
        pnlUsd: Double? = null
    ): String {
        if (settledCount <= 0) return "$label  $EM_DASH"
        val money = pnlUsd?.let { " · ${ScorecardLedger.signedUsd(it)}" }.orEmpty()
        return "$label  $wins-$losses · ${percentOrDash(hitRate)} · $settledCount settled$money"
    }

    fun recentPicks(settled: List<PredictionLogEntry>): List<RecentPick> =
        settled
            .sortedByDescending { settledAt(it) }
            .map { recentPick(it) }

    fun recentPick(entry: PredictionLogEntry): RecentPick {
        val coin = ScorecardMetrics.coinOf(entry.series.ifBlank { entry.ticker })
        val side = SignalCopy.callLabel(entry.predictedSide)
        val won = ForecastUnits.hit(entry)
        return RecentPick(
            ticker = entry.ticker,
            coin = coin,
            side = side,
            won = won,
            settledAtMs = settledAt(entry),
            line = recentPickLine(coin, side, won, entry.ticker)
        )
    }

    fun recentPick(row: ScorecardLedger.PickRow): RecentPick {
        val coin = ScorecardMetrics.coinOf(row.ticker)
        return RecentPick(
            ticker = row.ticker,
            coin = coin,
            side = row.displaySide,
            won = row.won,
            settledAtMs = row.settledAtMs,
            line = pickLine(row),
            row = row
        )
    }

    fun recentPickLine(coin: String, side: String, won: Boolean, ticker: String): String =
        "$coin  $side  ${if (won) WON else LOST}  $ticker"

    fun pickLine(row: ScorecardLedger.PickRow): String {
        val result = if (row.won) WON else LOST
        val ask = row.entryAsk?.let { String.format(Locale.US, "%.0f¢", it * 100.0) } ?: EM_DASH
        val ai = row.aiPct?.let { String.format(Locale.US, "AI %.0f%%", it) } ?: "AI $EM_DASH"
        val mkt = row.marketPct?.let { String.format(Locale.US, "mkt %.0f%%", it) } ?: "mkt $EM_DASH"
        val stake = row.stakeUsd?.let { String.format(Locale.US, "stake $%.2f", it) } ?: "stake $EM_DASH"
        val ct = row.contracts?.let { "$it ct" } ?: EM_DASH
        val fee = row.feeUsd?.let { String.format(Locale.US, "fee $%.2f", it) } ?: "fee $EM_DASH"
        val pnl = ScorecardLedger.signedUsd(row.pnlUsd)
        val strike = row.strikeUsd?.let { String.format(Locale.US, "strike $%,.0f", it) } ?: "strike $EM_DASH"
        val fin = row.finalUsd?.let { String.format(Locale.US, "final $%,.0f", it) } ?: "final $EM_DASH"
        return "${row.windowEt}  ${row.displaySide}  $ask  $ai  $mkt  $ct  $stake  $fee  $result  $pnl  $strike  $fin  ${row.ticker}"
    }

    fun recordLine(summary: HomeScorecardSummary): String =
        if (summary.settledCount <= 0) EM_DASH else "${summary.wins}-${summary.losses}"

    fun recordLine(record: ScorecardLedger.Record): String =
        if (record.settledCount <= 0) EM_DASH else "${record.wins}-${record.losses} · ${percentOrDash(record.hitRate)} · streak ${record.streak}"

    fun winRateLine(summary: HomeScorecardSummary): String = percentOrDash(summary.hitRate)

    fun paperPnlLine(summary: HomeScorecardSummary): String =
        if (summary.settledCount <= 0) EM_DASH else HomeScorecardSummary.paperPnlPart(summary.paperPnlUsd)

    fun settledCountLine(summary: HomeScorecardSummary): String =
        if (summary.settledCount <= 0) EM_DASH else "${summary.settledCount} settled"

    fun summaryLine(summary: HomeScorecardSummary): String = HomeCopy.scorecardSummaryLine(summary)

    fun percentOrDash(rate: Double?): String =
        rate?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: EM_DASH

    fun sideHeadline(side: String): com.dirk.kalshiodds.signal.trade.BetCall.Headline =
        when (side) {
            SignalStance.CALL_DOWN, "DOWN", "NO" -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.BET_DOWN
            SignalStance.CALL_UP, "UP", "YES" -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.BET_UP
            else -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.NO_BET
        }

    fun resultColorIsWin(won: Boolean): Boolean = won

    private fun toBucket(b: ScorecardLedger.Bucket): Bucket = Bucket(
        key = b.key,
        label = b.label,
        wins = b.wins,
        losses = b.losses,
        settledCount = b.settledCount,
        hitRate = b.hitRate,
        pnlUsd = b.pnlUsd,
        line = bucketLine(b.label, b.wins, b.losses, b.settledCount, b.hitRate, b.pnlUsd)
    )

    private fun settledAt(entry: PredictionLogEntry): Long = entry.settledAtMs ?: entry.timestampMs
}
