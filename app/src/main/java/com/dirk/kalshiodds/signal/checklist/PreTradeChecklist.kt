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
        return listOf(
            Item("Side", side),
            Item("Size", size),
            Item("Net EV", net),
            Item("Confidence", conf),
            Item("Regime", regime),
            Item("TTE", tte),
            Item("Skip filter", skip)
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
            appendLine("Regime: ${market.regimeTag ?: "—"}")
            appendLine("TTE: ${market.tteRegimeLabel ?: "—"}")
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
