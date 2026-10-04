package com.dirk.kalshiodds.signal.flowfade

import android.content.Context
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger
import com.dirk.kalshiodds.signal.latefav.LateFavoriteRule
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import com.dirk.kalshiodds.signal.latefav.LateFavoriteSummary
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.serialization.json.Json

/**
 * "Fade the flow" from docs/flow-fade-2026-10-04.md. **PAPER ONLY.** Nothing
 * here places an order; the tracker logs what the rule would do.
 *
 * Signal: takers' buying over the last 30 s is at least 3:1 on one side.
 * On 2,088 settled KXBTC15M windows (2026-09-12 → 10-04) following that
 * flow lost 4.2¢ per contract (99% CI [−6.7, −1.7]). Two ways to take the
 * other side were tested:
 *
 * - **Buying at the ask** ([evaluate]) looked good on the 11 days it was
 *   found on (+2.9¢) and failed on the 12 earlier days (−2.9¢, 3 of 12
 *   days positive). All 23 days: −0.0¢, 95% CI [−2.2, +2.3]. Retired; kept
 *   only so the tests document it.
 * - **Resting a no-fee bid** on the side takers are not buying ([order] +
 *   [MakerPaperBook]) made +1.8¢ per fill with 2,000 contracts queued
 *   ahead and +1.0¢ with 5,000 (95% CIs include zero; the earlier 12 days
 *   alone are about flat). Without the signal the same resting order loses
 *   0.8–1.2¢. This is what the tracker now runs. Not proven.
 *
 * Resting rule, one fill per market:
 * - watched live market with [MIN_TTE_SECONDS] ≤ time left ≤ [MAX_TTE_SECONDS]
 * - taker contracts in the last [WINDOW_MS]: total ≥ [MIN_CONTRACTS] and
 *   |imbalance| ≥ [MIN_ABS_IMBALANCE], imbalance = (yes − no) / (yes + no)
 * - join the best bid on the side takers are **not** buying, only if
 *   [MIN_ASK] ≤ bid ≤ [MAX_ASK]; the size shown at that price when the
 *   order is posted is the queue ahead ([DEFAULT_QUEUE_AHEAD] if unknown)
 * - filled only after that queue has traded at the price, or when a print
 *   trades through it; cancelled after [CANCEL_MS]
 * - $5 at the bid, no fee; stress line = the same fill with a
 *   [MAKER_FEE_STRESS] maker fee
 */
object FlowFadeRule {

    const val WINDOW_MS = 30_000L
    const val MIN_CONTRACTS = 500.0
    const val MIN_ABS_IMBALANCE = 0.5
    const val MIN_ASK = 0.10
    const val MAX_ASK = 0.90
    const val MIN_TTE_SECONDS = 30L
    const val MAX_TTE_SECONDS = 840L
    const val WORSE_FILL_SLIPPAGE = 0.01

    /** A resting paper order is cancelled this long after it is posted. */
    const val CANCEL_MS = 30_000L

    /** Queue assumed ahead when the book shows no size (median seen at the best bid). */
    const val DEFAULT_QUEUE_AHEAD = 3_500.0

    /** Maker fee coefficient for the stress line (the series charges makers 0 today). */
    const val MAKER_FEE_STRESS = 0.0175

    const val STAKE_USD = 5.0

    private const val EPS = 1e-9

    data class Inputs(
        val ticker: String,
        val nowMs: Long,
        val tteSeconds: Long?,
        /** Taker contracts that bought YES / NO in the last [WINDOW_MS]. */
        val takerYes: Double,
        val takerNo: Double,
        val yesAsk: Double?,
        val noAsk: Double?
    )

    /** (yes − no) / (yes + no); null when nothing traded. */
    fun imbalance(takerYes: Double, takerNo: Double): Double? {
        if (!takerYes.isFinite() || !takerNo.isFinite() || takerYes < 0.0 || takerNo < 0.0) return null
        val total = takerYes + takerNo
        return if (total > 0.0) (takerYes - takerNo) / total else null
    }

    /**
     * The retired buy-at-the-ask version: the bet it would take now, or
     * null. The decision's `z` holds the flow imbalance. Not wired to the
     * tracker any more (it failed out of sample).
     */
    fun evaluate(inputs: Inputs, alreadyEntered: Boolean): LateFavoriteRule.Decision? {
        if (alreadyEntered) return null
        if (!CryptoMarkets.isLiveTicker(inputs.ticker)) return null
        val tte = inputs.tteSeconds ?: return null
        if (tte < MIN_TTE_SECONDS || tte > MAX_TTE_SECONDS) return null
        if (inputs.takerYes + inputs.takerNo + EPS < MIN_CONTRACTS) return null
        val imb = imbalance(inputs.takerYes, inputs.takerNo) ?: return null
        if (abs(imb) + EPS < MIN_ABS_IMBALANCE) return null
        // Takers are buying YES → fade with NO, and the other way round.
        val side = if (imb > 0.0) "NO" else "YES"
        val ask = KalshiPrice.usable(if (side == "YES") inputs.yesAsk else inputs.noAsk) ?: return null
        if (ask + EPS < MIN_ASK || ask - EPS > MAX_ASK) return null
        val sized = LateFavoriteRule.sizeAllIn(ask) ?: return null
        val worseAsk = ask + WORSE_FILL_SLIPPAGE
        return LateFavoriteRule.Decision(
            ticker = inputs.ticker,
            nowMs = inputs.nowMs,
            tteSeconds = tte,
            z = imb,
            side = side,
            ask = ask,
            sized = sized,
            worseAsk = worseAsk,
            worse = LateFavoriteRule.sizeAllIn(worseAsk)
        )
    }

    data class MakerInputs(
        val ticker: String,
        val nowMs: Long,
        val tteSeconds: Long?,
        val takerYes: Double,
        val takerNo: Double,
        val yesBid: Double?,
        val noBid: Double?,
        /** Contracts shown at the best YES / NO bid, when the book has them. */
        val yesBidQty: Double? = null,
        val noBidQty: Double? = null
    )

    /** A resting paper bid: buy [side] at [price] once [queueAhead] contracts have traded there. */
    data class Order(
        val ticker: String,
        val side: String,
        val price: Double,
        val queueAhead: Double,
        val postedAtMs: Long,
        val tteSeconds: Long,
        val imbalance: Double
    )

    /** The resting paper order to post now, or null. Same signal as [evaluate]. */
    fun order(inputs: MakerInputs, alreadyEntered: Boolean): Order? {
        if (alreadyEntered) return null
        if (!CryptoMarkets.isLiveTicker(inputs.ticker)) return null
        val tte = inputs.tteSeconds ?: return null
        if (tte < MIN_TTE_SECONDS || tte > MAX_TTE_SECONDS) return null
        if (inputs.takerYes + inputs.takerNo + EPS < MIN_CONTRACTS) return null
        val imb = imbalance(inputs.takerYes, inputs.takerNo) ?: return null
        if (abs(imb) + EPS < MIN_ABS_IMBALANCE) return null
        // Takers are buying YES → they sell NO into the NO bid: rest there.
        val side = if (imb > 0.0) "NO" else "YES"
        val bid = KalshiPrice.usable(if (side == "YES") inputs.yesBid else inputs.noBid) ?: return null
        if (bid + EPS < MIN_ASK || bid - EPS > MAX_ASK) return null
        if (sizeResting(bid) == null) return null
        val shown = (if (side == "YES") inputs.yesBidQty else inputs.noBidQty)
            ?.takeIf { it.isFinite() && it >= 0.0 }
        return Order(
            ticker = inputs.ticker,
            side = side,
            price = bid,
            queueAhead = shown ?: DEFAULT_QUEUE_AHEAD,
            postedAtMs = inputs.nowMs,
            tteSeconds = tte,
            imbalance = imb
        )
    }

    /**
     * [STAKE_USD] of contracts at a resting [price]. [makerFeeRate] 0 is
     * today's schedule; the stress line uses [MAKER_FEE_STRESS].
     */
    fun sizeResting(price: Double?, makerFeeRate: Double = 0.0): LateFavoriteRule.Sized? {
        val p = KalshiPrice.usable(price) ?: return null
        var c = kotlin.math.floor(STAKE_USD / p + EPS).toInt()
        fun fee(n: Int) = kotlin.math.ceil(makerFeeRate * n * p * (1.0 - p) * 100.0 - 1e-7) / 100.0
        while (c > 0 && c * p + fee(c) > STAKE_USD + EPS) c--
        if (c <= 0) return null
        return LateFavoriteRule.Sized(contracts = c, costUsd = c * p + fee(c), feeUsd = fee(c))
    }

    /** The ledger row for [order] once it fills at [filledAtMs]; null if it cannot be sized. */
    fun filled(order: Order, filledAtMs: Long): LateFavoriteRule.Decision? {
        val sized = sizeResting(order.price) ?: return null
        return LateFavoriteRule.Decision(
            ticker = order.ticker,
            nowMs = filledAtMs,
            tteSeconds = order.tteSeconds,
            z = order.imbalance,
            side = order.side,
            ask = order.price,
            sized = sized,
            worseAsk = order.price,
            worse = sizeResting(order.price, MAKER_FEE_STRESS)
        )
    }
}

/**
 * Resting paper orders, one per ticker. Conservative fills: an order fills
 * only when the contracts shown ahead of it have traded at its price after
 * it was posted, or when a print trades through its price. Cancels ahead of
 * it never move it up. Thread-safe; fed with every public trade.
 */
class MakerPaperBook(private val cancelMs: Long = FlowFadeRule.CANCEL_MS) {
    private class Slot(val order: FlowFadeRule.Order) {
        var tradedAtPrice = 0.0
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    fun pending(ticker: String): FlowFadeRule.Order? = slots[ticker]?.order

    /** Post [order] unless its ticker already has one resting. */
    fun post(order: FlowFadeRule.Order): Boolean = slots.putIfAbsent(order.ticker, Slot(order)) == null

    /** Drop the order on [ticker] if it is older than the cancel time. Returns it when dropped. */
    fun expire(ticker: String, nowMs: Long): FlowFadeRule.Order? {
        val slot = slots[ticker] ?: return null
        if (nowMs - slot.order.postedAtMs <= cancelMs) return null
        return if (slots.remove(ticker, slot)) slot.order else null
    }

    /**
     * A public trade on [ticker]: [takerSide] "yes" = the taker bought YES
     * at [yesPrice]. Returns the resting order when this print fills it.
     * A NO bid at q is hit by takers buying YES at 1 − q or higher; a YES
     * bid at b is hit by takers buying NO when YES trades at b or lower.
     */
    fun onTrade(ticker: String, takerSide: String?, yesPrice: Double?, count: Double?, nowMs: Long): FlowFadeRule.Order? {
        val slot = slots[ticker] ?: return null
        val order = slot.order
        if (nowMs - order.postedAtMs > cancelMs) {
            slots.remove(ticker, slot)
            return null
        }
        val c = count?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val p = yesPrice?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return null
        val takerYes = when (takerSide?.lowercase(Locale.US)) {
            "yes" -> true
            "no" -> false
            else -> return null
        }
        val restingNo = order.side.equals("NO", ignoreCase = true)
        if (takerYes != restingNo) return null   // this taker is not selling our side
        val level = if (restingNo) 1.0 - order.price else order.price
        val through = if (restingNo) p > level + TICK_EPS else p < level - TICK_EPS
        val atLevel = abs(p - level) <= TICK_EPS
        val fill = synchronized(slot) {
            if (atLevel) slot.tradedAtPrice += c
            through || (atLevel && slot.tradedAtPrice > order.queueAhead)
        }
        return if (fill && slots.remove(ticker, slot)) order else null
    }

    fun retain(tickers: Set<String>) {
        slots.keys.retainAll(tickers)
    }

    private companion object {
        const val TICK_EPS = 5e-4
    }
}

/**
 * Rolling per-ticker sums of taker contracts over [windowMs]. Fed with
 * every public trade on the WebSocket thread, read on the tick thread.
 * Bounded: old prints are dropped on every add / read and each ticker
 * keeps at most [maxPrints] prints.
 */
class FlowWindow(
    private val windowMs: Long = FlowFadeRule.WINDOW_MS,
    private val maxPrints: Int = 20_000
) {
    private class Print(val tsMs: Long, val yes: Boolean, val count: Double)

    private class Book {
        val prints = ArrayDeque<Print>()
        var yes = 0.0
        var no = 0.0
    }

    private val books = ConcurrentHashMap<String, Book>()

    /** [takerSide] is Kalshi's `taker_side`: "yes" = the taker bought YES. */
    fun onTrade(ticker: String, takerSide: String?, count: Double?, nowMs: Long) {
        val c = count?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val yes = when (takerSide?.lowercase(Locale.US)) {
            "yes" -> true
            "no" -> false
            else -> return
        }
        val book = books.getOrPut(ticker) { Book() }
        synchronized(book) {
            book.prints.addLast(Print(nowMs, yes, c))
            if (yes) book.yes += c else book.no += c
            trim(book, nowMs)
        }
    }

    /** Taker (yes, no) contracts for [ticker] in the last [windowMs]. */
    fun sums(ticker: String, nowMs: Long): Pair<Double, Double> {
        val book = books[ticker] ?: return 0.0 to 0.0
        synchronized(book) {
            trim(book, nowMs)
            return book.yes.coerceAtLeast(0.0) to book.no.coerceAtLeast(0.0)
        }
    }

    /** Forget tickers that are no longer watched. */
    fun retain(tickers: Set<String>) {
        books.keys.retainAll(tickers)
    }

    private fun trim(book: Book, nowMs: Long) {
        while (true) {
            val head = book.prints.peekFirst() ?: break
            if (nowMs - head.tsMs <= windowMs && book.prints.size <= maxPrints) break
            book.prints.pollFirst()
            if (head.yes) book.yes -= head.count else book.no -= head.count
        }
        if (book.prints.isEmpty()) {
            book.yes = 0.0
            book.no = 0.0
        }
    }
}

/**
 * On-device store for the flow-fade paper tracker. Reuses
 * [LateFavoriteLedger] (same entry / settle / totals logic) under its own
 * preferences file. Isolated from live Approve and Kalshi keys.
 */
class FlowFadeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val ledger: LateFavoriteLedger = LateFavoriteLedger(
        initial = load(),
        persist = { save(it) }
    )

    private fun load(): LateFavoriteState {
        val raw = prefs.getString(KEY, null) ?: return LateFavoriteState()
        return runCatching { json.decodeFromString(LateFavoriteState.serializer(), raw) }
            .getOrElse { LateFavoriteState() }
    }

    private fun save(state: LateFavoriteState) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(LateFavoriteState.serializer(), state)).apply()
        }
    }

    companion object {
        private const val PREFS = "bitcoin_claude_flow_fade"
        // New key: the buy-at-the-ask rows from the first 1.3 build are not mixed in.
        private const val KEY = "resting_state_json"
    }
}

/** Home-card copy for the flow-fade paper tracker. Pure, unit tested. */
data class FlowFadeSummary(
    val title: String,
    val recordLine: String,
    val pnlLine: String,
    val worseLine: String,
    val openLine: String,
    val note: String
) {
    companion object {
        const val TITLE = "Flow fade (resting bid) · PAPER"
        const val TARGET_SETTLED = 1000
        const val NOTE =
            "Paper only. After 30 s of 3:1 one-sided taker buying, rests a no-fee bid on the other side and " +
                "counts a fill only once the queue ahead has traded. History over 23 days: about +1 to +2¢ per fill, " +
                "not proven. Buying at the ask on this signal failed its test and was retired. " +
                "Needs ~1,000 live fills and Live signals on."

        fun of(state: LateFavoriteState): FlowFadeSummary {
            val t = state.totals
            val winPct = t.winRate?.let { String.format(Locale.US, " (%.1f%%)", it * 100.0) }.orEmpty()
            val avgAsk = t.avgAsk?.let { String.format(Locale.US, " at a %.0f¢ bid", it * 100.0) }.orEmpty()
            val voids = if (t.voids > 0) " · ${t.voids} void" else ""
            val record = "${t.wins}-${t.losses}$winPct$avgAsk · ${t.settledBets} / $TARGET_SETTLED settled$voids"
            val perBet = t.perBetUsd?.let { " · ${LateFavoriteSummary.money(it)}/bet" }.orEmpty()
            val pnl = "P&L ${LateFavoriteSummary.money(t.pnlUsd)}$perBet"
            val worsePerBet = t.perBetWorseUsd?.let { " · ${LateFavoriteSummary.money(it)}/bet" }.orEmpty()
            val worse = "With a maker fee ${LateFavoriteSummary.money(t.pnlWorseUsd)}$worsePerBet"
            val open = state.openEntries
            val openLine = if (open.isEmpty()) {
                "Open: none"
            } else {
                val shown = open.take(3).joinToString(" · ") {
                    String.format(
                        Locale.US,
                        "%s %s @ %.0f¢",
                        it.ticker.substringAfter('-', it.ticker),
                        if (it.side.equals("YES", ignoreCase = true)) "UP" else "DOWN",
                        it.ask * 100.0
                    )
                }
                "Open: $shown" + (if (open.size > 3) " · +${open.size - 3}" else "")
            }
            return FlowFadeSummary(TITLE, record, pnl, worse, openLine, NOTE)
        }
    }
}
