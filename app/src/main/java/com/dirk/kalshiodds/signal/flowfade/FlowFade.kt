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
 * "Fade the flow" rule from docs/flow-fade-2026-10-04.md. **PAPER ONLY.**
 * Nothing here places an order; the tracker logs what the rule would do.
 *
 * On the public trade tape (952 settled KXBTC15M windows, 2026-09-24 →
 * 10-04), when takers' buying over the last 30 s was at least 3:1 on one
 * side, the *other* side won more often than its price: 700 first-trigger
 * bets, 69.0% wins at a 63.4¢ ask, +4.2¢ per contract after the taker fee
 * (95% CI [+0.9, +7.4], 99% CI [−0.2, +8.4]); +3.2¢ with a 1¢ worse fill,
 * +3.9¢ entering 30 s late, positive on 8 of 11 days. It was the best of
 * 18 rules tried on the same 11 days, so it is a lead, not a proven edge.
 * This tracker collects live windows before anyone trusts it.
 *
 * Rule, once per market (first qualifying moment), fixed on purpose:
 * - watched live market with [MIN_TTE_SECONDS] ≤ time left ≤ [MAX_TTE_SECONDS]
 *   (60 s to 870 s into the window)
 * - taker contracts in the last [WINDOW_MS]: total ≥ [MIN_CONTRACTS] and
 *   |imbalance| ≥ [MIN_ABS_IMBALANCE], imbalance = (yes − no) / (yes + no)
 * - buy the side takers are **not** buying, at its best ask, only if
 *   [MIN_ASK] ≤ ask ≤ [MAX_ASK]
 * - $5 all-in with the exact taker fee ([LateFavoriteRule.sizeAllIn]);
 *   stress case = the same bet at ask + 1¢
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
     * The paper bet the rule takes now, or null. The returned decision's
     * `z` holds the flow imbalance that triggered it (the ledger's column).
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
        private const val KEY = "state_json"
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
        const val TITLE = "Flow fade · PAPER"
        const val TARGET_SETTLED = 1000
        const val NOTE =
            "Paper only. Buys the side takers are not buying after 30 s of 3:1 one-sided flow. " +
                "History: +4.2¢ per contract over 700 bets, not proven. Needs ~1,000 live windows and Live signals on."

        fun of(state: LateFavoriteState): FlowFadeSummary {
            val t = state.totals
            val winPct = t.winRate?.let { String.format(Locale.US, " (%.1f%%)", it * 100.0) }.orEmpty()
            val avgAsk = t.avgAsk?.let { String.format(Locale.US, " at %.0f¢", it * 100.0) }.orEmpty()
            val voids = if (t.voids > 0) " · ${t.voids} void" else ""
            val record = "${t.wins}-${t.losses}$winPct$avgAsk · ${t.settledBets} / $TARGET_SETTLED settled$voids"
            val perBet = t.perBetUsd?.let { " · ${LateFavoriteSummary.money(it)}/bet" }.orEmpty()
            val pnl = "P&L ${LateFavoriteSummary.money(t.pnlUsd)}$perBet"
            val worsePerBet = t.perBetWorseUsd?.let { " · ${LateFavoriteSummary.money(it)}/bet" }.orEmpty()
            val worse = "Worse fill (+1¢) ${LateFavoriteSummary.money(t.pnlWorseUsd)}$worsePerBet"
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
