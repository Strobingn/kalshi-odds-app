package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.TimeLeft
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.trade.BetCall
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    const val SIGNAL_HISTORY = "Signal history"
    const val NO_SETTLED_PICKS = HomeScorecardSummary.NO_SETTLED
    const val SCORECARD_CONTENT_DESCRIPTION = "Scorecard"
    const val SETTINGS_CONTENT_DESCRIPTION = "Settings"
    const val REFRESH_CONTENT_DESCRIPTION = "Refresh"

    /** 0.3.12 top bar: title, LIVE chip, scorecard, settings, refresh. */
    val TOP_BAR_ACTIONS: List<String> = listOf(
        SCORECARD_CONTENT_DESCRIPTION,
        SETTINGS_CONTENT_DESCRIPTION,
        REFRESH_CONTENT_DESCRIPTION
    )

    /** Home never renders a Signals list; cards live on [SignalHistoryScreen]. */
    const val SHOWS_SIGNAL_LIST = false

    fun signalHistoryLink(): String = SIGNAL_HISTORY

    fun scorecardSummaryOf(
        entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>,
        paperPnlUsd: Double
    ): HomeScorecardSummary = HomeScorecardSummary.of(entries, paperPnlUsd)

    fun scorecardSummaryLine(summary: HomeScorecardSummary): String {
        if (summary.settledCount <= 0) return HomeScorecardSummary.NO_SETTLED
        val pct = ((summary.hitRate ?: 0.0) * 100.0).roundToInt()
        return "${summary.wins}-${summary.losses} · $pct% · ${HomeScorecardSummary.paperPnlPart(summary.paperPnlUsd)}"
    }

    fun scorecardSummaryLine(
        entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>,
        paperPnlUsd: Double
    ): String = scorecardSummaryLine(scorecardSummaryOf(entries, paperPnlUsd))

    fun signalCardsOnHome(alerts: List<com.dirk.kalshiodds.signal.model.SignalAlert>): List<SignalCopy.Card> {
        if (SHOWS_SIGNAL_LIST) return alerts.map { SignalCopy.card(it) }
        return emptyList()
    }

    fun coinShort(market: MarketUiModel): String = coinShort(market.ticker, market.seriesLabel)

    fun coinShort(seriesOrTicker: String, seriesLabel: String? = null): String = when (CryptoMarkets.kindFor(seriesOrTicker)) {
        SeriesKind.BTC -> "BTC"
        SeriesKind.ETH -> "ETH"
        SeriesKind.SOL -> "SOL"
        SeriesKind.CRYPTO -> (seriesLabel ?: seriesOrTicker).take(3).uppercase(Locale.US)
    }

    fun thisWindowHeadline(
        decision: BetCall.Decision?,
        market: MarketUiModel?,
        nowMs: Long
    ): String {
        if (decision == null || market == null) return "NO BET this window"
        if (!decision.isActionable) {
            return noBetHeadline(decision.noBetReason)
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
        val pct = ((100.0 * correct) / total).roundToInt()
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

    const val NO_BET_WINDOW = "NO BET this window"
    const val SIT_OUT_HOME =
        "NO BET this window. The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative."

    fun noBetHeadline(reason: String?): String {
        val raw = reason?.trim().orEmpty()
        if (raw.isEmpty()) return NO_BET_WINDOW
        if (isSitOutJargon(raw)) return SIT_OUT_HOME
        return "$NO_BET_WINDOW · $raw"
    }

    fun isSitOutJargon(reason: String): Boolean {
        val u = reason.lowercase()
        return u.contains("brier") ||
            u.contains("log-loss") ||
            u.contains("log loss") ||
            u.contains("sit out") ||
            u.contains("sitting out") ||
            u.contains("hasn't beaten kalshi") ||
            u.contains("expected value is negative")
    }

    const val AI_EM_DASH = "AI —"

    data class TileAiPercents(
        val up: String,
        val down: String,
        val upPct: Int?,
        val downPct: Int?
    )

    /**
     * UP / DOWN tile AI lines. Same [SignalStance.homeModelYes] used by the
     * model-vs-market line, signal side, and ticket. DOWN is 100 − rounded UP
     * so the two always sum to 100. Missing model → [AI_EM_DASH].
     */
    fun tileAiPercents(market: MarketUiModel): TileAiPercents {
        val modelYes = SignalStance.homeModelYes(market.importedModelPp, market.aiYesPercent)
            ?: return TileAiPercents(AI_EM_DASH, AI_EM_DASH, null, null)
        val up = modelYes.roundToInt().coerceIn(0, 100)
        val down = 100 - up
        return TileAiPercents("AI $up%", "AI $down%", up, down)
    }

    fun tileAiUp(market: MarketUiModel): String = tileAiPercents(market).up

    fun tileAiDown(market: MarketUiModel): String = tileAiPercents(market).down

    fun modelVsMarket(market: MarketUiModel, decision: BetCall.Decision): String {
        val modelYes = SignalStance.homeModelYes(market.importedModelPp, market.aiYesPercent)
        val marketYes = market.yesProbabilityPercent
        val side = when (decision.headline) {
            BetCall.Headline.BET_DOWN -> "DOWN"
            BetCall.Headline.BET_UP -> "UP"
            BetCall.Headline.NO_BET -> if ((modelYes ?: 50.0) >= 50.0) "UP" else "DOWN"
        }
        return SignalCopy.modelVsMarketLine(modelYes, marketYes, side)
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

    /** Confirm-sheet / ticket Approve button — same [mode] as [TradeModeLabel.forApprove]. */
    fun confirmApproveLabel(mode: String, stakeUsd: Double, isSell: Boolean): String = when {
        isSell && mode == com.dirk.kalshiodds.signal.trade.TradeModeLabel.PAPER -> "PAPER sell"
        isSell && mode == com.dirk.kalshiodds.signal.trade.TradeModeLabel.LIVE -> "LIVE $ sell"
        mode == com.dirk.kalshiodds.signal.trade.TradeModeLabel.LIVE ->
            String.format(Locale.US, "LIVE $%.2f", stakeUsd)
        else -> mode
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
