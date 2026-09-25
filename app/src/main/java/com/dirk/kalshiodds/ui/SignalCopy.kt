package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.trade.BetCall
import java.util.Locale

/**
 * Display-only signal card copy. Edge on the card is always
 * model% − market% for the shown side — same definition as
 * [HomeCopy.modelVsMarket]. Stored `deltaPp` is blended fair − mid
 * (YES-centric) and stays in [Card.details], not the header.
 */
object SignalCopy {
    data class Card(
        val title: String,
        val call: String,
        val modelLine: String,
        val outcome: String?,
        val details: String?
    )

    fun callLabel(side: String?): String = when {
        side.equals("NO", true) || side.equals("DOWN", true) -> "DOWN"
        side.equals("YES", true) || side.equals("UP", true) -> "UP"
        else -> "NO BET"
    }

    fun headline(side: String?): BetCall.Headline = when (callLabel(side)) {
        "DOWN" -> BetCall.Headline.BET_DOWN
        "UP" -> BetCall.Headline.BET_UP
        else -> BetCall.Headline.NO_BET
    }

    fun sideIsUp(side: String?): Boolean = callLabel(side) != "DOWN"

    /** [modelYes] / [marketYes] are P(YES) percents (0–100). */
    fun displayedEdgePts(modelYes: Double?, marketYes: Double?, side: String?): Double? {
        val model = sidePercent(modelYes, side) ?: return null
        val market = sidePercent(marketYes, side) ?: return null
        return model - market
    }

    fun sidePercent(yesPercent: Double?, side: String?): Double? {
        val p = yesPercent?.takeIf { it.isFinite() } ?: return null
        return if (sideIsUp(side)) p else 100.0 - p
    }

    fun modelVsMarketLine(modelYes: Double?, marketYes: Double?, side: String?): String {
        val model = sidePercent(modelYes, side)
        val market = sidePercent(marketYes, side)
        val edge = displayedEdgePts(modelYes, marketYes, side)
        val modelTxt = model?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val marketTxt = market?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val edgeTxt = edge?.let { String.format(Locale.US, "%+.0f pts", it) } ?: "—"
        return "Model $modelTxt vs market $marketTxt · edge $edgeTxt"
    }

    fun outcomeLabel(settled: String?): String = when {
        settled.isNullOrBlank() -> "Pending"
        settled.equals("yes", true) || settled.equals("won", true) ||
            settled.equals("win", true) -> "Won"
        settled.equals("no", true) || settled.equals("lost", true) ||
            settled.equals("loss", true) -> "Lost"
        settled.equals("void", true) -> "Void"
        else -> "Pending"
    }

    fun card(
        ticker: String,
        side: String?,
        modelYes: Double?,
        marketYes: Double?,
        settled: String? = null,
        details: String? = null,
        closeEpochMs: Long? = null
    ): Card = Card(
        title = WindowLabel.of(ticker, closeEpochMs),
        call = callLabel(side),
        modelLine = modelVsMarketLine(modelYes, marketYes, side),
        outcome = outcomeLabel(settled),
        details = details?.trim()?.takeIf { it.isNotEmpty() }
    )

    /**
     * Live-alert mapper. Prefers `AI N% vs mkt M%` from [SignalAlert.reason]
     * so the line matches the home card (AI − market). Falls back to
     * [SignalAlert.fairValuePp] / [SignalAlert.marketMidPp] when the
     * diagnostic has no AI pair. Stored [SignalAlert.deltaPp] is fair − mid.
     */
    fun card(alert: SignalAlert, settled: String? = null): Card {
        val parsed = parseAiVsMarket(alert.reason)
        return card(
            ticker = alert.ticker,
            side = alert.predictedSide,
            modelYes = parsed?.first ?: alert.fairValuePp,
            marketYes = parsed?.second ?: alert.marketMidPp,
            settled = settled,
            details = detailsForAlert(alert)
        )
    }

    fun parseAiVsMarket(reason: String?): Pair<Double, Double>? {
        val m = AI_VS_MKT.find(reason ?: return null) ?: return null
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    fun detailsForAlert(alert: SignalAlert): String = buildString {
        alert.reason.trim().takeIf { it.isNotEmpty() }?.let { append(it) }
        if (isNotEmpty()) append('\n')
        append(String.format(Locale.US, "Stored Δ %+.1f pp (fair − mid, not the card edge)", alert.deltaPp))
    }

    private val AI_VS_MKT = Regex("""AI\s+(\d+(?:\.\d+)?)%\s+vs\s+mkt\s+(\d+(?:\.\d+)?)%""", RegexOption.IGNORE_CASE)
}
