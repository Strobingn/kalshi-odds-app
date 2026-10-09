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
    val biggestLossUsd: Double = 0.0
) {
    val winRate: Double? get() = if (closed > 0) wins.toDouble() / closed else null
    val perScalpUsd: Double? get() = if (closed > 0) pnlUsd / closed else null
    val avgWinUsd: Double? get() = if (wins > 0) wonUsd / wins else null
    val avgLossUsd: Double? get() = if (losses > 0) lostUsd / losses else null
    val flat: Int get() = closed - wins - losses
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

    companion object {
        const val VERSION = 2
        const val ALL = "ALL"
        const val QUEUE_AWARE = "QUEUE_AWARE"
        const val COIN_PREFIX = "COIN_"
        const val HOUR_PREFIX = "HOUR_"
        fun bucketKey(b: QueueBucket): String = "Q_" + b.name
        fun coinKey(coin: String): String = COIN_PREFIX + coin
        fun hourKey(hour: Int): String = HOUR_PREFIX + hour

        /** An empty record in the current format. */
        fun fresh(): ScalperState = ScalperState(version = VERSION)

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
                            )
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
                version = ScalperState.VERSION,
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
    /** Called after [reset]: the store archives the trade files. */
    private val onReset: () -> Unit = {}
) {
    private val grids = HashMap<String, PrintGrid>()
    private val lastDecision = HashMap<String, IntArray>()   // ticker -> last decision second per cadence slot

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
        ledger.apply(events)
    }

    /** Time passing with no trade: expire, stop out and time out. Cheap when nothing is working. */
    @Synchronized
    fun onClock(ticker: String, nowMs: Long) {
        if (!engine.hasWork(ticker)) return
        val grid = grids[ticker] ?: return
        ledger.apply(clock(ticker, grid, nowMs))
    }

    /** The window settled: open scalps pay $1 or $0 and the window's grid is dropped. */
    @Synchronized
    fun settle(ticker: String, result: String, nowMs: Long = System.currentTimeMillis()) {
        val events = engine.settle(ticker, result, nowMs)
        grids.remove(ticker)
        lastDecision.remove(ticker)
        ledger.apply(events, forcePersist = events.isNotEmpty())
    }

    @Synchronized
    fun openTickers(): Set<String> = engine.openTickers()

    @Synchronized
    fun working(): Pair<Int, Int> = engine.workingTotal()

    @Synchronized
    fun reset() {
        engine.clear()
        ledger.reset()
        onReset()
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
            if (nowMs - e.value.openMs > PrintGrid.SECONDS * 1000L + GRID_KEEP_MS && !engine.hasWork(e.key)) it.remove()
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
        if (s < PrintGrid.MIN_DECISION_SECOND || s > PrintGrid.MAX_DECISION_SECOND) return emptyList()
        // Slot 0 is the last second looked at (dozens of prints share a second); then one slot per strategy.
        val last = lastDecision.getOrPut(ticker) { IntArray(STRATEGIES.size + 1) { -1_000 } }
        if (last[0] == s) return emptyList()
        last[0] = s
        val due = STRATEGIES.filter { s - last[it.ordinal + 1] >= it.cadenceSeconds }
        if (due.isEmpty()) return emptyList()
        for (st in due) last[st.ordinal + 1] = s
        if (!grid.warm(s)) return emptyList()
        val events = ArrayList<ScalpEvent>()
        val top = topOfBook(ticker)
        for (side in SIDES) {
            if (!grid.quotable(side, s)) continue
            val yes = side == "YES"
            val realBid = (if (yes) top?.yesBid else top?.noBid)?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
            val realAsk = (if (yes) top?.yesAsk else top?.noAsk)?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
            val realQty = (if (yes) top?.yesBidQty else top?.noBidQty)?.takeIf { it.isFinite() && it >= 0.0 }
            val bookOk = realBid != null && realAsk != null && realBid < realAsk
            val bid = if (bookOk) realBid!! else grid.bid(side, s)
            val ask = if (bookOk) realAsk!! else grid.ask(side, s)
            val queue = if (bookOk) realQty else null
            val d10 = grid.move(side, s, 10)
            val d30 = grid.move(side, s, 30)
            val askQty = if (bookOk) (if (yes) top?.yesAskQty else top?.noAskQty)?.takeIf { it.isFinite() && it >= 0.0 } else null
            // What this order saw, for the order log only.
            fun seen(x: FloatArray?) = ScalpContext(
                bid = bid, ask = ask, bidQty = queue, askQty = askQty,
                move10 = d10.takeIf { !it.isNaN() }, move30 = d30.takeIf { !it.isNaN() }, features = x
            )
            for (st in due) {
                val fire = when (st) {
                    ScalpStrategy.ML_REST -> null
                    ScalpStrategy.DIP_HUNTER, ScalpStrategy.DIP_HUNTER_REST ->
                        !d10.isNaN() && d10 <= -ScalpStrategy.FAST_MOVE_DOLLARS + EPS
                    ScalpStrategy.MOMENTUM_SNIPER, ScalpStrategy.MOMENTUM_SNIPER_REST ->
                        !d10.isNaN() && d10 >= ScalpStrategy.FAST_MOVE_DOLLARS - EPS
                    ScalpStrategy.EXTREME_REVERSION, ScalpStrategy.EXTREME_REVERSION_REST ->
                        !d30.isNaN() && d30 <= -ScalpStrategy.EXTREME_MOVE_DOLLARS + EPS
                }
                if (fire == null) {
                    val m = model ?: continue
                    val x = grid.features(side, s) ?: continue
                    val pred = m.predict(x)
                    if (pred < m.thetaCents) continue
                    val expected = m.expectedCents(pred, queue ?: engine.config.unknownQueue)
                    engine.post(st, ticker, side, bid, queue, pred, expected, nowMs, seen(x))?.let { events += it }
                } else if (fire) {
                    if (st.taker) {
                        events += engine.buyNow(
                            st, ticker, side, ask, { sd, px -> offerQueue(ticker, sd, px) }, nowMs, seen(null)
                        )
                    } else {
                        engine.post(st, ticker, side, bid, queue, 0.0, 0.0, nowMs, seen(null))?.let { events += it }
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
        private val STRATEGIES = ScalpStrategy.values().toList()
        private const val EPS = 1e-9
        private const val GRID_KEEP_MS = 120_000L
    }
}

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

    /** The trained model from assets, or null when it cannot be read (the fast strategies still run). */
    val model: ScalperModel? = runCatching {
        context.assets.open(ScalperModel.ASSET_NAME).bufferedReader().use { ScalperModel.parse(it.readText()) }
    }.getOrNull()

    private fun load(): ScalperState {
        val raw = prefs.getString(KEY, null) ?: return ScalperState.fresh()
        val saved = runCatching { json.decodeFromString(ScalperState.serializer(), raw) }.getOrNull()
        // A 1.8.4 record has no won / lost / fee totals and no trade file: start a new one.
        return saved?.takeIf { it.version >= ScalperState.VERSION } ?: ScalperState.fresh()
    }

    private fun save(state: ScalperState) {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(ScalperState.serializer(), state)).apply() }
    }

    private companion object {
        const val PREFS = "bitcoin_claude_scalper"
        const val KEY = "state_json"
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
        const val TITLE = "Paper scalper · aggressive · PAPER ONLY"
        const val NOTE =
            "Seven strategies at once on real trade prints, 10 contracts each, no caps. Resting orders fill only " +
                "after the queue ahead has traded; buy-now entries and stop / time-out exits pay the taker fee. " +
                "History in brackets is cents per contract over 22 days (back of the queue to front). " +
                "Needs Live signals on. Never sends an order."

        fun of(state: ScalperState, working: Pair<Int, Int> = 0 to 0): ScalperSummary {
            val all = state.stats(ScalperState.ALL)
            val total = "All strategies: ${money(all.pnlUsd)} · ${all.closed} scalps" + pct(all)
            val strategies = ScalpStrategy.values()
                .map { it to state.stats(it.name) }
                // Strategies that have traded first, best P&L on top; idle ones after, in their own order.
                .sortedWith(
                    compareByDescending<Pair<ScalpStrategy, ScalpStats>> { it.second.closed > 0 }
                        .thenByDescending { it.second.pnlUsd }
                        .thenBy { it.first.ordinal }
                )
                .map { (st, s) ->
                    val hist = String.format(Locale.US, " [%+.2f to %+.2f¢]", st.historyCents.first, st.historyCents.second)
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
                listOf("ML scalper, only orders expected to pay where they sat in the queue: ${money(aware.pnlUsd)} · ${aware.closed}${pct(aware)}") +
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
