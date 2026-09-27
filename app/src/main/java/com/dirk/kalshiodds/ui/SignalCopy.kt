package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.trade.BetCall
import java.util.Locale

/**
 * Display-only signal card copy. Side, percents, and edge all use
 * [SignalStance] — the same model-vs-market definition as
 * [HomeCopy.modelVsMarket] (`importedModelPp ?: AI` vs market).
 * Stored `deltaPp` is blended fair − mid (YES-centric) and stays in
 * [Card.details], not the header.
 */
object SignalCopy {
    data class Card(
        val title: String,
        val call: String,
        val modelLine: String,
        val outcome: String?,
        val details: String?,
        /** Ticker + id / timestamp — never display text. Used as a Lazy key. */
        val stableKey: String = ""
    )

    fun callLabel(side: String?): String = when (SignalStance.normalizeSide(side)) {
        SignalStance.NO -> SignalStance.CALL_DOWN
        SignalStance.YES -> SignalStance.CALL_UP
        else -> SignalStance.CALL_NO_BET
    }

    fun headline(side: String?): BetCall.Headline = when (callLabel(side)) {
        SignalStance.CALL_DOWN -> BetCall.Headline.BET_DOWN
        SignalStance.CALL_UP -> BetCall.Headline.BET_UP
        else -> BetCall.Headline.NO_BET
    }

    fun sideIsUp(side: String?): Boolean = callLabel(side) != SignalStance.CALL_DOWN

    /** [modelYes] / [marketYes] are P(YES) percents (0–100). */
    fun displayedEdgePts(modelYes: Double?, marketYes: Double?, side: String?): Double? =
        SignalStance.edgeFor(modelYes, marketYes, side)

    fun sidePercent(yesPercent: Double?, side: String?): Double? =
        SignalStance.sidePercent(yesPercent, side)

    fun modelVsMarketLine(modelYes: Double?, marketYes: Double?, side: String?): String {
        val model = sidePercent(modelYes, side)
        val market = sidePercent(marketYes, side)
        val edge = displayedEdgePts(modelYes, marketYes, side)
        val modelTxt = model?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val marketTxt = market?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val edgeTxt = edge?.let { String.format(Locale.US, "%+.0f pts", it) } ?: "—"
        return "Model $modelTxt vs market $marketTxt · edge $edgeTxt"
    }

    fun resolve(
        storedSide: String?,
        modelYes: Double?,
        marketYes: Double?,
        fairYes: Double? = null
    ): SignalStance.Resolved = SignalStance.resolve(storedSide, modelYes, marketYes, fairYes)

    fun resolve(alert: SignalAlert): SignalStance.Resolved {
        val parsed = parseAiVsMarket(alert.reason)
        return SignalStance.fromAlert(
            predictedSide = alert.predictedSide,
            modelYes = parsed?.first ?: alert.fairValuePp,
            marketYes = parsed?.second ?: alert.marketMidPp,
            fairYes = alert.fairValuePp
        )
    }

    fun shouldNotify(alert: SignalAlert): Boolean {
        if (!SignalStance.shouldNotify(resolve(alert))) return false
        val sideYes = !alert.predictedSide.equals("NO", true)
        val model = (if (sideYes) alert.fairValuePp else 100.0 - alert.fairValuePp) / 100.0
        val implied = (if (sideYes) alert.marketMidPp else 100.0 - alert.marketMidPp) / 100.0
        if (model.isFinite() && implied.isFinite()) {
            val px = implied.coerceIn(0.001, 0.999)
            if (px + 1e-12 < com.dirk.kalshiodds.signal.flip.FlipCheck.CHEAP_ASK &&
                model < com.dirk.kalshiodds.signal.flip.FlipCheck.CHEAP_FLIP_SUPPORT
            ) {
                return false
            }
            if (!com.dirk.kalshiodds.signal.flip.FlipCheck.beatsAllIn(model, px)) {
                return false
            }
        }
        return true
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
        closeEpochMs: Long? = null,
        fairYes: Double? = null
    ): Card {
        val resolved = resolve(side, modelYes, marketYes, fairYes)
        return Card(
            title = WindowLabel.of(ticker, closeEpochMs),
            call = resolved.call,
            modelLine = modelVsMarketLine(modelYes, marketYes, resolved.lineSide),
            outcome = outcomeLabel(settled),
            details = mergeDetails(resolved.detailsExtra, details),
            stableKey = "$ticker:${closeEpochMs ?: 0}:${side ?: ""}"
        )
    }

    /**
     * Live-alert mapper. Prefers `AI N% vs mkt M%` from [SignalAlert.reason]
     * so the line matches the home card (AI − market). Falls back to
     * [SignalAlert.fairValuePp] / [SignalAlert.marketMidPp] when the
     * diagnostic has no AI pair. Stored [SignalAlert.deltaPp] is fair − mid.
     */
    fun card(alert: SignalAlert, settled: String? = null): Card {
        val resolved = resolve(alert)
        return Card(
            title = WindowLabel.of(alert.ticker),
            call = resolved.call,
            modelLine = modelVsMarketLine(resolved.modelYes, resolved.marketYes, resolved.lineSide),
            outcome = outcomeLabel(settled),
            details = mergeDetails(resolved.detailsExtra, detailsForAlert(alert)),
            stableKey = "${alert.ticker}:${alert.id}:${alert.createdAtMs}"
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

    private fun mergeDetails(extra: String?, body: String?): String? {
        val parts = listOfNotNull(
            extra?.trim()?.takeIf { it.isNotEmpty() },
            body?.trim()?.takeIf { it.isNotEmpty() }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private val AI_VS_MKT = Regex("""AI\s+(\d+(?:\.\d+)?)%\s+vs\s+mkt\s+(\d+(?:\.\d+)?)%""", RegexOption.IGNORE_CASE)
}
