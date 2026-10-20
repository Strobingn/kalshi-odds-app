package com.dirk.kalshiodds.signal.scalper

import android.content.Context
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.engine.TopOfBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs

/** Running totals for one group of paper scalps. */
@Serializable
data class ScalpStats(
    val posted: Int = 0,
    val filled: Int = 0,
    val closed: Int = 0,
    val wins: Int = 0,
    val pnlUsd: Double = 0.0,
    val targets: Int = 0,
    val stops: Int = 0,
    val timeouts: Int = 0,
    val settled: Int = 0,
    /** Closed scalps that lost money after fees (a scalp that nets exactly $0 is neither a win nor a loss). */
    val losses: Int = 0,
    /** Dollars made by the winners / given back by the losers, both after fees, both positive numbers. */
    val wonUsd: Double = 0.0,
    val lostUsd: Double = 0.0,
    /** Taker fees charged on entries and exits. Already inside [pnlUsd]. */
    val feesUsd: Double = 0.0,
    val biggestWinUsd: Double = 0.0,
    val biggestLossUsd: Double = 0.0,
    /**
     * Per-window sums for the luck test, which clusters by market window: scalps in one window share
     * its outcome, so they are not independent. [windows] counts windows with a close; the
     * `win*` sums cover finished windows; `cur*`, `prev*` and the `old*` lists the windows still
     * collecting closes (a held position settles after the next window has started, sometimes later),
     * up to [OPEN_WINDOWS] of them, so a late settlement still joins its own window.
     */
    val windows: Int = 0,
    val winSqUsd: Double = 0.0,
    val winCrossUsd: Double = 0.0,
    val winN2: Double = 0.0,
    val curTicker: String = "",
    val curUsd: Double = 0.0,
    val curN: Int = 0,
    val prevTicker: String = "",
    val prevUsd: Double = 0.0,
    val prevN: Int = 0,
    val oldTickers: List<String> = emptyList(),
    val oldUsd: List<Double> = emptyList(),
    val oldN: List<Int> = emptyList(),
    /** Closes counted in the window sums. Below [closed] for a record that predates them (1.9.0–1.9.1). */
    val windowCloses: Int = 0
) {
    /** These totals with one more close on [ticker] folded into the per-window sums. */
    fun withWindowClose(ticker: String, pnlUsd: Double): ScalpStats {
        val old = oldTickers.indexOf(ticker)
        return when {
            curN > 0 && curTicker == ticker -> copy(curUsd = curUsd + pnlUsd, curN = curN + 1, windowCloses = windowCloses + 1)
            prevN > 0 && prevTicker == ticker -> copy(prevUsd = prevUsd + pnlUsd, prevN = prevN + 1, windowCloses = windowCloses + 1)
            old >= 0 -> copy(
                oldUsd = oldUsd.toMutableList().also { it[old] += pnlUsd },
                oldN = oldN.toMutableList().also { it[old] += 1 },
                windowCloses = windowCloses + 1
            )
            else -> {
                // A new window: the current one becomes the previous, the previous joins the older open
                // ones, and only the oldest beyond OPEN_WINDOWS is finished.
                val tk = (if (prevN > 0) listOf(prevTicker) else emptyList()) + oldTickers
                val usd = (if (prevN > 0) listOf(prevUsd) else emptyList()) + oldUsd
                val n = (if (prevN > 0) listOf(prevN) else emptyList()) + oldN
                val keep = OPEN_WINDOWS - 2
                val doneUsd = usd.drop(keep)
                val doneN = n.drop(keep)
                copy(
                    windows = windows + 1,
                    winSqUsd = winSqUsd + doneUsd.sumOf { it * it },
                    winCrossUsd = winCrossUsd + doneUsd.indices.sumOf { doneUsd[it] * doneN[it] },
                    winN2 = winN2 + doneN.sumOf { it.toDouble() * it },
                    oldTickers = tk.take(keep), oldUsd = usd.take(keep), oldN = n.take(keep),
                    prevTicker = curTicker, prevUsd = curUsd, prevN = curN,
                    curTicker = ticker, curUsd = pnlUsd, curN = 1,
                    windowCloses = windowCloses + 1
                )
            }
        }
    }

    val winRate: Double? get() = if (closed > 0) wins.toDouble() / closed else null
    val perScalpUsd: Double? get() = if (closed > 0) pnlUsd / closed else null
    val avgWinUsd: Double? get() = if (wins > 0) wonUsd / wins else null
    val avgLossUsd: Double? get() = if (losses > 0) lostUsd / losses else null
    val flat: Int get() = closed - wins - losses

    companion object {
        /** Windows kept open for late closes: the current one and the three before it (an hour). */
        const val OPEN_WINDOWS = 4
    }
}

/** Running paper P&L after the last scalp of a time bucket closed. */
@Serializable
data class CurvePoint(val tMs: Long, val pnlUsd: Double)

@Serializable
data class ScalpRow(
    val strategy: String,
    val ticker: String,
    val side: String,
    val entry: Double,
    val exit: Double,
    val kind: String,
    val pnlUsd: Double,
    val closedAtMs: Long
)

/**
 * The paper scalper's whole record. [groups] is keyed by [ALL], by
 * [ScalpStrategy.name], and for the ML scalper also by [QUEUE_AWARE] and
 * `Q_` + [QueueBucket.name].
 */
@Serializable
data class ScalperState(
    val groups: Map<String, ScalpStats> = emptyMap(),
    /** Newest first, capped at [ScalperLedger.MAX_RECENT]. */
    val recent: List<ScalpRow> = emptyList(),
    val startedAtMs: Long? = null,
    /**
     * 1 = a record written by 1.8.4, which kept no won / lost / fee totals;
     * [ScalperStore] starts a new record instead of mixing the two.
     */
    val version: Int = 1,
    /** Running P&L over time, at most [ScalperLedger.MAX_CURVE] points (older points are thinned). */
    val curve: List<CurvePoint> = emptyList(),
    val curveBucketMs: Long = ScalperLedger.CURVE_BUCKET_MS
) {
    fun stats(key: String): ScalpStats = groups[key] ?: ScalpStats()

    /** The record without the retired strategies' own rows ([ScalpStrategy.RETIRED]); totals stay the real history. */
    fun withoutRetired(): ScalperState = copy(
        groups = groups.filterKeys { !ScalpStrategy.isRetired(it) },
        recent = recent.filterNot { ScalpStrategy.isRetired(it.strategy) }
    )

    companion object {
        /**
         * 3 = the 15-strategy arena (1.9.0). A 1.8.x record holds history for the original seven
         * strategies only; carried over, those seven would race the eight new ones from a head start.
         */
        const val VERSION = 3
        const val ALL = "ALL"
        const val QUEUE_AWARE = "QUEUE_AWARE"
        const val COIN_PREFIX = "COIN_"
        const val HOUR_PREFIX = "HOUR_"
        fun bucketKey(b: QueueBucket): String = "Q_" + b.name
        fun coinKey(coin: String): String = COIN_PREFIX + coin
        fun hourKey(hour: Int): String = HOUR_PREFIX + hour

        /** An empty record in the current format. */
        fun fresh(): ScalperState = ScalperState(version = VERSION)

        /**
         * The record to start from: [saved] when it is in the current format; otherwise a fresh one,
         * once [archive] has moved the old trade files aside (kept, never deleted), so the leaderboard,
         * the charts and the trade list all begin together for every strategy. When the archive does
         * not complete, [saved] is kept as it is (old version and all) and the next start tries again.
         */
        fun upgraded(saved: ScalperState?, archive: () -> Boolean): ScalperState = when {
            saved == null -> fresh()
            saved.version >= VERSION -> saved
            runCatching { archive() }.getOrDefault(false) -> fresh()
            else -> saved
        }

        /** `KXBTC15M-26OCT091315-15` → `BTC`. */
        fun coinOf(ticker: String): String {
            val series = ticker.trim().uppercase(Locale.US).substringBefore('-')
            return series.removePrefix("KX").removeSuffix("15M").ifBlank { series }
        }
    }
}

/**
 * Persisted totals for the paper scalper. Pure logic; storage is the injected
 * [persist] callback, called at most every [persistEveryMs] because scalps
 * close many times a minute. No Kalshi keys, no trade client, no order path.
 */
class ScalperLedger(
    initial: ScalperState = ScalperState.fresh(),
    private val persist: (ScalperState) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val persistEveryMs: Long = 15_000L,
    /** Every resolved order, for the on-disk log: closed scalps, and one order-file row per order. No I/O here. */
    private val record: (trades: List<ScalpTrade>, orders: List<Pair<Long, String>>) -> Unit = { _, _ -> },
    /** Stamp of this app start, written on every logged row. */
    private val run: Long = 0L,
    /** Hour of the day (0–23) a scalp closed in, on the phone's clock. */
    private val hourOf: (Long) -> Int = { ms ->
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).hour
    }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ScalperState> = _state.asStateFlow()
    private var lastPersistMs = 0L

    fun snapshot(): ScalperState = _state.value

    fun apply(events: List<ScalpEvent>, forcePersist: Boolean = false) {
        if (events.isEmpty() && !forcePersist) return
        synchronized(lock) {
            val cur = _state.value
            val groups = HashMap(cur.groups)
            var recent = cur.recent
            var started = cur.startedAtMs
            var curve = cur.curve
            var bucketMs = cur.curveBucketMs.coerceAtLeast(1L)
            val trades = ArrayList<ScalpTrade>()
            val orders = ArrayList<Pair<Long, String>>()
            for (e in events) {
                when (e) {
                    is ScalpEvent.Posted -> {
                        if (started == null) started = e.order.postedAtMs
                        bump(groups, e.order) { it.copy(posted = it.posted + 1) }
                    }
                    is ScalpEvent.Filled -> bump(groups, e.order) { it.copy(filled = it.filled + 1) }
                    is ScalpEvent.Unfilled -> {
                        val at = nowMs()
                        orders += at to ScalpTradeLog.orderRow(e.order, null, at, run)
                    }
                    is ScalpEvent.Closed -> {
                        val c = e.scalp
                        val fees = c.order.entryFeeUsd + c.feeUsd
                        val extra = listOf(
                            ScalperState.coinKey(ScalperState.coinOf(c.order.ticker)),
                            ScalperState.hourKey(hourOf(c.closedAtMs))
                        )
                        bump(groups, c.order, extra) {
                            it.copy(
                                closed = it.closed + 1,
                                wins = it.wins + (if (c.pnlUsd > 0.0) 1 else 0),
                                losses = it.losses + (if (c.pnlUsd < 0.0) 1 else 0),
                                pnlUsd = it.pnlUsd + c.pnlUsd,
                                wonUsd = it.wonUsd + (if (c.pnlUsd > 0.0) c.pnlUsd else 0.0),
                                lostUsd = it.lostUsd + (if (c.pnlUsd < 0.0) -c.pnlUsd else 0.0),
                                feesUsd = it.feesUsd + fees,
                                biggestWinUsd = maxOf(it.biggestWinUsd, c.pnlUsd),
                                biggestLossUsd = maxOf(it.biggestLossUsd, -c.pnlUsd),
                                targets = it.targets + (if (c.kind == ExitKind.TARGET) 1 else 0),
                                stops = it.stops + (if (c.kind == ExitKind.STOP) 1 else 0),
                                timeouts = it.timeouts + (if (c.kind == ExitKind.TIMEOUT) 1 else 0),
                                settled = it.settled + (if (c.kind == ExitKind.SETTLED) 1 else 0)
                            ).withWindowClose(c.order.ticker, c.pnlUsd)
                        }
                        trades += ScalpTrade.of(c, run)
                        orders += c.closedAtMs to ScalpTradeLog.orderRow(c.order, c, c.closedAtMs, run)
                        // One point per time bucket: the running total after the bucket's last scalp.
                        val point = CurvePoint(c.closedAtMs, (groups[ScalperState.ALL] ?: ScalpStats()).pnlUsd)
                        val last = curve.lastOrNull()
                        curve = if (last != null && last.tMs / bucketMs == point.tMs / bucketMs) {
                            curve.dropLast(1) + point
                        } else {
                            curve + point
                        }
                        if (curve.size > MAX_CURVE) {
                            // Too many points to keep: halve the detail of the whole curve, never its end.
                            bucketMs *= 2
                            curve = curve.filterIndexed { i, _ -> i % 2 == 1 || i == curve.lastIndex }
                        }
                        val row = ScalpRow(
                            strategy = c.order.strategy.name,
                            ticker = c.order.ticker,
                            side = c.order.side,
                            entry = c.order.price,
                            exit = c.exitPrice,
                            kind = c.kind.name,
                            pnlUsd = c.pnlUsd,
                            closedAtMs = c.closedAtMs
                        )
                        recent = (listOf(row) + recent).take(MAX_RECENT)
                    }
                }
            }
            val next = ScalperState(
                groups = groups,
                recent = recent,
                startedAtMs = started,
                // The loaded record's version, not the current one: a legacy record whose archive failed
                // must still read as legacy on the next start, so the archive is retried there.
                version = cur.version,
                curve = curve,
                curveBucketMs = bucketMs
            )
            _state.value = next
            if (trades.isNotEmpty() || orders.isNotEmpty()) runCatching { record(trades, orders) }
            val now = nowMs()
            if (forcePersist || now - lastPersistMs >= persistEveryMs) {
                lastPersistMs = now
                runCatching { persist(next) }
            }
        }
    }

    /** Clear the record. Paper only: nothing to unwind. */
    fun reset() {
        synchronized(lock) {
            _state.value = ScalperState.fresh()
            lastPersistMs = nowMs()
            runCatching { persist(ScalperState.fresh()) }
        }
    }

    private fun bump(
        groups: HashMap<String, ScalpStats>,
        order: ScalpOrder,
        extraKeys: List<String> = emptyList(),
        f: (ScalpStats) -> ScalpStats
    ) {
        val keys = ArrayList<String>(6)
        keys += extraKeys
        keys += ScalperState.ALL
        keys += order.strategy.name
        if (order.strategy == ScalpStrategy.ML_REST) {
            order.bucket?.let { keys += ScalperState.bucketKey(it) }
            if (order.queueAware) keys += ScalperState.QUEUE_AWARE
        }
        for (k in keys) groups[k] = f(groups[k] ?: ScalpStats())
    }

    companion object {
        const val MAX_RECENT = 40

        /** The P&L curve starts with one point a minute and halves its detail past [MAX_CURVE] points. */
        const val CURVE_BUCKET_MS = 60_000L
        const val MAX_CURVE = 720
    }
}

/**
 * The paper scalper: listens to every public trade on the live Bitcoin
 * window, rebuilds the model's features ([PrintGrid]), runs every
 * [ScalpStrategy] at once ([ScalperEngine]) and keeps score
 * ([ScalperLedger]). **Paper only.** It holds no trade client and no
 * credentials: there is no path from here to an order.
 *
 * What is real: the trade prints that fill its orders, the best bid / ask it
 * prices from, and the queue shown ahead of it (when the Live signals book is
 * on). What is assumed: nobody ahead of it cancels, its own orders move
 * nothing, and with no book the queue is taken as 3,500 contracts.
 */
class PaperScalper(
    val model: ScalperModel?,
    val ledger: ScalperLedger,
    private val topOfBook: (String) -> TopOfBook?,
    private val bookLevels: (String) -> BookLevelSnapshot?,
    private val closeMs: (String) -> Long?,
    val engine: ScalperEngine = ScalperEngine(),
    /** Called first by [reset]: the store archives the trade files; false when that did not complete. */
    private val onReset: () -> Boolean = { true },
    /** YES fair price from the live spot ([ScalpFair]) for Kashi's late favourites; null when unknown. */
    private val fairYes: (ticker: String, nowMs: Long) -> Double? = { _, _ -> null },
    /** What runs. The retired strategies ([ScalpStrategy.RETIRED]) open nothing; tests and the replay pass them all. */
    private val strategies: List<ScalpStrategy> = ScalpStrategy.ACTIVE,
    /**
     * Own-band strategies normally need both a fresh taker-buy and a fresh taker-sell print (10 s) next to the
     * book; true accepts either one. Off in the app (the cached book has no age); the replay measures it.
     */
    private val oneSidedPrints: Boolean = false
) {
    private val grids = HashMap<String, PrintGrid>()
    private val lastDecision = HashMap<String, IntArray>()   // ticker -> last decision second per cadence slot
    /** Once-a-window strategies (the opening ride, late edge) that already bought on a ticker. */
    private val onceDone = HashMap<String, MutableSet<ScalpStrategy>>()
    /** Orders each strategy opened on a ticker this window, and when it opened its last one (your limits). */
    private val ordersThisWindow = HashMap<String, IntArray>()
    private val lastOrderMs = HashMap<String, LongArray>()
    /** Each strategy's closed paper P&L today (UTC day), for the daily loss pause. Since the app started. */
    private var lossDay: Long = -1L
    private val dayPnl = DoubleArray(ScalpStrategy.values().size)

    private fun utcDay(ms: Long): Long = Math.floorDiv(ms, 86_400_000L)

    /** Book [events] in the ledger, keeping today's per-strategy P&L. */
    private fun book(events: List<ScalpEvent>, forcePersist: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        val today = utcDay(nowMs)
        if (today != lossDay) {
            lossDay = today
            dayPnl.fill(0.0)
        }
        for (e in events) if (e is ScalpEvent.Closed && utcDay(e.scalp.closedAtMs) == today) {
            dayPnl[e.scalp.order.strategy.ordinal] += e.scalp.pnlUsd
        }
        ledger.apply(events, forcePersist)
    }

    /** True once [st] lost its daily limit today. */
    private fun pausedForLoss(st: ScalpStrategy, limit: Double?, nowMs: Long): Boolean {
        if (limit == null) return false
        if (utcDay(nowMs) != lossDay) return false
        return dayPnl[st.ordinal] <= -limit + 1e-9
    }

    private fun forgetWindow(ticker: String) {
        ordersThisWindow.remove(ticker)
        lastOrderMs.remove(ticker)
    }

    /** Today's closed paper P&L of [st] (UTC day, since the app started). */
    @Synchronized
    fun todayPnl(st: ScalpStrategy, nowMs: Long = System.currentTimeMillis()): Double =
        if (utcDay(nowMs) == lossDay) dayPnl[st.ordinal] else 0.0

    /** A public trade print. [takerSide] is Kalshi's `taker_side`: "yes" = the taker bought YES at [yesPrice]. */
    @Synchronized
    fun onTrade(ticker: String, takerSide: String?, yesPrice: Double?, contracts: Double?, nowMs: Long) {
        if (!CryptoMarkets.isLiveTicker(ticker)) return
        val takerYes = when (takerSide?.lowercase(Locale.US)) {
            "yes" -> true
            "no" -> false
            else -> return
        }
        val p = yesPrice?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return
        val c = contracts?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val grid = gridFor(ticker, nowMs) ?: return
        val events = ArrayList<ScalpEvent>()
        if (engine.hasWork(ticker)) {
            // Stops first, then fills, as in the tests of the rule.
            events += clock(ticker, grid, nowMs)
            events += engine.onTrade(ticker, takerYes, p, c, nowMs) { side, price -> offerQueue(ticker, side, price) }
        }
        grid.add(nowMs, takerYes, p, c)
        events += decide(ticker, grid, nowMs)
        book(events, nowMs = nowMs)
    }

    /** Time passing with no trade: expire, stop out and time out. Cheap when nothing is working. */
    @Synchronized
    fun onClock(ticker: String, nowMs: Long) {
        if (!engine.hasWork(ticker)) return
        val grid = grids[ticker] ?: return
        book(clock(ticker, grid, nowMs), nowMs = nowMs)
    }

    /** The window settled: open scalps pay $1 or $0 and the window's grid is dropped. */
    @Synchronized
    fun settle(ticker: String, result: String, nowMs: Long = System.currentTimeMillis()) {
        val events = engine.settle(ticker, result, nowMs)
        grids.remove(ticker)
        lastDecision.remove(ticker)
        onceDone.remove(ticker)
        forgetWindow(ticker)
        book(events, forcePersist = events.isNotEmpty(), nowMs = nowMs)
    }

    @Synchronized
    fun openTickers(): Set<String> = engine.openTickers()

    @Synchronized
    fun working(): Pair<Int, Int> = engine.workingTotal()

    /**
     * The live window the scalper is watching, for the Arena's live chart:
     * its YES mid per second from the prints (seconds with no fresh quote are
     * left out) and the paper positions open on it. Null before the first
     * print of a window.
     */
    @Synchronized
    fun liveWindow(nowMs: Long = System.currentTimeMillis()): LiveWindow? {
        val e = grids.entries.maxByOrNull { it.value.openMs } ?: return null
        val grid = e.value
        val last = grid.secondOf(nowMs).coerceAtMost(PrintGrid.SECONDS - 1)
        if (last < 0) return null
        val mids = ArrayList<Pair<Int, Double>>(last + 1)
        for (s in 0..last) {
            val m = grid.yesMid(s)
            if (!m.isNaN()) mids += s to m
        }
        return LiveWindow(e.key, grid.openMs, mids, engine.openPositions(e.key))
    }

    /**
     * Archive the trade files, then start a fresh record. When the archive does not complete, nothing
     * is reset (the old rows would still show beside zeroed totals) and this returns false.
     */
    @Synchronized
    fun reset(): Boolean {
        if (!runCatching { onReset() }.getOrDefault(false)) return false
        engine.clear()
        onceDone.clear()
        ordersThisWindow.clear()
        lastOrderMs.clear()
        dayPnl.fill(0.0)
        ledger.reset()
        return true
    }

    private fun gridFor(ticker: String, nowMs: Long): PrintGrid? {
        grids[ticker]?.let { return it }
        val close = closeMs(ticker) ?: return null
        val open = close - PrintGrid.SECONDS * 1000L
        if (nowMs < open || nowMs >= close) return null
        // One live window at a time: forget grids of windows that have closed.
        val it = grids.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value.openMs > PrintGrid.SECONDS * 1000L + GRID_KEEP_MS && !engine.hasWork(e.key)) {
                onceDone.remove(e.key)
                lastDecision.remove(e.key)
                forgetWindow(e.key)
                it.remove()
            }
        }
        return PrintGrid(open).also { grids[ticker] = it }
    }

    private fun clock(ticker: String, grid: PrintGrid, nowMs: Long): List<ScalpEvent> {
        val s = grid.secondOf(nowMs)
        // After the close there is nothing to sell into: open scalps wait for the settlement.
        if (s >= PrintGrid.SECONDS) return emptyList()
        val top = topOfBook(ticker)
        val bidYes = top?.yesBid ?: grid.yesBid(s).takeIf { !it.isNaN() }
        val bidNo = top?.noBid ?: grid.yesAsk(s).takeIf { !it.isNaN() }?.let { 1.0 - it }
        return engine.onClock(ticker, nowMs, bidYes, bidNo)
    }

    /**
     * Decide with everything up to the end of the last completed second.
     * Each strategy keeps its own cadence; a late trade print still decides,
     * on the current second, so the cadence never needs an exact tick.
     */
    private fun decide(ticker: String, grid: PrintGrid, nowMs: Long): List<ScalpEvent> {
        val s = grid.secondOf(nowMs) - 1
        // The opening ride trades in the first minute, the late favourites inside the last one;
        // everything else between minute 1 and minute 13.
        if (s < OPEN_FIRST_SECOND || s > LATE_LAST_SECOND) return emptyList()
        // Slot 0 is the last second looked at (dozens of prints share a second); then one slot per strategy.
        val last = lastDecision.getOrPut(ticker) { IntArray(ScalpStrategy.values().size + 1) { -1_000 } }
        if (last[0] == s) return emptyList()
        last[0] = s
        val tte = PrintGrid.SECONDS - s
        val tuning = engine.tuning
        val done = onceDone[ticker]
        val inWindow = { st: ScalpStrategy, t: Tuned ->
            val late = t.secondsLeft
            when {
                st.opening -> s <= OPEN_LAST_SECOND
                late != null -> tte in late
                else -> s in PrintGrid.MIN_DECISION_SECOND..PrintGrid.MAX_DECISION_SECOND
            }
        }
        val due0 = strategies.filter { st ->
            val t = tuning.tuned(st)
            t.enabled && s - last[st.ordinal + 1] >= t.cadenceSeconds && inWindow(st, t) && t.inMinutes(s) &&
                (done == null || st !in done) && !pausedForLoss(st, t.dailyLossUsd, nowMs)
        }
        if (due0.isEmpty()) return emptyList()
        for (st in due0) last[st.ordinal + 1] = s
        // The opening ride cannot wait for a minute of prints; everything else needs one.
        val warm = grid.warm(s)
        val due = if (warm) due0 else due0.filter { it.opening }
        if (due.isEmpty()) return emptyList()
        val events = ArrayList<ScalpEvent>()
        val top = topOfBook(ticker)
        val fairY = if (due.any { it.fav != null || it.opening }) {
            runCatching { fairYes(ticker, nowMs) }.getOrNull()?.takeIf { it.isFinite() }
        } else {
            null
        }
        val usHours = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneOffset.UTC).hour in 13..20
        // The opening ride's side: where the live Bitcoin price leans, else the market's own favourite.
        val rideYes: Boolean? = if (ScalpStrategy.OPENING_RIDE in due) {
            val lean = tuning.tuned(ScalpStrategy.OPENING_RIDE).triggerValue(OPEN_LEAN)
            val yesMid = top?.let { t ->
                val b = t.yesBid
                val a = t.yesAsk
                if (b != null && a != null && b.isFinite() && a.isFinite() && b < a) (b + a) / 2.0 else null
            } ?: grid.mid("YES", s).takeIf { !it.isNaN() }
            when {
                fairY != null -> if (fairY >= lean - EPS) true else if (1.0 - fairY >= lean - EPS) false else null
                yesMid != null -> if (yesMid > 0.5 + MARKET_LEAN - EPS) true else if (yesMid < 0.5 - MARKET_LEAN + EPS) false else null
                else -> null
            }
        } else {
            null
        }
        for (side in SIDES) {
            val yes = side == "YES"
            val realBid = (if (yes) top?.yesBid else top?.noBid)?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
            val realAsk = (if (yes) top?.yesAsk else top?.noAsk)?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
            val realQty = (if (yes) top?.yesBidQty else top?.noBidQty)?.takeIf { it.isFinite() && it >= 0.0 }
            // The cached book carries no age, so it is trusted only while it agrees with the trade prints
            // (within BOOK_GRID_TOLERANCE); a stalled book drifting from the live prints is ignored.
            val gridMid = grid.mid(side, s)
            val bookOk = realBid != null && realAsk != null && realBid < realAsk &&
                (gridMid.isNaN() || kotlin.math.abs((realBid + realAsk) / 2.0 - gridMid) <= BOOK_GRID_TOLERANCE + EPS)
            // The original scalpers need the prints to quote the side inside 10–90¢; the strategies with their
            // own price band (the holds) only need a live bid below a live ask.
            val scalpOk = grid.quotable(side, s)
            val bid = if (bookOk) realBid!! else grid.bid(side, s)
            val ask = if (bookOk) realAsk!! else grid.ask(side, s)
            val fresh = bid.isFinite() && ask.isFinite() && bid < ask
            if (!scalpOk && !fresh) continue
            val queue = if (bookOk) realQty else null
            val d10 = grid.move(side, s, 10)
            val d30 = grid.move(side, s, 30)
            val spread = ask - bid
            val mid = (bid + ask) / 2.0
            val askQty = if (bookOk) (if (yes) top?.yesAskQty else top?.noAskQty)?.takeIf { it.isFinite() && it >= 0.0 } else null
            // What this order saw, for the order log only.
            fun seen(x: FloatArray?) = ScalpContext(
                bid = bid, ask = ask, bidQty = queue, askQty = askQty,
                move10 = d10.takeIf { !it.isNaN() }, move30 = d30.takeIf { !it.isNaN() }, features = x
            )
            val opened = ordersThisWindow.getOrPut(ticker) { IntArray(ScalpStrategy.values().size) }
            val lastOpen = lastOrderMs.getOrPut(ticker) { LongArray(ScalpStrategy.values().size) { Long.MIN_VALUE / 2 } }
            for (st in due) {
                val tn = tuning.tuned(st)
                // Your limits: one side only, the widest spread, orders per window, and a wait between orders.
                if (!tn.sideAllowed(side)) continue
                val widest = tn.maxSpread
                if (widest != null && spread > widest + EPS) continue
                val most = tn.maxOrdersPerWindow
                if (most != null && opened[st.ordinal] >= most) continue
                if (tn.cooldownMs > 0 && nowMs - lastOpen[st.ordinal] < tn.cooldownMs) continue
                fun noteOrder() {
                    opened[st.ordinal]++
                    lastOpen[st.ordinal] = nowMs
                }
                // The strategy's main trigger: yours when you set one, else its default.
                fun trig(default: Double) = tn.triggerValue(default)
                val ownBand = tn.ownBand
                // Own-band strategies may trade outside the grid's 10–90¢ quotable band, but still need a
                // current grid quote: the cached book has no age, and a stalled book would price stale.
                val printsLive = if (oneSidedPrints) {
                    !grid.bid(side, s).isNaN() || !grid.ask(side, s).isNaN()
                } else {
                    !grid.bid(side, s).isNaN() && !grid.ask(side, s).isNaN()
                }
                if (if (ownBand) !(fresh && printsLive) else !scalpOk) continue
                val fire = when (st) {
                    ScalpStrategy.ML_REST -> null
                    ScalpStrategy.DIP_HUNTER, ScalpStrategy.DIP_HUNTER_REST ->
                        !d10.isNaN() && d10 <= -trig(ScalpStrategy.FAST_MOVE_DOLLARS) + EPS
                    ScalpStrategy.MOMENTUM_SNIPER, ScalpStrategy.MOMENTUM_SNIPER_REST ->
                        !d10.isNaN() && d10 >= trig(ScalpStrategy.FAST_MOVE_DOLLARS) - EPS
                    ScalpStrategy.EXTREME_REVERSION, ScalpStrategy.EXTREME_REVERSION_REST ->
                        !d30.isNaN() && d30 <= -trig(ScalpStrategy.EXTREME_MOVE_DOLLARS) + EPS
                    ScalpStrategy.BOUNCE -> {
                        // A fall of 5¢ from the minute's high to a low in the last 20 s (high first, then low),
                        // and the first 1–3¢ back up.
                        // The rebound is read on the same print grid as the low.
                        val hl = grid.highThenLow(side, s, 60, 20)
                        spread <= ScalpStrategy.TIGHT_SPREAD_DOLLARS + EPS && hl != null && !gridMid.isNaN() &&
                            hl.first - hl.second >= trig(ScalpStrategy.BOUNCE_DROP_DOLLARS) - EPS &&
                            gridMid - hl.second >= ScalpStrategy.BOUNCE_REBOUND_DOLLARS - EPS && gridMid - hl.second <= 0.03 + EPS
                    }
                    ScalpStrategy.BREAKOUT_50 -> {
                        // Both ends from the print grid: the book mid now against a grid mid 10 s ago is not a crossing.
                        val now = gridMid
                        val level = trig(0.50)
                        spread <= ScalpStrategy.TIGHT_SPREAD_DOLLARS + EPS && !d10.isNaN() && !now.isNaN() &&
                            now >= level - EPS && now - d10 < level - EPS
                    }
                    ScalpStrategy.IMPROVE_1C ->
                        s >= 180 && spread >= trig(ScalpStrategy.TIGHT_SPREAD_DOLLARS) - EPS
                    ScalpStrategy.FLOW_FADE -> {
                        val other = grid.takerBought(if (yes) "NO" else "YES", s, 30)
                        val mine = grid.takerBought(side, s, 30)
                        other >= trig(ScalpStrategy.FLOW_MIN_CONTRACTS) && other >= ScalpStrategy.FLOW_RATIO * mine
                    }
                    ScalpStrategy.LATE_FAVORITE -> s >= (trig(10.0) * 60).toInt()
                    ScalpStrategy.EARLY_FAVORITE -> s in 180 until (trig(10.0) * 60).toInt()
                    ScalpStrategy.MOMENTUM_HOLD ->
                        s >= 300 && !d30.isNaN() && d30 >= trig(ScalpStrategy.RIDE_MOVE_DOLLARS) - EPS
                    ScalpStrategy.LONG_SHOT -> s >= (trig(5.0) * 60).toInt()
                    ScalpStrategy.STRETCH_FADE -> {
                        val avg = grid.avgMid(side, s, 60)
                        spread <= ScalpStrategy.TIGHT_SPREAD_DOLLARS + EPS && !avg.isNaN() && !gridMid.isNaN() &&
                            avg - gridMid >= trig(ScalpStrategy.STRETCH_DOLLARS) - EPS
                    }
                    ScalpStrategy.SQUEEZE_BREAKOUT -> {
                        // The range is the two minutes before the last 5 s, so the break itself is not inside it,
                        // and it must have been quoted through both of its minutes: a few prints are no squeeze.
                        val hi = grid.highMid(side, s - 5, 120)
                        val lo = grid.lowMid(side, s - 5, 120)
                        s >= 125 && spread <= ScalpStrategy.TIGHT_SPREAD_DOLLARS + EPS && grid.coveredBothHalves(side, s - 5, 120) &&
                            !hi.isNaN() && !lo.isNaN() && !gridMid.isNaN() &&
                            hi - lo <= trig(ScalpStrategy.SQUEEZE_RANGE_DOLLARS) + EPS &&
                            gridMid - hi >= ScalpStrategy.SQUEEZE_BREAK_DOLLARS - EPS
                    }
                    ScalpStrategy.RANGE_FLOOR -> {
                        val hi = grid.highMid(side, s, 120)
                        val lo = grid.lowMid(side, s, 120)
                        s >= 120 && grid.coveredBothHalves(side, s, 120) && !hi.isNaN() && !lo.isNaN() && !gridMid.isNaN() &&
                            hi - lo >= trig(ScalpStrategy.FLOOR_MIN_RANGE_DOLLARS) - EPS &&
                            hi - lo <= ScalpStrategy.FLOOR_MAX_RANGE_DOLLARS + EPS &&
                            gridMid <= lo + 0.005 + EPS
                    }
                    ScalpStrategy.TREND_PULLBACK -> {
                        val d120 = grid.move(side, s, 120)
                        spread <= ScalpStrategy.TIGHT_SPREAD_DOLLARS + EPS && !d120.isNaN() && !d10.isNaN() &&
                            d120 >= trig(ScalpStrategy.PULLBACK_TREND_DOLLARS) - EPS && d10 <= -ScalpStrategy.PULLBACK_DIP_DOLLARS + EPS
                    }
                    ScalpStrategy.FLOW_FOLLOW -> {
                        val mine = grid.takerBought(side, s, 30)
                        val other = grid.takerBought(if (yes) "NO" else "YES", s, 30)
                        mine >= trig(ScalpStrategy.FLOW_MIN_CONTRACTS) && mine >= ScalpStrategy.FLOW_RATIO * other
                    }
                    ScalpStrategy.WHALE_FOLLOW ->
                        grid.biggestTakerBuy(side, s, 5) >= trig(ScalpStrategy.WHALE_CONTRACTS) - EPS
                    ScalpStrategy.QUIET_MAKER -> {
                        // Flat: the highest and lowest mid of the last 30 s are the same, with at least half of them quoted.
                        val w = trig(30.0).toInt().coerceIn(5, 300)
                        val hi = grid.highMid(side, s, w)
                        val lo = grid.lowMid(side, s, w)
                        s >= 60 + w && !hi.isNaN() && !lo.isNaN() && hi - lo <= EPS && !grid.avgMid(side, s, w).isNaN()
                    }
                    ScalpStrategy.OPENING_DRIVE -> {
                        val since = if (s in 120..240) grid.move(side, s, s - 60) else Double.NaN
                        !since.isNaN() && since >= trig(ScalpStrategy.DRIVE_DOLLARS) - EPS
                    }
                    ScalpStrategy.LATE_TIEBREAK -> {
                        val d60 = grid.move(side, s, 60)
                        s >= 720 && !d60.isNaN() && d60 >= trig(ScalpStrategy.TIEBREAK_MOVE_DOLLARS) - EPS
                    }
                    ScalpStrategy.HEAVY_FAVORITE -> s >= (trig(12.0) * 60).toInt()
                    ScalpStrategy.PANIC_FADE ->
                        !d10.isNaN() && d10 <= -trig(ScalpStrategy.PANIC_DROP_DOLLARS) + EPS &&
                            grid.takerBought(if (yes) "NO" else "YES", s, 10) >= ScalpStrategy.PANIC_CONTRACTS
                    ScalpStrategy.STEADY_CLIMB -> {
                        val d60 = grid.move(side, s, 60)
                        !d10.isNaN() && !d30.isNaN() && !d60.isNaN() &&
                            d10 >= 0.01 - EPS && d30 >= 0.03 - EPS && d60 >= trig(0.04) - EPS
                    }
                    // The two book strategies need the live book (the prints show no sizes).
                    ScalpStrategy.PENNY_SCALPER ->
                        queue != null && spread <= 0.01 + EPS && queue <= trig(ScalpStrategy.PENNY_MAX_QUEUE) + EPS
                    ScalpStrategy.BOOK_IMBALANCE ->
                        queue != null && askQty != null && askQty > 0.0 &&
                            queue >= ScalpStrategy.IMBALANCE_MIN_CONTRACTS && queue >= trig(ScalpStrategy.IMBALANCE_RATIO) * askQty
                    // From minute 10 by default ([ScalpStrategy.startMinute], checked with the other minutes above).
                    ScalpStrategy.LATE_COMEBACK ->
                        !d30.isNaN() && d30 >= trig(ScalpStrategy.COMEBACK_MOVE_DOLLARS) - EPS
                    ScalpStrategy.KASHI_FAV_BASE, ScalpStrategy.KASHI_FAV_STRICT, ScalpStrategy.KASHI_FAV_97,
                    ScalpStrategy.KASHI_FAV_LAST30, ScalpStrategy.KASHI_FAV_NIGHT -> {
                        val f = st.fav!!
                        val fair = fairY?.let { if (yes) it else 1.0 - it }
                        fair != null && fair >= trig(f.fairMin) - EPS && fair - ask >= (tn.favGap ?: f.gap) - EPS &&
                            !(f.offUsHours && usHours)
                    }
                    ScalpStrategy.OPENING_RIDE -> rideYes == yes
                    ScalpStrategy.LATE_EDGE -> {
                        val f = st.fav!!
                        val fair = fairY?.let { if (yes) it else 1.0 - it }
                        // The taker fee per contract on this order, rounded up per order as Kalshi charges it.
                        val feePer = com.dirk.kalshiodds.signal.trade.KalshiFee.total(tn.contracts, ask, engine.config.feeRate) /
                            tn.contracts
                        fair != null && fair >= f.fairMin - EPS && fair - ask >= feePer + trig(f.gap) - EPS
                    }
                }
                if (fire == null) {
                    val m = model ?: continue
                    val x = grid.features(side, s) ?: continue
                    val pred = m.predict(x)
                    // The model's own cut, unless you set another (in cents, as the model speaks).
                    if (pred < tn.trigger(m.thetaCents)) continue
                    val expected = m.expectedCents(pred, queue ?: engine.config.unknownQueue)
                    engine.post(st, ticker, side, bid, queue, pred, expected, nowMs, seen(x))?.let {
                        events += it
                        noteOrder()
                    }
                } else if (fire) {
                    if (st.taker) {
                        val bought = engine.buyNow(
                            st, ticker, side, ask, { sd, px -> offerQueue(ticker, sd, px) }, nowMs, seen(null)
                        )
                        if (bought.isNotEmpty() && (st.opening || st == ScalpStrategy.LATE_EDGE)) {
                            onceDone.getOrPut(ticker) { HashSet() }.add(st)
                        }
                        if (bought.isNotEmpty()) noteOrder()
                        events += bought
                    } else {
                        // 1¢ better rests inside the spread, so nobody is ahead of it at its price.
                        val improve = st == ScalpStrategy.IMPROVE_1C
                        val px = if (improve) ScalperEngine.round4(bid + 0.01) else bid
                        val q = if (improve) 0.0 else queue
                        engine.post(st, ticker, side, px, q, 0.0, 0.0, nowMs, seen(null))?.let {
                            events += it
                            noteOrder()
                        }
                    }
                }
            }
        }
        return events
    }

    /**
     * Contracts already offered at [price] on [side]: an offer of YES at `a`
     * is a NO bid at `1 − a`, so it is the size resting there. Null when the
     * book is not available; 0 when the book is there and the level is empty.
     */
    private fun offerQueue(ticker: String, side: String, price: Double): Double? {
        val book = bookLevels(ticker) ?: return null
        val levels = if (side == "YES") book.no else book.yes
        val target = 1.0 - price
        return levels.firstOrNull { abs(it.first - target) <= ScalperEngine.TICK_EPS }?.second ?: 0.0
    }

    companion object {
        private val SIDES = listOf("YES", "NO")
        private const val EPS = 1e-9

        /** Last decision second: 5 s before the close, the end of the late-edge window. */
        const val LATE_LAST_SECOND = PrintGrid.SECONDS - 5

        /** The opening ride looks from the 2nd second to the end of the first minute. */
        const val OPEN_FIRST_SECOND = 2
        const val OPEN_LAST_SECOND = 60

        /** Fair price the opening ride needs on a side before it buys it, and the market lean it falls back on. */
        const val OPEN_LEAN = 0.51
        const val MARKET_LEAN = 0.02

        /** Farthest the cached book's mid may sit from the live print mid before the book is ignored. */
        const val BOOK_GRID_TOLERANCE = 0.03
        private const val GRID_KEEP_MS = 120_000L
    }
}

/** The window on screen in the Arena's live chart. [mids] = (second since the open, YES mid). */
data class LiveWindow(
    val ticker: String,
    val openMs: Long,
    val mids: List<Pair<Int, Double>>,
    val open: List<ScalperEngine.OpenPosition>
)

/** On-device store for the paper scalper's record. */
class ScalperStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Every order on disk (`filesDir/scalper/`): the trade list, the export and the research data. */
    val log: ScalpTradeLog = ScalpTradeLog(java.io.File(context.applicationContext.filesDir, ScalpTradeLog.DIR_NAME))

    val ledger: ScalperLedger = ScalperLedger(
        initial = load(),
        persist = {
            save(it)
            log.flush()
        },
        record = { trades, orders -> log.add(trades, orders) },
        run = log.run
    )

    /** Your per-strategy settings, kept with the record ([ScalpTuning]). */
    val tuning: ScalpTuning = ScalpTuning(
        initial = runCatching {
            prefs.getString(KNOBS_KEY, null)?.let { json.decodeFromString(knobsSerializer, it) }
        }.getOrNull().orEmpty(),
        persist = { map -> runCatching { prefs.edit().putString(KNOBS_KEY, json.encodeToString(knobsSerializer, map)).apply() } }
    )

    /** The maker-fee, strike and settlement checks against Kalshi ([ContractChecks]). */
    val checks: ContractCheckBook = ContractCheckBook(
        initial = runCatching {
            prefs.getString(CHECKS_KEY, null)?.let { json.decodeFromString(ContractCheckState.serializer(), it) }
        }.getOrNull() ?: ContractCheckState(),
        persist = { st -> runCatching { prefs.edit().putString(CHECKS_KEY, json.encodeToString(ContractCheckState.serializer(), st)).apply() } }
    )

    /** The trained model from assets, or null when it cannot be read (the fast strategies still run). */
    val model: ScalperModel? = runCatching {
        context.assets.open(ScalperModel.ASSET_NAME).bufferedReader().use { ScalperModel.parse(it.readText()) }
    }.getOrNull()

    private fun load(): ScalperState {
        val raw = prefs.getString(KEY, null) ?: return ScalperState.fresh()
        val saved = runCatching { json.decodeFromString(ScalperState.serializer(), raw) }.getOrNull()
        // An older record (1.8.4: no won / lost / fee totals; 1.8.5–1.8.8: seven strategies only) is not
        // mixed into the arena: its trade files are archived and a new record starts for the whole arena (strategies added later simply start at zero).
        return ScalperState.upgraded(saved) { log.archive() }
    }

    private fun save(state: ScalperState) {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(ScalperState.serializer(), state)).apply() }
    }

    private companion object {
        const val PREFS = "bitcoin_claude_scalper"
        const val KEY = "state_json"
        const val KNOBS_KEY = "strategy_settings_json"
        const val CHECKS_KEY = "contract_checks_json"
        val knobsSerializer = kotlinx.serialization.serializer<Map<String, ScalpKnobs>>()
    }
}

/** Home-card copy for the paper scalper. Pure, unit tested; the composable only lays these out. */
data class ScalperSummary(
    val title: String,
    val totalLine: String,
    /** One line per strategy: those that have traded first, best P&L on top. */
    val strategyLines: List<String>,
    /** ML scalper by queue, and its queue-aware subset. Empty until it has closed a scalp. */
    val queueLines: List<String>,
    val workingLine: String,
    val note: String
) {
    companion object {
        val TITLE = "Strategy arena · ${ScalpStrategy.ACTIVE.size} at once · PAPER ONLY"

        /** The queue-aware subset on the recorded order books: a short queue was a level about to break. */
        const val QUEUE_AWARE_HISTORY = "−1.73¢"
        const val NOTE =
            "Every strategy at once on the same live Bitcoin window and the same real trade prints, " +
                "10 contracts an order, no caps; the best P&L is on top. Resting orders fill only after the " +
                "queue ahead has traded; buy-now entries and stop / time-out exits pay the taker fee; " +
                "\"hold\" strategies ride to the settlement. In brackets: what a filled scalp of that kind made " +
                "per contract on 7 days of recorded order books, real queue counted (\"new\" = added in 1.9.0 or later, " +
                "no history yet). Needs Live signals on. Never sends an order."

        fun of(
            state: ScalperState,
            working: Pair<Int, Int> = 0 to 0,
            shown: List<ScalpStrategy> = ScalpStrategy.ACTIVE
        ): ScalperSummary {
            val all = state.stats(ScalperState.ALL)
            val total = "All strategies: ${money(all.pnlUsd)} · ${all.closed} scalps" + pct(all)
            val strategies = shown
                .map { it to state.stats(it.name) }
                // Strategies that have traded first, best P&L on top; idle ones after, in their own order.
                .sortedWith(
                    compareByDescending<Pair<ScalpStrategy, ScalpStats>> { it.second.closed > 0 }
                        .thenByDescending { it.second.pnlUsd }
                        .thenBy { it.first.ordinal }
                )
                .map { (st, s) ->
                    val hist = st.historyCents?.let { String.format(Locale.US, " [history %+.2f¢]", it) }
                        ?: " [new · ${st.origin}]"
                    if (s.closed == 0) {
                        "${st.label}: no scalps yet$hist"
                    } else {
                        val per = s.perScalpUsd?.let { " · ${money(it)} each" }.orEmpty()
                        "${st.label}: ${money(s.pnlUsd)} · ${s.closed}${pct(s)}$per$hist"
                    }
                }
            val ml = state.stats(ScalpStrategy.ML_REST.name)
            val queue = if (ml.closed == 0) {
                emptyList()
            } else {
                val aware = state.stats(ScalperState.QUEUE_AWARE)
                listOf(
                    "ML scalper, only orders expected to pay where they sat in the queue: ${money(aware.pnlUsd)} · " +
                        "${aware.closed}${pct(aware)} [history $QUEUE_AWARE_HISTORY]"
                ) +
                    QueueBucket.values().mapNotNull { b ->
                        val s = state.stats(ScalperState.bucketKey(b))
                        if (s.closed == 0) null else "ML scalper, ${b.label}: ${money(s.pnlUsd)} · ${s.closed}${pct(s)}"
                    }
            }
            val workingLine = "Working now: ${working.first} resting bids · ${working.second} open scalps · " +
                "exits ${all.targets} target / ${all.stops} stop / ${all.timeouts} time-out / ${all.settled} settled"
            return ScalperSummary(TITLE, total, strategies, queue, workingLine, NOTE)
        }

        fun money(v: Double): String {
            val sign = if (v < 0) "−" else "+"
            return String.format(Locale.US, "%s$%,.2f", sign, abs(v))
        }

        private fun pct(s: ScalpStats): String =
            s.winRate?.let { String.format(Locale.US, " (%.0f%% won)", it * 100.0) }.orEmpty()
    }
}
