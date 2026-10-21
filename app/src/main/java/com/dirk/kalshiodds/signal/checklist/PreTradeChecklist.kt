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
        val likely = HomeCardDetails.likelySideLabel(market)?.let { "$it (model)" } ?: "—"
        val valueBase = HomeCardDetails.valueSideLine(market)?.removePrefix("Value side: ")
        val gap = valueGapPp(market)
        val value = when {
            valueBase == null -> "—"
            gap != null -> String.format(Locale.US, "%s (%+.1f pp vs ask)", valueBase, gap)
            else -> valueBase
        }
        val side = ChecklistAdvisors.tradedSide(market.predictedSide)
        val onSide = side?.let { " on $it" }.orEmpty()
        val size = when (val n = market.suggestedContracts) {
            null -> "—"
            0 -> {
                val netPp = market.netEdgePp
                if (netPp == null || netPp <= 0.0) {
                    val why = side?.let { "$it net ≤ 0" } ?: "net ≤ 0"
                    "0 contracts ($why)"
                } else {
                    "0 contracts (Kelly rounds to 0)"
                }
            }
            else -> "$n contracts max"
        }
        val net = market.netEdgePp?.let { String.format(Locale.US, "%+.1f pp%s", it, onSide) }
            ?: market.edgePp?.let { String.format(Locale.US, "%+.1f pp raw%s", it, onSide) }
            ?: "—"
        val conf = market.aiConfidence?.let { String.format(Locale.US, "%.0f%% (signal)", it * 100.0) } ?: "—"
        val regime = orDash(market.regimeTag)
        val tte = orDash(market.tteRegimeLabel)
        val skip = when {
            market.muted -> market.muteReason ?: "muted"
            !market.passedFilter -> market.skipReason ?: "filtered"
            else -> "cleared"
        }
        val unc = ChecklistAdvisors.uncertaintyText(
            market.uncertainty,
            market.uncertainty == null || market.uncertaintyPassed,
            market.uncertaintyMethod
        )
        val ttm = market.timeToMoveSec?.let { String.format(Locale.US, "%.0fs", it) } ?: "—"
        val fill = market.pFill?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val surv = ChecklistAdvisors.survivalText(market.survivalYesPp, market)
        val confSet = ChecklistAdvisors.conformalText(market)
        val path = ChecklistAdvisors.pathText(market.pathSurvive, market)
        val rl = ChecklistAdvisors.rlText(market.rlNote)
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
        val netEv = market.netEvDollars?.let { String.format(Locale.US, "%+.3f $/ct", it) } ?: "—"
        val raw = market.edgePp?.let { String.format(Locale.US, "%+.1f pp", it) } ?: "—"
        return buildString {
            appendLine("Dip Hunter checklist (advisory — no order)")
            appendLine("Ticker: ${market.ticker}")
            items(market).forEach { appendLine("${it.label}: ${it.value}") }
            appendLine("Net EV $/ct: $netEv")
            appendLine("Raw edge: $raw")
            market.metaNote?.let { appendLine("Meta: $it") }
            market.anomalyNote?.let { appendLine("Anomaly: $it") }
            HomeCardDetails.stanceLine(market)?.let { appendLine("Stance: $it") }
        }.trim()
    }

    /**
     * Model probability of the underpriced side, minus that side's ask, in percentage points.
     * Separate from Net EV, which is the sized side after the fee and half-spread.
     */
    fun valueGapPp(market: MarketUiModel): Double? {
        val yes = HomeCardDetails.modelYesPercent(market) ?: return null
        val ask = HomeCardDetails.valueAskCents(market) ?: return null
        return when (HomeCardDetails.valueSide(market)) {
            "UP" -> yes - ask
            "DOWN" -> (100.0 - yes) - ask
            else -> null
        }
    }

    fun compactLine(items: List<Item>): String =
        items.joinToString(" · ") { "${it.label} ${it.value}" }

    private fun orDash(raw: String?): String {
        val t = raw?.trim().orEmpty()
        return if (t.isEmpty()) "—" else t
    }

}
