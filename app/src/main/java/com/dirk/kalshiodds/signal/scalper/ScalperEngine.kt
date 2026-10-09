package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.abs

/** How many contracts were ahead of a paper bid when it was posted. */
enum class QueueBucket(val label: String) {
    FRONT("0–100 ahead"),
    NEAR("101–250 ahead"),
    MID("251–1,000 ahead"),
    DEEP("over 1,000 ahead"),
    UNKNOWN("queue not shown");

    companion object {
        fun of(contractsAhead: Double, known: Boolean): QueueBucket = when {
            !known -> UNKNOWN
            contractsAhead <= 100.0 -> FRONT
            contractsAhead <= 250.0 -> NEAR
            contractsAhead <= 1_000.0 -> MID
            else -> DEEP
        }
    }
}

enum class ExitKind { TARGET, STOP, TIMEOUT, SETTLED }

/**
 * The paper scalper's strategies. They all run at once, each with its own
 * positions and its own line on the scoreboard.
 *
 * Every scalp exits the same way: a resting offer [targetDollars] above the
 * entry (no fee), or a sale at the bid with the taker fee when the bid falls
 * [stopDollars] below the entry or [timeoutMs] passes.
 *
 * [taker] strategies buy at the ask the moment the signal fires and pay the
 * fee; the others rest a bid and wait up to 20 s for it to fill.
 *
 * [historyCents] is what one scalp made on 1,992 settled windows
 * (2026-09-18 → 10-09, `tools/research/scalp/strat_bt.py`), offer filled at
 * the back of the queue / at the front. A fact about the past, shown on the
 * card; it gates nothing.
 */
enum class ScalpStrategy(
    val label: String,
    val taker: Boolean,
    val targetDollars: Double,
    val stopDollars: Double,
    val timeoutMs: Long,
    /** Seconds between decisions per window. */
    val cadenceSeconds: Int,
    /** Positions this strategy may have working at once, per side, per window. */
    val maxPerSide: Int,
    val historyCents: Pair<Double, Double>
) {
    /** The trained model picks calm moments. Rest in, +1¢ out. */
    ML_REST("ML scalper · resting", false, 0.01, 0.04, 120_000L, 5, 12, -2.02 to 0.35),

    /** The side's price fell 2¢ or more in 10 s: buy the dip now. */
    DIP_HUNTER("Dip-hunter · buy now", true, 0.02, 0.02, 30_000L, 2, 6, -3.68 to -3.27),

    /** The side's price rose 2¢ or more in 10 s: buy the move now. */
    MOMENTUM_SNIPER("Momentum-sniper · buy now", true, 0.02, 0.02, 30_000L, 2, 6, -3.49 to -3.07),

    /** The side's price fell 6¢ or more in 30 s: buy the snap-back now. */
    EXTREME_REVERSION("Extreme reversion · buy now", true, 0.03, 0.03, 60_000L, 2, 6, -3.67 to -3.26),

    /** Same three signals with a resting bid instead of paying the ask. */
    DIP_HUNTER_REST("Dip-hunter · resting", false, 0.01, 0.04, 30_000L, 2, 6, -2.25 to 0.00),
    MOMENTUM_SNIPER_REST("Momentum-sniper · resting", false, 0.01, 0.04, 30_000L, 2, 6, -2.13 to 0.06),
    EXTREME_REVERSION_REST("Extreme reversion · resting", false, 0.01, 0.04, 30_000L, 2, 6, -2.25 to -0.01);

    companion object {
        /** Price move over 10 s that the dip-hunter and the momentum-sniper react to. */
        const val FAST_MOVE_DOLLARS = 0.02

        /** Price fall over 30 s that extreme reversion reacts to. */
        const val EXTREME_MOVE_DOLLARS = 0.06
    }
}

/** One paper scalp order as posted. Never a real order. */
data class ScalpOrder(
    val id: Long,
    val strategy: ScalpStrategy,
    val ticker: String,
    /** "YES" (UP) or "NO" (DOWN). */
    val side: String,
    /** Entry price in that side's dollars: the resting bid, or the ask paid by a buy-now strategy. */
    val price: Double,
    val contracts: Int,
    /** Taker fee paid on a buy-now entry; 0 for a resting bid. */
    val entryFeeUsd: Double = 0.0,
    /** Contracts shown ahead of the bid when it was posted (the assumed size when the book does not show it). */
    val queueAhead: Double,
    val queueKnown: Boolean,
    val postedAtMs: Long,
    /** Model output: expected cents per contract from the front of the queue. */
    val predictionCents: Double,
    /** [predictionCents] minus the queue penalty: what this order is expected to make where it sits. */
    val expectedCents: Double
) {
    /** Queue bucket of a resting bid; buy-now entries have no entry queue. */
    val bucket: QueueBucket? get() = if (strategy.taker) null else QueueBucket.of(queueAhead, queueKnown)

    /** ML scalper only: true when the order is expected to pay after its place in the queue is counted. */
    val queueAware: Boolean get() = strategy == ScalpStrategy.ML_REST && queueKnown && expectedCents >= 0.0
}

data class ClosedScalp(
    val order: ScalpOrder,
    val exitPrice: Double,
    val kind: ExitKind,
    /** Exit fee (a sale at the bid); the entry fee is on the order. */
    val feeUsd: Double,
    /** Dollars for the whole order: (exit − entry) × contracts − both fees. */
    val pnlUsd: Double,
    val filledAtMs: Long,
    val closedAtMs: Long
)

sealed class ScalpEvent {
    data class Posted(val order: ScalpOrder) : ScalpEvent()
    data class Unfilled(val order: ScalpOrder) : ScalpEvent()
    data class Filled(val order: ScalpOrder, val atMs: Long) : ScalpEvent()
    data class Closed(val scalp: ClosedScalp) : ScalpEvent()
}

/**
 * Paper scalps, both legs resting, filled only by real public trades.
 *
 * One scalp (docs/scalping-2026-10-09.md; the ML scalper's numbers are the
 * ones its model was trained and tested on):
 *
 *  1. Entry: rest a bid at the best bid for up to [Config.entryWaitMs], or,
 *     for a buy-now strategy, pay the ask and the taker fee at once.
 *  2. Rest an offer [ScalpStrategy.targetDollars] above the entry.
 *  3. If the side's bid falls [ScalpStrategy.stopDollars] below the entry,
 *     or [ScalpStrategy.timeoutMs] passes, sell at the bid and pay the fee.
 *  4. Anything still open when the window closes settles at $1 or $0.
 *
 * Fills are conservative: a resting order fills when a print trades
 * **through** its price, or when the contracts ahead of it **plus its own
 * size** have traded at its price since it was posted. Nobody ahead cancels.
 * Resting fills pay no fee (KXBTC15M maker fee is 0).
 *
 * Aggressive by design: every [ScalpStrategy] runs at once on both sides,
 * each holding up to [ScalpStrategy.maxPerSide] positions per side, with no
 * cap on scalps per window, no daily cap and no queue check before posting;
 * the ledger splits the results by strategy and by queue instead. Pure and deterministic; not thread-safe (the
 * [PaperScalper] holds the lock). There is no path from here to an order.
 */
class ScalperEngine(val config: Config = Config()) {

    data class Config(
        val contracts: Int = 10,
        val entryWaitMs: Long = 20_000L,
        /** Queue assumed when the book shows no size (same figure the flow-fade tracker uses). */
        val unknownQueue: Double = 3_500.0,
        val feeRate: Double = KalshiFee.TAKER_COEFFICIENT,
        val minPrice: Double = PrintGrid.MIN_PRICE,
        val maxPrice: Double = PrintGrid.MAX_PRICE
    )

    private class Working(val order: ScalpOrder) {
        var traded = 0.0
        var filledAtMs: Long? = null
        var exitPrice = 0.0
        var exitQueue = 0.0
        var exitTraded = 0.0
        val resting: Boolean get() = filledAtMs == null
    }

    private val byTicker = HashMap<String, MutableList<Working>>()
    private var nextId = 1L

    /** (resting bids, resting offers) on [ticker]. */
    fun working(ticker: String): Pair<Int, Int> {
        val list = byTicker[ticker] ?: return 0 to 0
        val bids = list.count { it.resting }
        return bids to (list.size - bids)
    }

    fun workingTotal(): Pair<Int, Int> {
        var bids = 0
        var offers = 0
        for (list in byTicker.values) for (w in list) if (w.resting) bids++ else offers++
        return bids to offers
    }

    /** Tickers that hold a filled scalp waiting to exit (they need a settlement if the window ends). */
    fun openTickers(): Set<String> =
        byTicker.filterValues { list -> list.any { !it.resting } }.keys.toSet()

    fun hasWork(ticker: String): Boolean = !byTicker[ticker].isNullOrEmpty()

    /** Positions [strategy] has working on one side of [ticker] (resting bids and filled scalps). */
    fun workingFor(strategy: ScalpStrategy, ticker: String, side: String): Int {
        val want = if (PrintGrid.isYes(side)) "YES" else "NO"
        return byTicker[ticker]?.count { it.order.strategy == strategy && it.order.side == want } ?: 0
    }

    /**
     * Rest a paper bid for a resting [strategy]. Null when the price is
     * outside 10–90¢ or the strategy already has its limit working on that
     * side. [queueAhead] null = the book did not show a size.
     */
    fun post(
        strategy: ScalpStrategy,
        ticker: String,
        side: String,
        price: Double,
        queueAhead: Double?,
        predictionCents: Double,
        expectedCents: Double,
        nowMs: Long
    ): ScalpEvent.Posted? {
        require(!strategy.taker) { "${strategy.name} buys at the ask: use buyNow" }
        if (!price.isFinite() || price < config.minPrice - EPS || price > config.maxPrice + EPS) return null
        val want = if (PrintGrid.isYes(side)) "YES" else "NO"
        if (workingFor(strategy, ticker, want) >= strategy.maxPerSide) return null
        val known = queueAhead != null && queueAhead.isFinite() && queueAhead >= 0.0
        val order = ScalpOrder(
            id = nextId++,
            strategy = strategy,
            ticker = ticker,
            side = want,
            price = round4(price),
            contracts = config.contracts,
            queueAhead = if (known) queueAhead!! else config.unknownQueue,
            queueKnown = known,
            postedAtMs = nowMs,
            predictionCents = predictionCents,
            expectedCents = expectedCents
        )
        byTicker.getOrPut(ticker) { ArrayList() }.add(Working(order))
        return ScalpEvent.Posted(order)
    }

    /**
     * A buy-now [strategy] pays [askPrice] plus the taker fee and at once
     * rests its offer. [offerQueue] = contracts already offered at the
     * target price (null when the book does not show it). Empty when the
     * price is outside 10–90¢ or the strategy is at its limit on that side.
     */
    fun buyNow(
        strategy: ScalpStrategy,
        ticker: String,
        side: String,
        askPrice: Double,
        offerQueue: (side: String, price: Double) -> Double?,
        nowMs: Long
    ): List<ScalpEvent> {
        require(strategy.taker) { "${strategy.name} rests a bid: use post" }
        if (!askPrice.isFinite() || askPrice < config.minPrice - EPS || askPrice > config.maxPrice + EPS) return emptyList()
        val want = if (PrintGrid.isYes(side)) "YES" else "NO"
        if (workingFor(strategy, ticker, want) >= strategy.maxPerSide) return emptyList()
        val price = round4(askPrice)
        val order = ScalpOrder(
            id = nextId++,
            strategy = strategy,
            ticker = ticker,
            side = want,
            price = price,
            contracts = config.contracts,
            entryFeeUsd = KalshiFee.total(config.contracts, price, config.feeRate),
            queueAhead = 0.0,
            queueKnown = true,
            postedAtMs = nowMs,
            predictionCents = 0.0,
            expectedCents = 0.0
        )
        val w = Working(order)
        w.filledAtMs = nowMs
        w.exitPrice = round4(price + strategy.targetDollars)
        w.exitQueue = offerQueue(want, w.exitPrice)?.takeIf { q -> q.isFinite() && q >= 0.0 } ?: config.unknownQueue
        byTicker.getOrPut(ticker) { ArrayList() }.add(w)
        return listOf(ScalpEvent.Posted(order), ScalpEvent.Filled(order, nowMs))
    }

    /**
     * A public trade on [ticker]: [takerYes] = the taker bought YES at
     * [yesPrice]. [exitQueue] returns the contracts already offered at a
     * side's price (null when the book does not show it); it is asked once,
     * when an entry fills.
     */
    fun onTrade(
        ticker: String,
        takerYes: Boolean,
        yesPrice: Double,
        contracts: Double,
        nowMs: Long,
        exitQueue: (side: String, price: Double) -> Double?
    ): List<ScalpEvent> {
        val list = byTicker[ticker] ?: return emptyList()
        if (list.isEmpty() || !yesPrice.isFinite() || !contracts.isFinite() || contracts <= 0.0) return emptyList()
        val events = ArrayList<ScalpEvent>(2)
        val it = list.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val o = w.order
            val yes = o.side == "YES"
            // The print's price in this order's own side.
            val sidePrice = if (yes) yesPrice else 1.0 - yesPrice
            if (w.resting) {
                if (nowMs - o.postedAtMs > config.entryWaitMs) {
                    it.remove()
                    events += ScalpEvent.Unfilled(o)
                    continue
                }
                // Our bid is hit by takers selling our side, i.e. buying the other one.
                if (takerYes == yes) continue
                val through = sidePrice < o.price - TICK_EPS
                if (abs(sidePrice - o.price) <= TICK_EPS) w.traded += contracts
                if (through || w.traded >= o.queueAhead + o.contracts) {
                    w.filledAtMs = nowMs
                    w.exitPrice = round4(o.price + o.strategy.targetDollars)
                    val shown = if (o.queueKnown) exitQueue(o.side, w.exitPrice) else null
                    w.exitQueue = shown?.takeIf { q -> q.isFinite() && q >= 0.0 } ?: config.unknownQueue
                    events += ScalpEvent.Filled(o, nowMs)
                }
            } else {
                // Our offer is lifted by takers buying our side.
                if (takerYes != yes) continue
                val through = sidePrice > w.exitPrice + TICK_EPS
                if (abs(sidePrice - w.exitPrice) <= TICK_EPS) w.exitTraded += contracts
                if (through || w.exitTraded >= w.exitQueue + o.contracts) {
                    it.remove()
                    events += ScalpEvent.Closed(close(w, w.exitPrice, ExitKind.TARGET, feeUsd = 0.0, nowMs = nowMs))
                }
            }
        }
        return events
    }

    /**
     * Time and price checks: expire unfilled bids, stop out or time out
     * filled scalps at the side's current bid ([bidYes] / [bidNo]; null =
     * no bid to sell into, so the scalp waits).
     */
    fun onClock(ticker: String, nowMs: Long, bidYes: Double?, bidNo: Double?): List<ScalpEvent> {
        val list = byTicker[ticker] ?: return emptyList()
        if (list.isEmpty()) return emptyList()
        val events = ArrayList<ScalpEvent>(2)
        val it = list.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val o = w.order
            val filledAt = w.filledAtMs
            if (filledAt == null) {
                if (nowMs - o.postedAtMs > config.entryWaitMs) {
                    it.remove()
                    events += ScalpEvent.Unfilled(o)
                }
                continue
            }
            val bid = (if (o.side == "YES") bidYes else bidNo)?.takeIf { b -> b.isFinite() && b >= 0.0 && b < 1.0 }
                ?: continue
            val stopped = bid <= o.price - o.strategy.stopDollars + EPS
            val timedOut = nowMs - filledAt >= o.strategy.timeoutMs
            if (stopped || timedOut) {
                it.remove()
                val fee = if (bid > 0.0) KalshiFee.total(o.contracts, bid, config.feeRate) else 0.0
                events += ScalpEvent.Closed(
                    close(w, bid, if (stopped) ExitKind.STOP else ExitKind.TIMEOUT, feeUsd = fee, nowMs = nowMs)
                )
            }
        }
        return events
    }

    /** The window settled: filled scalps pay $1 or $0, unfilled bids are dropped. "void" returns the stake. */
    fun settle(ticker: String, result: String, nowMs: Long): List<ScalpEvent> {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
        val list = byTicker.remove(ticker) ?: return emptyList()
        val events = ArrayList<ScalpEvent>(list.size)
        for (w in list) {
            val o = w.order
            if (w.resting) {
                events += ScalpEvent.Unfilled(o)
                continue
            }
            if (outcome == "void") {
                // A void refunds the stake and the fee: nothing won, nothing lost.
                events += ScalpEvent.Closed(
                    ClosedScalp(o, o.price, ExitKind.SETTLED, 0.0, 0.0, w.filledAtMs ?: nowMs, nowMs)
                )
                continue
            }
            val value = if ((outcome == "yes") == (o.side == "YES")) 1.0 else 0.0
            events += ScalpEvent.Closed(close(w, value, ExitKind.SETTLED, feeUsd = 0.0, nowMs = nowMs))
        }
        return events
    }

    /** Drop everything on tickers that are no longer watched (nothing is booked for them). */
    fun retain(tickers: Set<String>) {
        byTicker.keys.retainAll(tickers)
    }

    fun clear() {
        byTicker.clear()
    }

    private fun close(w: Working, exitPrice: Double, kind: ExitKind, feeUsd: Double, nowMs: Long): ClosedScalp {
        val o = w.order
        return ClosedScalp(
            order = o,
            exitPrice = exitPrice,
            kind = kind,
            feeUsd = feeUsd,
            pnlUsd = (exitPrice - o.price) * o.contracts - o.entryFeeUsd - feeUsd,
            filledAtMs = w.filledAtMs ?: nowMs,
            closedAtMs = nowMs
        )
    }

    companion object {
        /** Kalshi quotes some levels in tenths of a cent. */
        const val TICK_EPS = 5e-4
        private const val EPS = 1e-9

        fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
    }
}
