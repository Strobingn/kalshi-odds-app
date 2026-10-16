package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.decision.AutopilotMinStake
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot

/**
 * 0.3.40: the per-market Autopilot step OddsViewModel runs, extracted so tests exercise the real path.
 *
 * Owner decision: Autopilot is PAPER-ONLY. This object has no network client, no order sender and no
 * armed state; its outcomes are paper fills or shadow records ("what would have been sent", never sent).
 *
 * Sizing: half-Kelly on the free paper bankroll — no caps beyond that bankroll, displayed depth and fees
 * on both legs. Any Kelly stake under $5 → NO BET "below $5 minimum" (skipped, never rounded up).
 */
object AutopilotStep {
    sealed class Outcome {
        data class Skip(val reason: String?) : Outcome()
        /** PAPER mode: the paper fill booked (null when the book refused it). */
        data class Paper(val fill: PaperFill?) : Outcome()
        /** SHADOW mode: the would-be order, recorded and never sent. */
        data class ShadowOnly(val ticket: ShadowTicket, val reason: String) : Outcome()
    }

    fun run(
        paperBook: PaperBook,
        shadowBook: ShadowBook,
        market: MarketUiModel,
        settings: SignalSettings,
        mode: AutopilotMode,
        nowMs: Long,
        yesAsk: Double?,
        noAsk: Double?,
        yesDepth: Int?,
        noDepth: Int?,
        book: BookLevelSnapshot?,
        assessment: com.dirk.kalshiodds.decision.DecisionPipeline.Assessment?,
        clientOrderId: String,
        reasonPrefix: String = "Autopilot edge"
    ): Outcome {
        val tick = PaperAutopilot.tick(
            paperBook = paperBook,
            market = market,
            settings = settings,
            nowMs = nowMs,
            yesAsk = yesAsk,
            noAsk = noAsk,
            yesDepth = yesDepth,
            noDepth = noDepth,
            book = book,
            bookPaper = mode == AutopilotMode.PAPER,
            assessment = assessment
        )
        val picked = tick.decision.side
        if (!tick.decision.ok || picked == null) return Outcome.Skip(tick.decision.reason)
        if (mode == AutopilotMode.PAPER) return Outcome.Paper(tick.fill)
        val depth = if (picked.side.equals("NO", true)) noDepth else yesDepth
        val sized = PaperKellySizer.size(
            winProb = picked.winProb,
            ask = picked.ask,
            bankrollUsd = tick.decision.freeBankrollUsd,
            kellyFraction = PaperAutopilot.PAPER_AI_KELLY_FRACTION,
            feeRate = settings.feeRate,
            depthContracts = depth,
            capFullKelly = false
        )
        if (!sized.ok) return Outcome.Skip(sized.reason)
        if (AutopilotMinStake.below(sized.allInUsd)) return Outcome.Skip(AutopilotMinStake.REASON)
        val tags = AutopilotRegime.tags(market, picked.side, picked.ask, nowMs)
        val draft = ShadowOrderPayload.fromKelly(
            ticker = market.ticker,
            side = picked.side,
            sized = sized,
            depth = depth,
            reason = "$reasonPrefix ${String.format(java.util.Locale.US, "%.1f¢", picked.evPerContract * 100)} after fees",
            nowMs = nowMs,
            clientOrderId = clientOrderId,
            regimeKey = tags.key
        )
        val recorded = shadowBook.record(draft)
        return Outcome.ShadowOnly(recorded.ticket, "SHADOW — recorded, never sent")
    }
}
