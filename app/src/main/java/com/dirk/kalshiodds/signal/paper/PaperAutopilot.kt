package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.flip.FlipCheck
import com.dirk.kalshiodds.signal.model.ProbabilityClamp
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

/**
 * Autonomous **paper** autopilot. Places Kelly-sized paper fills whenever
 * the model sees positive EV after fees, on either side, at any time in
 * the window. Never calls Kalshi. Never places a live order.
 *
 * Side is chosen by EV at the ask (`p − ask − fee`), not by favorite /
 * spot-vs-strike. Flip-chance still blocks near-impossible lottery prints.
 * A re-entry guard skips the identical ticker+side when price and EV have
 * not moved enough to justify another clip.
 */
object PaperAutopilot {

    const val SOURCE = "AI paper autopilot"
    const val PRICE_DELTA = 0.02
    const val PROB_DELTA = 0.03
    const val KELLY_DELTA = 0.05

    /** Minimum post-fee edge, in probability points (4¢ per $1 contract). */
    const val MIN_EDGE = 0.04
    const val EDGE_HOLDS = 2
    const val MIN_ASK = 0.10
    const val MAX_ASK = 0.90
    const val ANCHOR = 0.40
    const val WINDOW_BANKROLL_FRACTION = 0.05
    const val HALF_KELLY = 0.5
    const val LAST_WINDOW_MS = 60_000L

    private data class Hold(val side: String, val count: Int)

    private val edgeHolds = ConcurrentHashMap<String, Hold>()
    private val clampWindows = ConcurrentHashMap.newKeySet<String>()

    /** Test hook. Production ticks share one process-lifetime streak. */
    fun resetSession() {
        edgeHolds.clear()
        clampWindows.clear()
    }

    data class SideEv(
        val side: String,
        val displaySide: String,
        val winProb: Double,
        val ask: Double,
        val evPerContract: Double,
        val feePerContract: Double
    )

    data class Decision(
        val skip: Boolean,
        val reason: String? = null,
        val side: SideEv? = null,
        val kellyF: Double = 0.0,
        val freeBankrollUsd: Double = 0.0,
        val maxStakeUsd: Double? = null
    ) {
        val ok: Boolean get() = !skip && side != null
    }

    /** p = market + 0.4 × (model − market). */
    fun anchoredYes(modelYes: Double, marketYes: Double): Double =
        (marketYes + ANCHOR * (modelYes - marketYes)).coerceIn(0.0, 1.0)

    /** Bankroll minus money already riding on open paper positions. */
    fun freeBankroll(paper: PaperBookState): Double =
        (paper.paperBankrollUsd - paper.openStakeUsd).coerceAtLeast(0.0)

    /**
     * One budget for every clip in the window: half of the capped Kelly
     * edge, and never more than 5% of free bankroll.
     */
    fun windowBudgetUsd(freeBankrollUsd: Double, rawFullKelly: Double): Double {
        if (!freeBankrollUsd.isFinite() || freeBankrollUsd <= 0.0) return 0.0
        if (!rawFullKelly.isFinite() || rawFullKelly <= 0.0) return 0.0
        val f = rawFullKelly.coerceAtMost(PaperKellySizer.MAX_FULL_KELLY_F)
        val half = freeBankrollUsd * f * HALF_KELLY
        val five = freeBankrollUsd * WINDOW_BANKROLL_FRACTION
        return min(half, five)
    }

    fun spentInWindow(fills: List<PaperFill>, ticker: String): Double =
        fills.filter { it.ticker.equals(ticker, ignoreCase = true) }.sumOf { it.stakeUsd }

    /** First paper fill in the window locks the side. */
    fun committedSide(fills: List<PaperFill>, ticker: String): String? =
        fills.filter { it.ticker.equals(ticker, ignoreCase = true) }
            .minByOrNull { it.createdAtMs }
            ?.side

    fun marketYes(market: MarketUiModel, yesAsk: Double?, noAsk: Double?): Double? {
        market.yesProbabilityPercent?.takeIf { it.isFinite() && it > 0.0 && it < 100.0 }
            ?.let { return (it / 100.0).coerceIn(0.0, 1.0) }
        val bid = KalshiPrice.usable(market.yesBid)
        val ask = KalshiPrice.usable(yesAsk)
        if (bid != null && ask != null) return ((bid + ask) / 2.0).coerceIn(0.0, 1.0)
        val no = KalshiPrice.usable(noAsk)
        if (ask != null && no != null) return ((ask + (1.0 - no)) / 2.0).coerceIn(0.0, 1.0)
        return ask ?: no?.let { (1.0 - it).coerceIn(0.0, 1.0) }
    }

    fun secondsLeft(market: MarketUiModel, nowMs: Long): Long? {
        val close = market.closeTimeEpochMs ?: return null
        return close - nowMs
    }

    /**
     * Per-contract EV at the live ask after the official taker fee:
     * `p × $1 − (ask + fee)`.
     */
    fun evPerContract(
        winProb: Double,
        ask: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double? {
        val px = KalshiPrice.usable(ask) ?: return null
        if (!winProb.isFinite()) return null
        val cost = KalshiFee.totalCost(1, px, feeRate)
        if (!cost.isFinite() || cost <= 0.0) return null
        return winProb * SignalConstants.CONTRACT_SETTLEMENT_USD - cost
    }

    /**
     * Pick the side whose EV at the ask is higher. Skip when both
     * `p − ask − fee` are ≤ [margin].
     */
    fun pickSide(
        pYes: Double,
        yesAsk: Double?,
        noAsk: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        margin: Double = 0.0
    ): SideEv? {
        if (!pYes.isFinite()) return null
        val yes = sideEv("YES", "UP", pYes.coerceIn(0.0, 1.0), yesAsk, feeRate)
        val no = sideEv("NO", "DOWN", (1.0 - pYes).coerceIn(0.0, 1.0), noAsk, feeRate)
        val best = listOfNotNull(yes, no).maxByOrNull { it.evPerContract } ?: return null
        if (best.evPerContract <= margin + 1e-12) return null
        return best
    }

    fun modelYes(market: MarketUiModel): Double? {
        val lm = market.lastMinute?.pUp?.takeIf { it.isFinite() }
        if (lm != null) return lm
        return TicketBuilder.modelProb(market, "YES")
    }

    fun materiallyChanged(
        last: PaperFill?,
        side: String,
        ask: Double,
        winProb: Double,
        kellyF: Double
    ): Boolean {
        if (last == null) return true
        if (!last.side.equals(side, ignoreCase = true)) return true
        if (abs(ask - last.limitPrice) + 1e-12 >= PRICE_DELTA) return true
        val lastP = last.aiPct?.let { if (it <= 1.0 + 1e-9) it else it / 100.0 }
        if (lastP != null && abs(winProb - lastP) + 1e-12 >= PROB_DELTA) return true
        val lastK = last.kellyF
        if (lastK != null && abs(kellyF - lastK) + 1e-12 >= KELLY_DELTA) return true
        return false
    }

    fun lastSameSide(fills: List<PaperFill>, ticker: String, side: String): PaperFill? =
        fills.filter {
            it.ticker.equals(ticker, ignoreCase = true) && it.side.equals(side, ignoreCase = true)
        }.maxByOrNull { it.createdAtMs }

    fun evaluate(
        market: MarketUiModel,
        settings: SignalSettings,
        paper: PaperBookState,
        nowMs: Long,
        yesAsk: Double? = market.yesAsk,
        noAsk: Double? = market.noAsk,
        yesDepth: Int? = null,
        noDepth: Int? = null
    ): Decision {
        if (!settings.paperTradingEnabled) {
            return Decision(skip = true, reason = "Paper trading off")
        }
        if (!settings.aiPaperAutopilotEnabled) {
            return Decision(skip = true, reason = "AI paper autopilot off")
        }
        if (!CryptoMarkets.isAutopilotTicker(market.ticker)) {
            return Decision(skip = true, reason = "Paper skip — not BTC, ETH, or SOL 15m")
        }
        if (!MarketLifecycle.isTradable(market, nowMs)) {
            return Decision(skip = true, reason = "Paper skip — window closed")
        }
        val left = secondsLeft(market, nowMs)
        if (left != null && left <= LAST_WINDOW_MS) {
            return Decision(skip = true, reason = "Paper skip — last 60 seconds")
        }
        val rawYes = modelYes(market)
            ?: return Decision(skip = true, reason = "Paper skip — no model win probability")
        if (clampWindows.contains(market.ticker) || ProbabilityClamp.binding(rawYes)) {
            clampWindows.add(market.ticker)
            return Decision(skip = true, reason = "Paper skip — model is clamped at 2–98%")
        }
        val marketP = marketYes(market, yesAsk, noAsk)
            ?: return Decision(skip = true, reason = "Paper skip — no market probability")
        val pYes = anchoredYes(rawYes, marketP)
        val picked = pickSide(pYes, yesAsk, noAsk, settings.feeRate, margin = MIN_EDGE)
            ?: return Decision(skip = true, reason = "Paper skip — edge under 4pp after fees").also {
                edgeHolds.remove(market.ticker)
            }
        if (picked.ask + 1e-12 < MIN_ASK || picked.ask - 1e-12 > MAX_ASK) {
            return Decision(skip = true, reason = "Paper skip — ask outside 10¢–90¢", side = picked)
        }
        val locked = committedSide(paper.fills, market.ticker)
        if (locked != null && !locked.equals(picked.side, ignoreCase = true)) {
            return Decision(skip = true, reason = "Paper skip — opposite side already filled", side = picked)
        }
        if (picked.evPerContract + 1e-12 < MIN_EDGE) {
            edgeHolds.remove(market.ticker)
            return Decision(skip = true, reason = "Paper skip — edge under 4pp after fees", side = picked)
        }
        val held = noteEdge(market.ticker, picked.side)
        if (held < EDGE_HOLDS) {
            return Decision(
                skip = true,
                reason = "Paper skip — edge needs 2 consecutive evaluations",
                side = picked
            )
        }
        if (!FlipCheck.allowsRealisticMarketSide(
                market = market,
                side = picked.side,
                ask = picked.ask,
                nowMs = nowMs
            )
        ) {
            return Decision(skip = true, reason = "Paper skip — flip-chance / lottery block", side = picked)
        }
        val free = freeBankroll(paper)
        val rawF = PaperKellySizer.fullKelly(picked.winProb, picked.ask, settings.feeRate)
        val budget = windowBudgetUsd(free, rawF)
        val room = budget - spentInWindow(paper.fills, market.ticker)
        val depth = if (picked.side.equals("NO", true)) noDepth else yesDepth
        val kellyQuote = PaperKellySizer.size(
            winProb = picked.winProb,
            ask = picked.ask,
            bankrollUsd = free,
            kellyFraction = settings.paperKellyFraction,
            feeRate = settings.feeRate,
            depthContracts = null,
            maxStakeUsd = room
        )
        if (kellyQuote.ok && com.dirk.kalshiodds.decision.AutopilotMinStake.below(kellyQuote.allInUsd)) {
            return Decision(
                skip = true,
                reason = com.dirk.kalshiodds.decision.AutopilotMinStake.REASON,
                side = picked,
                kellyF = kellyQuote.kellyF,
                freeBankrollUsd = free,
                maxStakeUsd = room
            )
        }
        val sized = PaperKellySizer.size(
            winProb = picked.winProb,
            ask = picked.ask,
            bankrollUsd = free,
            kellyFraction = settings.paperKellyFraction,
            feeRate = settings.feeRate,
            depthContracts = depth,
            maxStakeUsd = room
        )
        if (!sized.ok) {
            return Decision(
                skip = true,
                reason = if (room + 1e-9 < sized.costPerContract) {
                    "Paper skip — window Kelly budget spent"
                } else {
                    sized.reason ?: "Paper skip — Kelly ≤ 0 after fees"
                },
                side = picked,
                kellyF = sized.kellyF,
                freeBankrollUsd = free,
                maxStakeUsd = room
            )
        }
        val last = lastSameSide(paper.fills, market.ticker, picked.side)
        if (!materiallyChanged(last, picked.side, picked.ask, picked.winProb, sized.kellyF)) {
            return Decision(
                skip = true,
                reason = "Paper skip — same side / price / EV as last fill",
                side = picked,
                kellyF = sized.kellyF,
                freeBankrollUsd = free,
                maxStakeUsd = room
            )
        }
        return Decision(
            skip = false,
            side = picked,
            kellyF = sized.kellyF,
            freeBankrollUsd = free,
            maxStakeUsd = room
        )
    }

    private fun noteEdge(ticker: String, side: String): Int {
        val prev = edgeHolds[ticker]
        val count = if (prev != null && prev.side.equals(side, ignoreCase = true)) prev.count + 1 else 1
        edgeHolds[ticker] = Hold(side, count)
        return count
    }

    data class Tick(val decision: Decision, val fill: PaperFill?)

    /**
     * Evaluate and, if the EV rule fires, optionally book a Kelly paper fill.
     * Pure paper path — never calls Kalshi and never places a live order.
     * [bookPaper] false still runs the gates (shadow mode) and stamps no fill.
     */
    fun tick(
        paperBook: PaperBook,
        market: MarketUiModel,
        settings: SignalSettings,
        nowMs: Long,
        yesAsk: Double? = market.yesAsk,
        noAsk: Double? = market.noAsk,
        yesDepth: Int? = null,
        noDepth: Int? = null,
        book: BookLevelSnapshot? = null,
        bookPaper: Boolean = true
    ): Tick {
        val liveYes = if (book != null && !book.isEmpty()) {
            TicketBuilder.bookAskOrNull("YES", book)
        } else {
            yesAsk
        }
        val liveNo = if (book != null && !book.isEmpty()) {
            TicketBuilder.bookAskOrNull("NO", book)
        } else {
            noAsk
        }
        val decision = evaluate(
            market = market,
            settings = settings,
            paper = paperBook.snapshot(),
            nowMs = nowMs,
            yesAsk = liveYes,
            noAsk = liveNo,
            yesDepth = yesDepth,
            noDepth = noDepth
        )
        if (!decision.ok) {
            decision.reason?.let { paperBook.rememberMessage(it) }
            return Tick(decision, null)
        }
        if (!bookPaper) return Tick(decision, null)
        val picked = decision.side ?: return Tick(decision, null)
        val depth = if (picked.side.equals("NO", true)) noDepth else yesDepth
        val tags = AutopilotRegime.tags(market, picked.side, picked.ask, nowMs)
        val fill = paperBook.considerAutopilot(
            ticker = market.ticker,
            side = picked.side,
            ask = picked.ask,
            winProb = picked.winProb,
            depthContracts = depth,
            evPerContract = picked.evPerContract,
            enabled = true,
            bankrollUsd = decision.freeBankrollUsd,
            maxStakeUsd = decision.maxStakeUsd,
            regime = tags
        )
        return Tick(decision, fill)
    }

    /**
     * Evaluate and, if the EV rule fires, book a Kelly paper fill.
     * Pure paper path — never calls Kalshi and never places a live order.
     */
    fun consider(
        paperBook: PaperBook,
        market: MarketUiModel,
        settings: SignalSettings,
        nowMs: Long,
        yesAsk: Double? = market.yesAsk,
        noAsk: Double? = market.noAsk,
        yesDepth: Int? = null,
        noDepth: Int? = null,
        book: BookLevelSnapshot? = null
    ): PaperFill? = tick(
        paperBook, market, settings, nowMs, yesAsk, noAsk, yesDepth, noDepth, book, bookPaper = true
    ).fill

    private fun sideEv(
        side: String,
        display: String,
        winProb: Double,
        ask: Double?,
        feeRate: Double
    ): SideEv? {
        val px = KalshiPrice.usable(ask) ?: return null
        val ev = evPerContract(winProb, px, feeRate) ?: return null
        return SideEv(
            side = side,
            displaySide = display,
            winProb = winProb,
            ask = px,
            evPerContract = ev,
            feePerContract = KalshiFee.total(1, px, feeRate)
        )
    }
}
