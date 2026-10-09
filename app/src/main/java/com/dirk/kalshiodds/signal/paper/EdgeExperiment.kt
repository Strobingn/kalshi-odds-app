package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Honest verdict on the running auto-paper experiment: is the AI's automatic
 * trading actually profitable after fees and spread, over enough settled
 * windows to trust? Pure / local — reads the settled paper ledger only.
 */
object EdgeExperiment {

    const val MIN_SETTLED = 30
    const val MIN_CONFIDENT = 100

    data class Verdict(
        val settledWins: Int,
        val settledLosses: Int,
        val settledCount: Int,
        val pnlUsd: Double,
        val avgPnlPerFill: Double?,
        /** Positive when wins outperform losses by a real margin. */
        val edgeDetected: Boolean,
        val enoughSamples: Boolean,
        val verdictLine: String,
        val detailLine: String
    ) {
        val winRate: Double? get() =
            if (settledCount <= 0) null else settledWins.toDouble() / settledCount
    }

    fun evaluate(state: PaperBookState): Verdict {
        val settled = state.fills
            .filter { CryptoMarkets.isLiveTicker(it.ticker) }
            .filter { it.settled && it.outcome != "void" }
        val wins = settled.count { it.won == true }
        val losses = settled.count { it.won != true }
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val n = settled.size
        val avg = if (n > 0) pnl / n else null

        val pnlText = String.format(java.util.Locale.US, "%+.2f", pnl)
        val stdev = if (n >= 2) {
            val m = avg ?: 0.0
            sqrt(settled.sumOf { val d = (it.pnlUsd ?: 0.0) - m; d * d } / (n - 1))
        } else null
        // Require the mean to clear noise: t-ish threshold shrinks with n.
        // Zero variance with a positive mean is the strongest possible edge.
        val clearsNoise = n >= MIN_SETTLED && (avg ?: 0.0) > 0.0 &&
            (stdev == null || stdev <= 0.0 || abs(avg ?: 0.0) > 2.0 * stdev / sqrt(n.toDouble()))
        val edge = n >= MIN_SETTLED && pnl > 0.0 && clearsNoise
        val enough = n >= MIN_CONFIDENT

        val verdictLineFmt = when {
            n == 0 -> "No settled auto-fills yet — the experiment is running"
            n < MIN_SETTLED -> "Collecting data · $n/$MIN_SETTLED settled windows"
            edge && enough -> "EDGE DETECTED over $n windows · paper $pnlText"
            edge -> "Edge emerging over $n windows · keep collecting to $MIN_CONFIDENT"
            else -> "No edge over $n windows · paper $pnlText after fees"
        }
        val detail = buildString {
            append(
                String.format(java.util.Locale.US, "%d W · %d L", wins, losses)
            )
            avg?.let {
                append(
                    String.format(
                        java.util.Locale.US,
                        " · avg %+.2f/fill after fees",
                        it
                    )
                )
            }
            if (n in 1 until MIN_CONFIDENT) append(" · need ${MIN_CONFIDENT - n} more for a confident call")
        }
        return Verdict(
            settledWins = wins,
            settledLosses = losses,
            settledCount = n,
            pnlUsd = pnl,
            avgPnlPerFill = avg,
            edgeDetected = edge,
            enoughSamples = enough,
            verdictLine = verdictLineFmt,
            detailLine = detail
        )
    }
}
