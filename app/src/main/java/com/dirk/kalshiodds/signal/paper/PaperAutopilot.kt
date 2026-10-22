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
 * spot-vs-strike. UP and DOWN may both be open in the same window; each
 * side still has to clear its own edge, fee, NO BET, and flip-chance gates.
 * There is no per-window count cap. The same ask on the same book snapshot
 * does not print another fill, and each new fill has to re-qualify for
 * [EDGE_HOLDS] consecutive evaluations.
 */
object PaperAutopilot {
    /** 0.3.40: paper Autopilot always sizes half-Kelly on the paper bankroll. */
    const val PAPER_AI_KELLY_FRACTION = 0.5


    const val SOURCE = "AI paper autopilot"
    const val PRICE_DELTA = 0.02
    const val PROB_DELTA = 0.03
    const val KELLY_DELTA = 0.05

    /** Minimum post-fee edge, in probability points (4¢ per $1 contract). */
    const val MIN_EDGE = 0.04
    /** Gated path: minimum expected net per contract after fill odds, fees, and adverse selection. */
    const val MIN_NET = 0.02
    const val EDGE_HOLDS = 2
    const val MIN_ASK = 0.10
    const val MAX_ASK = 0.90
    const val ANCHOR = 0.40
    const val WINDOW_BANKROLL_FRACTION = 0.05
    const val HALF_KELLY = 0.5
    const val LAST_WINDOW_MS = 60_000L

    private data class Hold(val side: String, val count: Int)
    private data class Snap(val ask: Double, val book: String)

    private val edgeHolds = ConcurrentHashMap<String, Hold>()
    private val lastSnap = ConcurrentHashMap<String, Snap>()
    private val clampWindows = ConcurrentHashMap.newKeySet<String>()

    /** Test hook. Production ticks share one process-lifetime streak. */
    fun resetSession() {
        edgeHolds.clear()
        lastSnap.clear()
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

    /**
     * Cash still free to put on a new clip. Open paper stake and any extra open
     * exposure (shadow clips on the same bankroll) come off first, and the result
     * cannot exceed the cash that is actually sitting in the book.
     */
    fun freeBankroll(paper: PaperBookState, extraOpenUsd: Double = 0.0): Double {
        val room = paper.paperBankrollUsd - paper.openStakeUsd - extraOpenUsd.coerceAtLeast(0.0)
        return min(room, paper.cashUsd).coerceAtLeast(0.0)
    }

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

    /**
     * Displayed ask depth minus contracts our own open fills on this side
     * already took. Paper does not remove those contracts from the live book,
     * so the next clip must not size as if the size were still all available.
     */
    fun depthRemaining(fills: List<PaperFill>, ticker: String, side: String, displayed: Int?): Int? {
        if (displayed == null) return null
        val used = fills.filter {
            !it.settled &&
                it.ticker.equals(ticker, ignoreCase = true) &&
                it.side.equals(side, ignoreCase = true)
        }.sumOf { it.contracts }
        return (displayed - used).coerceAtLeast(0)
    }

    /** Stable fingerprint of a book snapshot. Null and empty are the same quote. */
    fun bookKey(book: BookLevelSnapshot?): String {
        if (book == null || book.isEmpty()) return ""
        fun fmt(levels: List<Pair<Double, Double>>) =
            levels.joinToString(";") { (p, s) -> String.format(java.util.Locale.US, "%.6f@%.4f", p, s) }
        return fmt(book.yes) + "|" + fmt(book.no)
    }

    /**
     * First paper fill in the window. Shadow mode still refuses the other side.
     * Paper mode does not: UP and DOWN may both be open.
     */
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
    fun qualifyingSides(
        pYes: Double,
        yesAsk: Double?,
        noAsk: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        margin: Double = 0.0
    ): List<SideEv> {
        if (!pYes.isFinite()) return emptyList()
        return listOfNotNull(
            sideEv("YES", "UP", pYes.coerceIn(0.0, 1.0), yesAsk, feeRate),
            sideEv("NO", "DOWN", (1.0 - pYes).coerceIn(0.0, 1.0), noAsk, feeRate)
        ).filter { it.evPerContract > margin + 1e-12 }
    }

    fun pickSide(
        pYes: Double,
        yesAsk: Double?,
        noAsk: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        margin: Double = 0.0
    ): SideEv? = qualifyingSides(pYes, yesAsk, noAsk, feeRate, margin).maxByOrNull { it.evPerContract }

    fun modelYes(market: MarketUiModel): Double? {
        val lm = market.lastMinute?.pUp?.takeIf { it.isFinite() && !com.dirk.kalshiodds.signal.lastminute.LastMinuteRetired.retired }
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
        noDepth: Int? = null,
        assessment: com.dirk.kalshiodds.decision.DecisionPipeline.Assessment? = null,
        book: BookLevelSnapshot? = null,
        /** Shadow still refuses the other side. Paper does not. */
        lockOppositeSide: Boolean = false
    ): Decision {
        if (!settings.paperTradingEnabled) {
            return Decision(skip = true, reason = "Paper trading off")
        }
        if (!settings.aiPaperAutopilotEnabled) {
            return Decision(skip = true, reason = "AI paper autopilot off")
        }
        if (!CryptoMarkets.isAutopilotTicker(market.ticker)) {
            return Decision(skip = true, reason = "Paper skip — not a BTC, ETH, or SOL 15m or daily market")
        }
        if (!MarketLifecycle.isTradable(market, nowMs)) {
            return Decision(skip = true, reason = "Paper skip — window closed")
        }
        if (assessment != null) {
            return evaluateGated(market, settings, paper, nowMs, yesDepth, noDepth, assessment, book, lockOppositeSide)
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
        val edged = qualifyingSides(pYes, yesAsk, noAsk, settings.feeRate, MIN_EDGE)
        if (edged.isEmpty()) {
            clearHolds(market.ticker)
            return Decision(skip = true, reason = "Paper skip — edge under 4pp after fees")
        }
        val inBand = edged.filter { it.ask + 1e-12 >= MIN_ASK && it.ask - 1e-12 <= MAX_ASK }
        if (inBand.isEmpty()) {
            return Decision(
                skip = true,
                reason = "Paper skip — ask outside 10¢–90¢",
                side = edged.maxByOrNull { it.evPerContract }
            )
        }
        return chooseEntry(market, settings, paper, nowMs, inBand, yesDepth, noDepth, MIN_EDGE, book, lockOppositeSide)
    }

    /**
     * 0.3.37 decision path: the side, win probability, and expected net come
     * from [com.dirk.kalshiodds.decision.DecisionPipeline] (calibrated,
     * market-as-prior, fill-aware). A NO BET verdict is final; its reason is
     * shown verbatim. Final 60 s are allowed only when that regime is calibrated.
     */
    private fun evaluateGated(
        market: MarketUiModel,
        settings: SignalSettings,
        paper: PaperBookState,
        nowMs: Long,
        yesDepth: Int?,
        noDepth: Int?,
        a: com.dirk.kalshiodds.decision.DecisionPipeline.Assessment,
        book: BookLevelSnapshot?,
        lockOppositeSide: Boolean
    ): Decision {
        val c = a.chosen
        val picked = c?.let {
            SideEv(
                side = it.side,
                displaySide = if (it.side == "NO") "DOWN" else "UP",
                winProb = it.pWin,
                ask = it.ask,
                evPerContract = it.expectedNetPerContract,
                feePerContract = it.feePerContract
            )
        }
        if (!a.allow || picked == null) {
            clearHolds(market.ticker)
            return Decision(skip = true, reason = a.reason ?: "NO BET", side = picked)
        }
        return chooseEntry(
            market, settings, paper, nowMs, listOf(picked), yesDepth, noDepth, MIN_NET, book, lockOppositeSide
        )
    }

    /**
     * Every side that clears its own gates can be the fill this tick.
     * At most one fill is returned; the other side, if it is also ready,
     * waits until the next evaluation.
     */
    private fun chooseEntry(
        market: MarketUiModel,
        settings: SignalSettings,
        paper: PaperBookState,
        nowMs: Long,
        sides: List<SideEv>,
        yesDepth: Int?,
        noDepth: Int?,
        minEdge: Double,
        book: BookLevelSnapshot?,
        lockOppositeSide: Boolean
    ): Decision {
        var pool = sides
        if (lockOppositeSide) {
            val locked = committedSide(paper.fills, market.ticker)
            if (locked != null) {
                val kept = pool.filter { it.side.equals(locked, ignoreCase = true) }
                if (kept.isEmpty()) {
                    return Decision(
                        skip = true,
                        reason = "Paper skip — opposite side already filled",
                        side = pool.maxByOrNull { it.evPerContract }
                    )
                }
                pool = kept
            }
        }
        val ready = ArrayList<Decision>()
        var fallback: Decision? = null
        for (picked in pool.sortedByDescending { it.evPerContract }) {
            if (picked.evPerContract + 1e-12 < minEdge) {
                edgeHolds.remove(holdKey(market.ticker, picked.side))
                fallback = fallback ?: Decision(
                    skip = true,
                    reason = if (minEdge == MIN_EDGE) "Paper skip — edge under 4pp after fees" else "NO BET — expected net under 2¢ per contract",
                    side = picked
                )
                continue
            }
            val held = noteEdge(market.ticker, picked.side)
            if (held < EDGE_HOLDS) {
                fallback = fallback ?: Decision(
                    skip = true,
                    reason = "Paper skip — edge needs 2 consecutive evaluations",
                    side = picked
                )
                continue
            }
            if (!FlipCheck.allowsRealisticMarketSide(
                    market = market,
                    side = picked.side,
                    ask = picked.ask,
                    nowMs = nowMs
                )
            ) {
                fallback = fallback ?: Decision(
                    skip = true,
                    reason = "Paper skip — flip-chance / lottery block",
                    side = picked
                )
                continue
            }
            val free = freeBankroll(paper)
            val shown = if (picked.side.equals("NO", true)) noDepth else yesDepth
            val depth = depthRemaining(paper.fills, market.ticker, picked.side, shown)
            val sized = PaperKellySizer.size(
                winProb = picked.winProb,
                ask = picked.ask,
                bankrollUsd = free,
                kellyFraction = PAPER_AI_KELLY_FRACTION,
                feeRate = settings.feeRate,
                depthContracts = depth,
                maxStakeUsd = null,
                capFullKelly = false
            )
            if (!sized.ok) {
                fallback = fallback ?: Decision(
                    skip = true,
                    reason = sized.reason ?: "Paper skip — Kelly ≤ 0 after fees",
                    side = picked,
                    kellyF = sized.kellyF,
                    freeBankrollUsd = free
                )
                continue
            }
            if (com.dirk.kalshiodds.decision.AutopilotMinStake.below(sized.allInUsd)) {
                fallback = fallback ?: Decision(
                    skip = true,
                    reason = com.dirk.kalshiodds.decision.AutopilotMinStake.REASON,
                    side = picked,
                    kellyF = sized.kellyF,
                    freeBankrollUsd = free
                )
                continue
            }
            if (sameQuote(market.ticker, picked.side, picked.ask, book)) {
                fallback = fallback ?: Decision(
                    skip = true,
                    reason = "Paper skip — same quote snapshot",
                    side = picked,
                    kellyF = sized.kellyF,
                    freeBankrollUsd = free
                )
                continue
            }
            ready += Decision(
                skip = false,
                side = picked,
                kellyF = sized.kellyF,
                freeBankrollUsd = free
            )
        }
        return ready.maxByOrNull { it.side?.evPerContract ?: Double.NEGATIVE_INFINITY }
            ?: fallback
            ?: Decision(skip = true, reason = "Paper skip — edge under 4pp after fees")
    }

    private fun holdKey(ticker: String, side: String): String =
        ticker.uppercase() + "|" + side.uppercase()

    private fun clearHolds(ticker: String) {
        val prefix = ticker.uppercase() + "|"
        edgeHolds.keys.filter { it.startsWith(prefix) || it.equals(ticker, ignoreCase = true) }
            .forEach { edgeHolds.remove(it) }
    }

    private fun noteEdge(ticker: String, side: String): Int {
        val key = holdKey(ticker, side)
        val count = (edgeHolds[key]?.count ?: 0) + 1
        edgeHolds[key] = Hold(side, count)
        return count
    }

    private fun forgetHold(ticker: String, side: String) {
        edgeHolds.remove(holdKey(ticker, side))
    }

    private fun sameQuote(ticker: String, side: String, ask: Double, book: BookLevelSnapshot?): Boolean {
        val prev = lastSnap[holdKey(ticker, side)] ?: return false
        return abs(prev.ask - ask) <= 1e-9 && prev.book == bookKey(book)
    }

    private fun rememberSnap(ticker: String, side: String, ask: Double, book: BookLevelSnapshot?) {
        lastSnap[holdKey(ticker, side)] = Snap(ask, bookKey(book))
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
        bookPaper: Boolean = true,
        assessment: com.dirk.kalshiodds.decision.DecisionPipeline.Assessment? = null
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
        val snap = paperBook.snapshot()
        val decision = evaluate(
            market = market,
            settings = settings,
            paper = snap,
            nowMs = nowMs,
            yesAsk = liveYes,
            noAsk = liveNo,
            yesDepth = yesDepth,
            noDepth = noDepth,
            assessment = assessment,
            book = book,
            lockOppositeSide = !bookPaper
        )
        if (!decision.ok) {
            decision.reason?.let { paperBook.rememberMessage(it) }
            return Tick(decision, null)
        }
        val picked = decision.side ?: return Tick(decision, null)
        if (!bookPaper) {
            rememberSnap(market.ticker, picked.side, picked.ask, book)
            return Tick(decision, null)
        }
        val rawDepth = if (picked.side.equals("NO", true)) noDepth else yesDepth
        val depth = depthRemaining(snap.fills, market.ticker, picked.side, rawDepth)
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
        if (fill != null) {
            forgetHold(market.ticker, picked.side)
            rememberSnap(market.ticker, picked.side, picked.ask, book)
        }
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
