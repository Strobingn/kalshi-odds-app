package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.CryptoMarkets
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
    val settledCount: Int = 0,
    /** Model-versus-market side, kept as its own labelled stat. */
    val evSideWins: Int = 0,
    val evSideLosses: Int = 0,
    val evSideHitRate: Double? = null,
    val evSideSettled: Int = 0
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
                .filter { CryptoMarkets.isScorecardTicker(it.ticker) }
            val ev = ScorecardMetrics.window(settled)
            val modelRows = settled.mapNotNull { row ->
                val side = com.dirk.kalshiodds.signal.feedback.ForecastUnits.modelWinnerSide(row)
                    ?: return@mapNotNull null
                if (row.predictedSide.equals(side, ignoreCase = true)) row else row.copy(predictedSide = side)
            }
            val model = ScorecardMetrics.window(modelRows)
            return HomeScorecardSummary(
                wins = model.hits,
                losses = (model.total - model.hits).coerceAtLeast(0),
                hitRate = model.hitRate,
                paperPnlUsd = paperPnlUsd,
                settledCount = model.total,
                evSideWins = ev.hits,
                evSideLosses = (ev.total - ev.hits).coerceAtLeast(0),
                evSideHitRate = ev.hitRate,
                evSideSettled = ev.total
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
