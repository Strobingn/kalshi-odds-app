package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.flip.FlipCheck
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import kotlin.math.abs

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
        val kellyF: Double = 0.0
    ) {
        val ok: Boolean get() = !skip && side != null
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
        if (!CryptoMarkets.isLiveTicker(market.ticker)) {
            return Decision(skip = true, reason = "Paper skip — Bitcoin-only")
        }
        if (!MarketLifecycle.isTradable(market, nowMs)) {
            return Decision(skip = true, reason = "Paper skip — window closed")
        }
        val pYes = modelYes(market)
            ?: return Decision(skip = true, reason = "Paper skip — no model win probability")
        val picked = pickSide(pYes, yesAsk, noAsk, settings.feeRate)
            ?: return Decision(skip = true, reason = "Paper skip — both sides ≤ EV margin")
        if (!FlipCheck.allowsRealisticMarketSide(
                market = market,
                side = picked.side,
                ask = picked.ask,
                nowMs = nowMs
            )
        ) {
            return Decision(skip = true, reason = "Paper skip — flip-chance / lottery block", side = picked)
        }
        val depth = if (picked.side.equals("NO", true)) noDepth else yesDepth
        val sized = PaperKellySizer.size(
            winProb = picked.winProb,
            ask = picked.ask,
            bankrollUsd = paper.paperBankrollUsd.coerceAtLeast(paper.cashUsd),
            kellyFraction = settings.paperKellyFraction,
            feeRate = settings.feeRate,
            depthContracts = depth
        )
        if (!sized.ok) {
            return Decision(
                skip = true,
                reason = sized.reason ?: "Paper skip — Kelly ≤ 0 after fees",
                side = picked,
                kellyF = sized.kellyF
            )
        }
        val last = lastSameSide(paper.fills, market.ticker, picked.side)
        if (!materiallyChanged(last, picked.side, picked.ask, picked.winProb, sized.kellyF)) {
            return Decision(
                skip = true,
                reason = "Paper skip — same side / price / EV as last fill",
                side = picked,
                kellyF = sized.kellyF
            )
        }
        return Decision(skip = false, side = picked, kellyF = sized.kellyF)
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
    ): PaperFill? {
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
            return null
        }
        val picked = decision.side ?: return null
        val depth = if (picked.side.equals("NO", true)) noDepth else yesDepth
        return paperBook.considerAutopilot(
            ticker = market.ticker,
            side = picked.side,
            ask = picked.ask,
            winProb = picked.winProb,
            depthContracts = depth,
            evPerContract = picked.evPerContract,
            enabled = true
        )
    }

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
