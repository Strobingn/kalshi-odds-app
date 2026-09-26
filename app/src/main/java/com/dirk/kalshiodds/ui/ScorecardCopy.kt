package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ForecastUnits
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.model.SignalStance
import java.time.ZoneId
import java.util.Locale

/**
 * Full-scorecard presentation. Every count, empty state, and section
 * line is derived from [settledPicks] plus the same paper P&L
 * [HomeScorecardSummary] uses. Empty-state copy is shown only when
 * that settled list is empty — never from [ScorecardMetrics.Snapshot.perSeries]
 * or ML calibration sample counts.
 */
object ScorecardCopy {
    const val TITLE = "Scorecard"
    const val SUBTITLE = "Post-settlement paper record. Voids and NO BET rows are excluded."
    const val NO_SETTLED = HomeScorecardSummary.NO_SETTLED
    const val COINS_TITLE = "By coin"
    const val TIME_TITLE = "By time of day"
    const val RECENT_TITLE = "Recent settled picks"
    const val WON = "won"
    const val LOST = "lost"
    const val EM_DASH = "—"
    const val RECENT_LIMIT = 20
    val COIN_ORDER: List<String> = ScorecardMetrics.COIN_ORDER
    val ET_ZONE: ZoneId = ZoneId.of("America/New_York")

    data class Bucket(
        val key: String,
        val label: String,
        val wins: Int,
        val losses: Int,
        val settledCount: Int,
        val hitRate: Double?,
        val line: String
    )

    data class RecentPick(
        val ticker: String,
        val coin: String,
        val side: String,
        val won: Boolean,
        val settledAtMs: Long,
        val line: String
    )

    data class View(
        val summary: HomeScorecardSummary,
        val coins: List<Bucket>,
        val timeOfDay: List<Bucket>,
        val recent: List<RecentPick>
    ) {
        val settledCount: Int get() = summary.settledCount
        val showsEmptyState: Boolean get() = showsEmptyState(settledCount)
        fun emptyState(): String? = emptyState(settledCount)

        fun allLines(recentExpanded: Boolean = false): List<String> {
            val lines = mutableListOf(SUBTITLE)
            emptyState()?.let { lines += it }
            if (!showsEmptyState) {
                lines += recordLine(summary)
                lines += winRateLine(summary)
                lines += paperPnlLine(summary)
                lines += settledCountLine(summary)
            }
            lines += COINS_TITLE
            lines += coins.map { it.line }
            lines += TIME_TITLE
            lines += timeOfDay.map { it.line }
            if (recent.isNotEmpty()) {
                lines += RECENT_TITLE
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
    ): View {
        val settled = settledPicks(entries)
        return View(
            summary = HomeScorecardSummary.of(settled, paperPnlUsd),
            coins = coinBuckets(settled),
            timeOfDay = timeBuckets(settled, zoneId),
            recent = recentPicks(settled)
        )
    }

    fun coinBuckets(settled: List<PredictionLogEntry>): List<Bucket> {
        val groups = settled.groupBy { ScorecardMetrics.coinOf(it.series.ifBlank { it.ticker }) }
        return COIN_ORDER.map { coin -> bucket(coin, coin, groups[coin].orEmpty()) }
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
        hitRate: Double?
    ): String {
        if (settledCount <= 0) return "$label  $EM_DASH"
        return "$label  $wins-$losses · ${percentOrDash(hitRate)} · $settledCount settled"
    }

    fun recentPicks(settled: List<PredictionLogEntry>): List<RecentPick> =
        settled
            .sortedByDescending { settledAt(it) }
            .take(RECENT_LIMIT)
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

    fun recentPickLine(coin: String, side: String, won: Boolean, ticker: String): String =
        "$coin  $side  ${if (won) WON else LOST}  $ticker"

    fun recordLine(summary: HomeScorecardSummary): String =
        if (summary.settledCount <= 0) EM_DASH else "${summary.wins}-${summary.losses}"

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
            SignalStance.CALL_DOWN -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.BET_DOWN
            SignalStance.CALL_UP -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.BET_UP
            else -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.NO_BET
        }

    private fun settledAt(entry: PredictionLogEntry): Long = entry.settledAtMs ?: entry.timestampMs
}
