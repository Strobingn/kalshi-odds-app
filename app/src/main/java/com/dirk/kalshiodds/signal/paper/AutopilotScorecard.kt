package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import java.util.Locale
import kotlin.math.abs

/**
 * Out-of-sample style slices for autopilot edge fills.
 * Paper bankroll and settled P&L are the score. Hit rate is secondary.
 * There is no fitted train/test split on this ledger — the copy says so.
 */
object AutopilotScorecard {
    const val MIN_BUCKET = AutopilotRegime.MIN_BUCKET

    data class Report(
        val bankrollLabel: String,
        val pnlLabel: String,
        val credibility: String,
        val favoriteLogLine: String,
        val edgeFillLine: String,
        val laterSliceLine: String,
        val regimeLines: List<String>,
        val settledCount: Int,
        val bucketsWithMin: Int,
        val pnlUsd: Double
    )

    fun of(
        fills: List<PaperFill>,
        entries: List<PredictionLogEntry> = emptyList(),
        bankrollUsd: Double? = null
    ): Report {
        val edge = fills.filter { isAutopilotEdge(it) && ScorecardLedger.isScorecardTicker(it.ticker) }
        val settled = edge.filter { it.settled && it.won != null }
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val wins = settled.count { it.won == true }
        val losses = settled.count { it.won == false }
        val buckets = settled.groupBy { composite(it) }.count { it.value.size >= MIN_BUCKET }
        return Report(
            bankrollLabel = bankrollLabel(bankrollUsd),
            pnlLabel = "Autopilot settled P&L ${ScorecardLedger.signedUsd(pnl)}",
            credibility = credibility(settled.size, buckets),
            favoriteLogLine = favoriteLogLine(entries),
            edgeFillLine = edgeFillLine(wins, losses, settled.size, pnl),
            laterSliceLine = laterSliceLine(settled),
            regimeLines = regimeLines(settled),
            settledCount = settled.size,
            bucketsWithMin = buckets,
            pnlUsd = pnl
        )
    }

    fun isAutopilotEdge(fill: PaperFill): Boolean =
        PaperPickSource.of(fill) == PaperPickSource.AUTOPILOT

    fun composite(fill: PaperFill): String =
        listOf(
            fill.regimePath ?: "untagged",
            fill.regimeRole ?: "untagged",
            fill.regimeVol ?: "untagged",
            fill.regimeSession ?: "untagged",
            fill.regimeStrike ?: "untagged"
        ).joinToString("|")

    fun credibility(settled: Int, bucketsWithMin: Int): String {
        val thin = settled < 40 || bucketsWithMin < 3
        val sample = if (thin) {
            "Thin sample — not a credible out-of-sample record across regimes."
        } else {
            "Enough settled fills to read regime slices. This is still not a fitted walk-forward test."
        }
        return "$settled settled autopilot fills · $bucketsWithMin regime buckets with >= $MIN_BUCKET trades. " +
            "No train/test split is stored on these fills. $sample"
    }

    fun favoriteLogLine(entries: List<PredictionLogEntry>): String {
        val rows = entries.filter { isSettledFavorite(it) }
        if (rows.isEmpty()) {
            return "Prediction-log favorites: none settled (direction only, separate from autopilot dollars)."
        }
        val wins = rows.count { hit(it) }
        val losses = rows.size - wins
        return "Prediction-log favorites: $wins-$losses · direction only, not autopilot dollars."
    }

    fun isSettledFavorite(entry: PredictionLogEntry): Boolean {
        val outcome = entry.outcome?.lowercase() ?: return false
        if (outcome != "yes" && outcome != "no") return false
        val side = entry.predictedSide?.trim()?.uppercase() ?: return false
        if (side != "YES" && side != "NO") return false
        val mid = entry.marketMid.takeIf { it.isFinite() } ?: return false
        val unit = if (mid > 1.0 + 1e-9) mid / 100.0 else mid
        val favoriteYes = unit >= 0.50
        return if (favoriteYes) side == "YES" else side == "NO"
    }

    private fun hit(entry: PredictionLogEntry): Boolean {
        val side = entry.predictedSide?.trim()?.uppercase() ?: return false
        val outcome = entry.outcome?.lowercase() ?: return false
        return (side == "YES" && outcome == "yes") || (side == "NO" && outcome == "no")
    }

    private fun edgeFillLine(wins: Int, losses: Int, n: Int, pnl: Double): String {
        if (n <= 0) {
            return "Autopilot edge fills: none settled. Paper P&L is the score, not hit rate."
        }
        return "Autopilot edge fills: $wins-$losses · P&L ${ScorecardLedger.signedUsd(pnl)} · $n settled. " +
            "Hit rate is not the score."
    }

    fun laterSliceLine(settledNewestLast: List<PaperFill>): String {
        val ordered = settledNewestLast.sortedBy { it.createdAtMs }
        if (ordered.size < 3) {
            return "Later-in-time slice: fewer than 3 settled autopilot fills (not a fitted holdout)."
        }
        val start = ordered.size * 2 / 3
        val later = ordered.drop(start)
        val pnl = later.sumOf { it.pnlUsd ?: 0.0 }
        return "Later-in-time P&L ${ScorecardLedger.signedUsd(pnl)} on ${later.size} fills " +
            "(chronological tail, not a fitted holdout)."
    }

    fun regimeLines(settled: List<PaperFill>): List<String> {
        if (settled.isEmpty()) return listOf("Regime slices: no settled autopilot fills yet.")
        val defs = listOf(
            "Path" to { f: PaperFill -> f.regimePath ?: "untagged" },
            "Role" to { f: PaperFill -> f.regimeRole ?: "untagged" },
            "Vol" to { f: PaperFill -> f.regimeVol ?: "untagged" },
            "Session" to { f: PaperFill -> f.regimeSession ?: "untagged" },
            "Strike" to { f: PaperFill -> f.regimeStrike ?: "untagged" }
        )
        return defs.flatMap { (title, key) ->
            settled.groupBy(key).toSortedMap().map { (bucket, rows) ->
                val pnl = rows.sumOf { it.pnlUsd ?: 0.0 }
                val wins = rows.count { it.won == true }
                val losses = rows.count { it.won == false }
                val thin = if (rows.size < MIN_BUCKET) " · thin" else ""
                "$title $bucket · P&L ${ScorecardLedger.signedUsd(pnl)} · $wins-$losses · n=${rows.size}$thin"
            }
        }
    }

    private fun bankrollLabel(bankrollUsd: Double?): String {
        val v = bankrollUsd?.takeIf { it.isFinite() } ?: return "Paper bankroll —"
        return String.format(Locale.US, "Paper bankroll $%.2f", v)
    }

    fun signedAbs(usd: Double): String {
        val sign = when {
            usd > 1e-9 -> "+"
            usd < -1e-9 -> "−"
            else -> ""
        }
        return String.format(Locale.US, "%s$%.2f", sign, abs(usd))
    }
}
