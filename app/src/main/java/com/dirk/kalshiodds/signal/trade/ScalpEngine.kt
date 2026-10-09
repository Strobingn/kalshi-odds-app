package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import kotlinx.serialization.Serializable

/** Executable-quote hypothesis, NOT a profitability claim or a BTC-last-trade fade.
 * No entry-minute filter, deadline, fixed holding period, or forced late-window sale.
 * Fair probabilities are the app's proxy models, not an authenticated BRTI valuation.
 */
class ScalpEngine(initial: List<Replay> = emptyList(), private val save: (List<Replay>) -> Unit = {}) {
    @Serializable
    data class Replay(val ticker: String, val side: String, val enteredMs: Long,
        val entry: Double, val count: Int, val entryDebit: Double,
        val closeMs: Long, val exit: Double? = null, val netUsd: Double? = null,
        val reason: String? = null, val endedMs: Long? = null, val source: String = "live")
    private data class Quote(val at: Long, val bid: Double, val ask: Double, val fair: Double)
    private val history = mutableMapOf<String, ArrayDeque<Quote>>()
    private val records = initial.toMutableList()
    private val signals = mutableMapOf<String, TradeTicket>()
    private var activeSource = "live"

    @Synchronized
    fun observe(markets: List<MarketUiModel>, ctx: TicketBuilder.Context, source: String = "live",
                freshBook: (String) -> Boolean): List<TradeTicket> {
        if (source != activeSource) { history.clear(); signals.clear(); activeSource = source }
        val active = markets.filter { it.ticker.startsWith("KXBTC15M-") &&
            MarketLifecycle.isCurrentWindow(it, ctx.nowMs) }
        val activeIds = active.map { it.ticker }.toSet()
        history.keys.removeAll { it.substringBefore('|') !in activeIds }
        signals.keys.removeAll { it.substringBefore('|') !in activeIds }
        val exits = mutableListOf<TradeTicket>()
        for (m in active) for (side in listOf("YES", "NO")) {
            val key = "${m.ticker}|$side"
            // Rebuild entries from this observation, never retain an old opportunity.
            signals.remove(key)
            val bid = TicketBuilder.freshBestBid(m, side, ctx)
            val ask = TicketBuilder.bestAsk(m, side, ctx)
            val fair = TicketBuilder.modelProb(m, side)
            if (!freshBook(m.ticker)) {
                history.remove(key)
                // Missing quotes leave the position unresolved, never a fictional fill.
                continue
            }
            if (bid == null) continue
            val replayIndex = records.indexOfFirst { it.source == source && it.ticker == m.ticker && it.side == side && it.endedMs == null }
            if (replayIndex >= 0) {
                val rec = records[replayIndex]
                val reason = exitReason(rec.entry, bid, fair)
                if (reason != null && bidDepth(ctx, m.ticker, side, bid) >= rec.count) {
                    finish(replayIndex, bid, ctx.nowMs, reason)
                }
            }
            // Advisory exits are always reduce-only IOC and require their own Approve.
            ctx.positions.firstOrNull { it.ticker == m.ticker && it.side == side }?.let { pos ->
                pos.avgCost?.let { cost ->
                    exitReason(cost, bid, fair)?.let { reason ->
                        TicketBuilder.proposeSell(m, side, PositionParser.heldContracts(pos), ctx)?.let {
                            exits += it.copy(id = "scalp-exit-$key", gateNote = "Experimental scalp exit: $reason. No holding-time cutoff.")
                        }
                    }
                }
            }
            // A wide or one-sided book blocks entries, but must not suppress
            // price-based loss/target exits when a fresh sellable bid exists.
            if (ask == null || fair == null || ask < bid || ask - bid > .02 + 1e-9) continue
            val quotes = history.getOrPut(key) { ArrayDeque() }
            while (quotes.firstOrNull()?.at?.let { ctx.nowMs - it > 60_000 } == true) quotes.removeFirst()
            if (quotes.lastOrNull()?.at?.let { ctx.nowMs - it >= 1000 } != false) {
                quotes.addLast(Quote(ctx.nowMs, bid, ask, fair))
            }
            while (quotes.size > 60) quotes.removeFirst()
            val window = quotes.toList()
            if (window.size < 5) continue
            val lowIndex = window.indices.minByOrNull { window[it].ask } ?: continue
            val low = window[lowIndex]
            val peak = window.take(lowIndex).maxOfOrNull { it.ask } ?: continue
            val bounce = peak - low.ask >= .05 && ask - low.ask >= .01 &&
                fair - ask >= .06 && kotlin.math.abs(fair - low.fair) <= .03
            if (!bounce || ctx.alertsPaused || ctx.settings.isSittingOut() || !ctx.settings.ticketsEnabled) {
                signals.remove(key); continue
            }
            if (ctx.positions.any { it.ticker == m.ticker }) { signals.remove(key); continue }
            val cap = minOf(5.0, (ctx.bankrollUsd ?: 100.0) * .005)
            val count = minOf(LiveOrderSizer.maxCount(ask, cap), askDepth(ctx, m.ticker, side, ask))
            if (count < 1) continue
            val total = LiveOrderSizer.allInUsd(count, ask)
            val base = TicketBuilder.proposeManual(m, side, ctx) ?: continue
            signals[key] = base.copy(id = "scalp-$key", kind = TicketKind.SCALP,
                paperOnly = false, contracts = count, limitPrice = ask,
                yesLimitPrice = if (side == "YES") ask else 1 - ask,
                stakeUsd = total, allInUsd = total, feeUsd = LiveOrderSizer.feeUsd(count, ask),
                maxPayoutUsd = count.toDouble(), estimatedFillUsd = total,
                profitIfWinUsd = count - total, blockedReason = null,
                netEvUsd = null, netEvPerContract = null, netEdgePp = null,
                gateNote = "Experimental bounce after a ≥5¢ drop and ≥1¢ recovery; proxy fair value ≥6¢ over ask. No verified Kalshi profit model.",
                sizingNote = "$count contracts · ≤0.5% bankroll / $5 · both-leg fees included in replay", winTargetUsd = null)
            if (records.none { it.source == source && it.ticker == m.ticker && it.side == side }) {
                records += Replay(m.ticker, side, ctx.nowMs, ask, count, total, m.closeTimeEpochMs ?: continue, source = source)
                persist()
            }
        }
        return signals.values.toList() + exits
    }

    fun exitReason(entry: Double, bid: Double, fair: Double?): String? = when {
        bid >= entry + .08 -> "8¢ price bounce"
        bid <= entry - .06 -> "6¢ price loss"
        fair != null && fair <= bid -> "proxy fair-value advantage gone"
        else -> null
    }
    private fun askDepth(ctx: TicketBuilder.Context, ticker: String, side: String, ask: Double): Int {
        val b = ctx.books[ticker] ?: return 0
        val opposite = if (side == "YES") b.no else b.yes
        return kotlin.math.floor(opposite.filter { 1 - it.first <= ask + 1e-9 }.sumOf { it.second }).toInt()
    }
    private fun bidDepth(ctx: TicketBuilder.Context, ticker: String, side: String, bid: Double): Int {
        val b = ctx.books[ticker] ?: return 0
        val levels = if (side == "YES") b.yes else b.no
        return kotlin.math.floor(levels.filter { it.first >= bid - 1e-9 }.sumOf { it.second }).toInt()
    }
    private fun finish(index: Int, price: Double, now: Long, reason: String) {
        val rec = records[index]
        val proceeds = rec.count * price - LiveOrderSizer.feeUsd(rec.count, price)
        records[index] = rec.copy(exit = price, netUsd = proceeds - rec.entryDebit, reason = reason, endedMs = now)
        persist()
    }
    @Synchronized
    fun settle(ticker: String, yesWon: Boolean, atMs: Long = System.currentTimeMillis(), source: String = activeSource) {
        records.indices.filter { records[it].source == source && records[it].ticker == ticker && records[it].endedMs == null }.forEach { i ->
            val rec = records[i]
            val payout = if ((rec.side == "YES") == yesWon) rec.count.toDouble() else 0.0
            records[i] = rec.copy(exit = payout / rec.count, netUsd = payout - rec.entryDebit,
                reason = "official settlement", endedMs = atMs)
        }
        persist()
    }
    @Synchronized
    fun summary(): String {
        val finished = records.filter { it.source == activeSource && it.endedMs != null }
        val unresolved = records.count { it.source == activeSource && it.endedMs == null }
        return "${activeSource.uppercase()} quote replay: ${finished.size} completed · $unresolved unresolved · net $${"%.2f".format(java.util.Locale.US, finished.sumOf { it.netUsd ?: 0.0 })}. Hypothetical taker fills; queue, latency and slippage untested."
    }
    @Synchronized fun snapshot(): List<Replay> = records.toList()
    private fun persist() {
        val terminal = records.filter { it.endedMs != null }.takeLast(2000)
        val open = records.filter { it.endedMs == null }
        records.clear(); records.addAll(terminal + open); save(records.toList())
    }
}
