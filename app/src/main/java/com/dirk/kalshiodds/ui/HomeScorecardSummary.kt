package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import java.util.Locale
import kotlin.math.abs

/**
 * Compact home scorecard: W-L, win rate, paper P&L from the same
 * settled-result store [ScorecardViewModel] uses ([PredictionLogStore]
 * + [ScorecardMetrics.window] / all-time). NO BET rows are not scored.
 */
data class HomeScorecardSummary(
    val wins: Int = 0,
    val losses: Int = 0,
    val hitRate: Double? = null,
    val paperPnlUsd: Double = 0.0,
    val settledCount: Int = 0
) {
    fun line(): String = HomeCopy.scorecardSummaryLine(this)

    companion object {
        const val NO_SETTLED = "No settled picks yet"
        val EMPTY = HomeScorecardSummary()

        fun of(
            entries: List<PredictionLogEntry>,
            paperPnlUsd: Double
        ): HomeScorecardSummary {
            val settled = ScorecardMetrics.settledScoredPicks(entries)
            val all = ScorecardMetrics.window(settled)
            return HomeScorecardSummary(
                wins = all.hits,
                losses = (all.total - all.hits).coerceAtLeast(0),
                hitRate = all.hitRate,
                paperPnlUsd = paperPnlUsd,
                settledCount = all.total
            )
        }

        fun paperPnlPart(usd: Double): String {
            val sign = when {
                usd > 1e-9 -> "+"
                usd < -1e-9 -> "−"
                else -> ""
            }
            return String.format(Locale.US, "paper %s$%.2f", sign, abs(usd))
        }
    }
}
