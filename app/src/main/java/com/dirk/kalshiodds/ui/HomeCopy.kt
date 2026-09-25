package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.TimeLeft
import com.dirk.kalshiodds.signal.trade.BetCall
import java.util.Locale
import kotlin.math.abs

/**
 * Home-screen display copy. Formatting only — never changes a [BetCall]
 * or a ticket. Wired from [WiringAuditTest] so every line stays a real method.
 */
object HomeCopy {
    const val TITLE = "DipHunter"
    const val THIS_WINDOW = "This window"
    const val BUY_ANYWAY = "Buy anyway"
    const val NEED_20 = "need 20+ results"
    const val WINDOW_LENGTH = "15m"

    fun coinShort(market: MarketUiModel): String = when (CryptoMarkets.kindFor(market.ticker)) {
        SeriesKind.BTC -> "BTC"
        SeriesKind.ETH -> "ETH"
        SeriesKind.SOL -> "SOL"
        SeriesKind.CRYPTO -> market.seriesLabel.take(3).uppercase(Locale.US)
    }

    fun thisWindowHeadline(
        decision: BetCall.Decision?,
        market: MarketUiModel?,
        nowMs: Long
    ): String {
        if (decision == null || market == null) return "NO BET this window"
        if (!decision.isActionable) {
            val reason = decision.noBetReason?.trim().orEmpty()
            return if (reason.isEmpty()) "NO BET this window" else "NO BET this window · $reason"
        }
        val profit = decision.profitIfWinUsd?.let { String.format(Locale.US, "$%.2f", it) } ?: "—"
        return "${decision.label}  ${coinShort(market)}  · $5 wins $profit profit · ${closesIn(market.closeTimeEpochMs, nowMs)}"
    }

    fun closesIn(closeEpochMs: Long?, nowMs: Long): String {
        val raw = TimeLeft.format(closeEpochMs, nowMs)
        return when {
            raw == "—" || raw == "Expired" -> raw
            raw.endsWith(" left") -> "closes in ${raw.removeSuffix(" left")}"
            else -> "closes in $raw"
        }
    }

    /**
     * Compact scorecard line from the existing snapshot totals
     * (`modelScoreCorrect` / `modelScoreTotal` / `modelMeanBrier`).
     * [brier] is the picked-side Brier. Hidden until N ≥ 20.
     */
    fun pickedSideLine(correct: Int?, total: Int?): String {
        if (correct == null || total == null || total <= 0) return "Picked side: —"
        val pct = ((100.0 * correct) / total).toInt()
        return "Picked side: $correct/$total correct ($pct%)"
    }

    fun pickedSideBrierLine(total: Int?, brier: Double?): String {
        if (total == null || total < 20) return NEED_20
        return brier?.let { String.format(Locale.US, "Picked-side Brier %.3f", it) } ?: "—"
    }

    fun pUpBrierLine(total: Int?, brier: Double?): String {
        if (total == null || total < 20) return NEED_20
        return brier?.let { String.format(Locale.US, "P(UP) Brier %.3f", it) } ?: "—"
    }

    fun scorecardLine(correct: Int?, total: Int?, brier: Double?): String {
        val head = pickedSideLine(correct, total)
        if (correct == null || total == null || total <= 0) return "$head · $NEED_20"
        return if (total < 20) {
            "$head · $NEED_20"
        } else {
            val brierPart = pickedSideBrierLine(total, brier)
            if (brierPart == NEED_20 || brierPart == "—") head else "$head · $brierPart"
        }
    }

    fun modelVsMarket(market: MarketUiModel, decision: BetCall.Decision): String {
        val modelYes = market.importedModelPp ?: market.aiYesPercent
        val marketYes = market.yesProbabilityPercent
        val up = when (decision.headline) {
            BetCall.Headline.BET_DOWN -> false
            BetCall.Headline.BET_UP -> true
            BetCall.Headline.NO_BET -> (modelYes ?: 50.0) >= 50.0
        }
        val side = if (up) "UP" else "DOWN"
        val modelSide = modelYes?.let { if (up) it else 100.0 - it }
        val marketSide = marketYes?.let { if (up) it else 100.0 - it }
        val edge = when {
            modelSide != null && marketSide != null -> modelSide - marketSide
            else -> market.edgePp?.let { if (up) it else -it }
        }
        val modelTxt = modelSide?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val marketTxt = marketSide?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
        val edgeTxt = edge?.let { String.format(Locale.US, "%+.0f pts", it) } ?: "—"
        return "Model $modelTxt $side vs market $marketTxt · edge $edgeTxt"
    }

    fun allInProfit(decision: BetCall.Decision): String? {
        if (!decision.isActionable) return null
        val profit = decision.profitIfWinUsd ?: return null
        return String.format(Locale.US, "$5 all-in → profit $%.2f if it wins", profit)
    }

    fun primaryButtonLabel(mode: String, decision: BetCall.Decision): String {
        val side = when (decision.headline) {
            BetCall.Headline.BET_UP -> "UP"
            BetCall.Headline.BET_DOWN -> "DOWN"
            BetCall.Headline.NO_BET -> "NO BET"
        }
        return "$mode  $side"
    }

    fun buyAnywaySide(market: MarketUiModel, decision: BetCall.Decision): String {
        val raw = decision.side ?: market.predictedSide ?: market.primaryHeroSide ?: "YES"
        return if (raw.equals("NO", true)) "NO" else "YES"
    }

    fun targetText(market: MarketUiModel): String? {
        val strike = market.floorStrike?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return String.format(Locale.US, "Target $%,.0f", strike)
    }

    fun spotDeltaText(market: MarketUiModel): String? {
        val d = market.spotVsTargetUsd?.takeIf { it.isFinite() } ?: return null
        val sign = if (d >= 0.0) "+" else "−"
        return String.format(Locale.US, "%s$%,.0f", sign, abs(d))
    }

    fun spotDeltaAbove(market: MarketUiModel): Boolean = (market.spotVsTargetUsd ?: 0.0) >= 0.0
}
