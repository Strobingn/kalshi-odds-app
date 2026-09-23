package com.dirk.kalshiodds.signal.checklist

import com.dirk.kalshiodds.domain.MarketUiModel
import java.util.Locale

/**
 * Compact pre-trade checklist + one-tap plain-text copy.
 * Advisory only — never an order ticket.
 */
object PreTradeChecklist {

    data class Item(
        val label: String,
        val value: String
    )

    fun items(market: MarketUiModel): List<Item> {
        val side = market.predictedSide ?: market.stanceSide()
        val size = market.suggestedContracts?.let { "$it contracts max" } ?: "—"
        val net = market.netEdgePp?.let { String.format(Locale.US, "%+.1f pp", it) }
            ?: market.edgePp?.let { String.format(Locale.US, "%+.1f pp raw", it) }
            ?: "—"
        val conf = market.aiConfidence?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val regime = market.regimeTag ?: "—"
        val tte = market.tteRegimeLabel ?: "—"
        val skip = when {
            market.muted -> market.muteReason ?: "muted"
            !market.passedFilter -> market.skipReason ?: "filtered"
            else -> "cleared"
        }
        val unc = market.uncertainty?.let { String.format(Locale.US, "%.2f%s", it, if (market.uncertaintyPassed) "" else " GATE") } ?: "—"
        val ttm = market.timeToMoveSec?.let { String.format(Locale.US, "%.0fs", it) } ?: "—"
        val fill = market.pFill?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val surv = market.survivalYesPp?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val confSet = market.conformalSet ?: "—"
        val path = market.pathSurvive?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val rl = market.rlNote ?: "—"
        return listOf(
            Item("Side", side),
            Item("Size", size),
            Item("Net EV", net),
            Item("Confidence", conf),
            Item("Uncertainty", unc),
            Item("Time-to-move", ttm),
            Item("P(fill)", fill),
            Item("Regime", regime),
            Item("TTE", tte),
            Item("Skip filter", skip),
            Item("Survival P(YES)", surv),
            Item("Conformal", confSet),
            Item("P(edge survives)", path),
            Item("RL stake", rl)
        )
    }

    fun copyText(market: MarketUiModel): String {
        val side = market.predictedSide ?: market.stanceSide()
        val size = market.suggestedContracts ?: 0
        val netEv = market.netEvDollars?.let { String.format(Locale.US, "%+.3f $/ct", it) } ?: "—"
        val netPp = market.netEdgePp?.let { String.format(Locale.US, "%+.1f pp", it) } ?: "—"
        val raw = market.edgePp?.let { String.format(Locale.US, "%+.1f pp", it) } ?: "—"
        val conf = market.aiConfidence?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val skip = when {
            market.muted -> "MUTED (${market.muteReason ?: "allowlist"})"
            !market.passedFilter -> "FILTERED (${market.skipReason ?: "weak"})"
            else -> "cleared"
        }
        return buildString {
            appendLine("Dip Hunter checklist (advisory — no order)")
            appendLine("Ticker: ${market.ticker}")
            appendLine("Side: $side")
            appendLine("Suggested size: $size contracts max")
            appendLine("Net EV: $netEv ($netPp)")
            appendLine("Raw edge: $raw")
            appendLine("Confidence: $conf")
            market.uncertainty?.let {
                appendLine("Uncertainty: ${String.format(Locale.US, "%.2f", it)}${if (market.uncertaintyPassed) "" else " (gated)"}")
            }
            market.timeToMoveSec?.let { appendLine("Time-to-move: ${String.format(Locale.US, "%.0fs", it)}") }
            market.midVolPp?.let { appendLine("Mid vol: ${String.format(Locale.US, "%.1fpp", it)}") }
            market.pFill?.let { appendLine("P(fill): ${String.format(Locale.US, "%.0f%%", it * 100.0)}") }
            appendLine("Regime: ${market.regimeTag ?: "—"}")
            market.sessionTag?.let { appendLine("Session: $it") }
            appendLine("TTE: ${market.tteRegimeLabel ?: "—"}")
            market.survivalYesPp?.let { appendLine("Survival P(YES): ${String.format(Locale.US, "%.0f%%", it)}") }
            market.conformalSet?.let { appendLine("Conformal: $it${if (market.conformalAmbiguous) " (ambiguous)" else ""}") }
            market.pathSurvive?.let { appendLine("P(edge survives): ${String.format(Locale.US, "%.0f%%", it * 100.0)}") }
            market.rlNote?.let { appendLine("RL stake (advisory): $it") }
            market.metaNote?.let { appendLine("Meta: $it") }
            market.anomalyNote?.let { appendLine("Anomaly: $it") }
            appendLine("Skip filter: $skip")
            market.stance?.let { appendLine("Stance: $it") }
        }.trim()
    }

    fun compactLine(items: List<Item>): String =
        items.joinToString(" · ") { "${it.label} ${it.value}" }

    private fun MarketUiModel.stanceSide(): String = when {
        (edgePp ?: 0.0) < 0 -> "NO"
        (edgePp ?: 0.0) > 0 -> "YES"
        stance?.contains("NO", true) == true -> "NO"
        else -> "YES"
    }
}
