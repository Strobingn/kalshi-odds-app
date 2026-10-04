package com.dirk.kalshiodds.signal.checklist

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.HomeCardDetails
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
        val likely = HomeCardDetails.likelySideLabel(market) ?: "—"
        val value = HomeCardDetails.valueSideLine(market)?.removePrefix("Value side: ") ?: "—"
        val size = market.suggestedContracts?.let { "$it contracts max" } ?: "—"
        val net = market.netEdgePp?.let { String.format(Locale.US, "%+.1f pp", it) }
            ?: market.edgePp?.let { String.format(Locale.US, "%+.1f pp raw", it) }
            ?: "—"
        val conf = market.aiConfidence?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val regime = orDash(market.regimeTag)
        val tte = orDash(market.tteRegimeLabel)
        val skip = when {
            market.muted -> market.muteReason ?: "muted"
            !market.passedFilter -> market.skipReason ?: "filtered"
            else -> "cleared"
        }
        val unc = market.uncertainty?.let { String.format(Locale.US, "%.2f%s", it, if (market.uncertaintyPassed) "" else " GATE") } ?: "—"
        val ttm = market.timeToMoveSec?.let { String.format(Locale.US, "%.0fs", it) } ?: "—"
        val fill = market.pFill?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val surv = market.survivalYesPp?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val confSet = orDash(market.conformalSet)
        val path = market.pathSurvive?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val rl = orDash(market.rlNote)
        return listOf(
            Item("Likely side", likely),
            Item("Value side", value),
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
        val likely = HomeCardDetails.likelySideLabel(market) ?: "—"
        val value = HomeCardDetails.valueSideLine(market) ?: "—"
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
            appendLine("Bitcoin Claude checklist (advisory — no order)")
            appendLine("Ticker: ${market.ticker}")
            appendLine("Likely side: $likely")
            appendLine("Value side: $value")
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
            HomeCardDetails.stanceLine(market)?.let { appendLine("Stance: $it") }
        }.trim()
    }

    fun compactLine(items: List<Item>): String =
        items.joinToString(" · ") { "${it.label} ${it.value}" }

    private fun orDash(raw: String?): String {
        val t = raw?.trim().orEmpty()
        return if (t.isEmpty()) "—" else t
    }

}
