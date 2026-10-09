package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.decision.AutopilotMinStake
import com.dirk.kalshiodds.decision.LiveBalancePolicy
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot

/**
 * 0.3.40: the per-market Autopilot step that OddsViewModel actually runs, extracted so unit
 * tests exercise the real execution path (paper tick → sizing → shadow record → dispatch gate →
 * live claim). The only thing left to the caller is the network send for [Outcome.Send].
 *
 * Sizing rules (owner, enforced here for every mode):
 *  - LIVE: stake = Kelly fraction × the FRESH real Kalshi balance (≤ 15 min old). Missing or
 *    stale balance → no order. The paper bankroll is never used for a live size.
 *  - Any computed Kelly stake (paper, shadow or live) under $5 → NO BET "below $5 minimum".
 */
object AutopilotStep {
    data class Live(
        val cashUsd: Double?,
        val cashAtMs: Long?,
        val armed: Boolean,
        val credentialsOk: Boolean,
        val backoffBlocked: Boolean
    )

    sealed class Outcome {
        data class Skip(val reason: String?) : Outcome()
        /** Paper fill booked (or skipped by paper gates) — PAPER mode never goes further. */
        data class Paper(val fill: PaperFill?) : Outcome()
        data class ShadowOnly(val ticket: ShadowTicket, val reason: String) : Outcome()
        /** The caller must send exactly this ticket once, with [ShadowTicket.clientOrderId]. */
        data class Send(val ticket: ShadowTicket, val dayKey: String, val sized: PaperKellySizer.Result) : Outcome()
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
        live: Live,
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
            bookPaper = mode != AutopilotMode.SHADOW,
            assessment = assessment
        )
        val picked = tick.decision.side
        if (!tick.decision.ok || picked == null) return Outcome.Skip(tick.decision.reason)
        if (mode == AutopilotMode.PAPER) return Outcome.Paper(tick.fill)
        val depth = if (picked.side.equals("NO", true)) noDepth else yesDepth
        val sized: PaperKellySizer.Result = if (mode == AutopilotMode.LIVE) {
            val fresh = LiveBalancePolicy.fresh(live.cashUsd, live.cashAtMs, nowMs)
            val liveSized = if (fresh) {
                PaperKellySizer.size(
                    winProb = picked.winProb,
                    ask = picked.ask,
                    bankrollUsd = live.cashUsd ?: 0.0,
                    kellyFraction = settings.paperKellyFraction,
                    feeRate = settings.feeRate,
                    depthContracts = depth
                )
            } else {
                null
            }
            val pre = LiveAutopilotPreflight.check(
                armed = live.armed,
                decisionOk = tick.decision.ok,
                balanceFresh = fresh,
                backoffBlocked = live.backoffBlocked,
                kellyOk = liveSized?.ok == true,
                allInUsd = liveSized?.allInUsd ?: 0.0
            )
            if (!pre.ok || liveSized == null) {
                val why = if (!pre.ok) pre.reason else (liveSized?.reason ?: "NO BET — balance unavailable")
                paperBook.rememberMessage(why)
                return Outcome.Skip(why)
            }
            liveSized
        } else {
            val q = AutopilotOrderSize.quote(
                decision = tick.decision,
                fill = tick.fill,
                depth = depth,
                kellyFraction = settings.paperKellyFraction,
                feeRate = settings.feeRate,
                cashUsd = paperBook.snapshot().cashUsd
            )
            if (q.ok && AutopilotMinStake.below(q.allInUsd)) return Outcome.Skip(AutopilotMinStake.REASON)
            q
        }
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
        val ticket = recorded.ticket
        val dispatch = AutopilotDispatch.decide(
            AutopilotDispatch.Request(
                mode = mode,
                masterOn = settings.aiPaperAutopilotEnabled,
                decisionOk = true,
                paperFilled = tick.fill != null,
                armed = live.armed,
                credentialsOk = live.credentialsOk,
                failClosed = live.backoffBlocked,
                paperSide = picked.side,
                paperPrice = picked.ask,
                shadowSide = ticket.side,
                shadowPrice = ticket.limitPrice,
                shadowDepthFill = ticket.depthFill,
                shadowAllInUsd = ticket.stakeUsd,
                alreadyAttempted = shadowBook.snapshot().attempted(ticket.clientOrderId)
            )
        )
        if (!dispatch.shouldPlace || !recorded.isNew) return Outcome.ShadowOnly(ticket, dispatch.reason)
        // Belt and braces: the exact ticket about to be sent must itself respect the $5 floor.
        if (AutopilotMinStake.below(ticket.stakeUsd)) return Outcome.Skip(AutopilotMinStake.REASON)
        val day = LiveAutopilotGate.dayKey(nowMs)
        if (!shadowBook.claimLive(ticket.clientOrderId, day, ticket.stakeUsd)) {
            return Outcome.Skip("This client_order_id was already attempted")
        }
        return Outcome.Send(ticket, day, sized)
    }
}
