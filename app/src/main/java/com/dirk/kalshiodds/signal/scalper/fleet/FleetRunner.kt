package com.dirk.kalshiodds.signal.scalper.fleet

import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.scalper.PrintGrid
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalperEngine

/** The index side of one window, from the settlement index (BRTI). */
data class IndexView(
    val spot: Double,
    val strike: Double?,
    val sigma1sLog: Double?,
    /** One value a second over the last 10 minutes, newest last, NaN gaps. */
    val index1s: DoubleArray,
    /** One value a second inside the final minute so far. */
    val finalPrints: DoubleArray
)

/** Everything the fleet needs from outside the paper scalper. The app's [FleetHub]; the replay passes its own. */
interface FleetFeed {
    fun index(ticker: String, closeMs: Long, nowMs: Long): IndexView?
    fun external(ticker: String, closeMs: Long, nowMs: Long): FleetExternal
    /** ε(P) for YES quoted at [yesPrice] (scalper 13), or null with no table. */
    fun behavioural(yesPrice: Double): Double? = null
    /** A leg on another market opened by [strategy] (held to its settlement there). */
    fun openOffBook(strategy: ScalpStrategy, leg: OffBookLeg, note: String, nowMs: Long) {}
    /** Every view the fleet decided on (the behavioural table learns from them). */
    fun onView(view: FleetView) {}
    /** A Bitcoin window settled. */
    fun onSettle(ticker: String, result: String) {}
}

/**
 * Per-ticker state the fleet keeps inside the paper scalper: VPIN volume buckets and the time between prints.
 * Not thread-safe; the paper scalper holds its lock.
 */
class FleetTape {
    private class Tape {
        val buckets = ArrayDeque<Pair<Double, Double>>()
        var curYes = 0.0
        var curNo = 0.0
        val printTimes = ArrayDeque<Long>()
    }

    private val tapes = HashMap<String, Tape>()

    fun onTrade(ticker: String, takerYes: Boolean, contracts: Double, nowMs: Long) {
        val t = tapes.getOrPut(ticker) { Tape() }
        var left = contracts
        while (left > 0) {
            val room = BUCKET - (t.curYes + t.curNo)
            val put = minOf(room, left)
            if (takerYes) t.curYes += put else t.curNo += put
            left -= put
            if (t.curYes + t.curNo >= BUCKET - 1e-9) {
                t.buckets.addLast(t.curYes to t.curNo)
                while (t.buckets.size > 50) t.buckets.removeFirst()
                t.curYes = 0.0
                t.curNo = 0.0
            }
        }
        t.printTimes.addLast(nowMs)
        while (t.printTimes.isNotEmpty() && t.printTimes.first() < nowMs - 60_000L) t.printTimes.removeFirst()
    }

    fun vpin(ticker: String): Double? = tapes[ticker]?.buckets?.let { FleetMath.vpin(it.toList()) }

    /** Taker YES share of the last three buckets. */
    fun recentYesShare(ticker: String): Double? {
        val b = tapes[ticker]?.buckets ?: return null
        if (b.size < 3) return null
        val last = b.toList().takeLast(3)
        val y = last.sumOf { it.first }
        val n = last.sumOf { it.second }
        return if (y + n <= 0) null else y / (y + n)
    }

    fun tradeGap(ticker: String): Double? {
        val p = tapes[ticker]?.printTimes ?: return null
        if (p.size < 3) return null
        return (p.last() - p.first()) / 1000.0 / (p.size - 1)
    }

    fun forget(ticker: String) {
        tapes.remove(ticker)
    }

    companion object {
        /** Contracts per VPIN volume bucket. */
        const val BUCKET = 500.0
    }
}

/** Builds the [FleetView] for one decision second. */
object FleetViews {
    fun build(
        ticker: String,
        nowMs: Long,
        closeMs: Long,
        s: Int,
        grid: PrintGrid,
        top: TopOfBook?,
        levels: BookLevelSnapshot?,
        fairYes: Double?,
        tape: FleetTape,
        engine: ScalperEngine,
        feed: FleetFeed
    ): FleetView {
        val idx = runCatching { feed.index(ticker, closeMs, nowMs) }.getOrNull()
        val mids = DoubleArray(120) { i -> val at = s - 119 + i; if (at < 0) Double.NaN else grid.yesMid(at) }
        val yesLv = levels?.yes.orEmpty().filter { it.second > 0 }.sortedByDescending { it.first }
        val noLv = levels?.no.orEmpty().filter { it.second > 0 }.sortedByDescending { it.first }
        val gridBid = grid.yesBid(s).takeIf { !it.isNaN() }
        val gridAsk = grid.yesAsk(s).takeIf { !it.isNaN() }
        val yb = top?.yesBid ?: gridBid
        val ya = top?.yesAsk ?: gridAsk
        val nb = top?.noBid ?: ya?.let { 1.0 - it }
        val na = top?.noAsk ?: yb?.let { 1.0 - it }
        return FleetView(
            ticker = ticker, nowMs = nowMs, s = s, tte = (closeMs - nowMs) / 1000.0,
            strike = idx?.strike, spot = idx?.spot, sigma1sLog = idx?.sigma1sLog,
            index1s = idx?.index1s ?: DoubleArray(0), finalPrints = idx?.finalPrints ?: DoubleArray(0),
            yesBid = yb, yesAsk = ya, noBid = nb, noAsk = na,
            yesBidQty = top?.yesBidQty, yesAskQty = top?.yesAskQty, noBidQty = top?.noBidQty, noAskQty = top?.noAskQty,
            yesLevels = yesLv, noLevels = noLv, yesMids = mids,
            tradeGap = tape.tradeGap(ticker), fairYes = fairYes,
            vpin = tape.vpin(ticker), recentYesShare = tape.recentYesShare(ticker),
            behavioural = { p -> feed.behavioural(p) },
            inventory = { st -> engine.inventory(st, ticker) },
            restingAt = { st, side -> engine.restingPrices(st, ticker, side) },
            ext = runCatching { feed.external(ticker, closeMs, nowMs) }.getOrDefault(FleetExternal())
        )
    }
}

/** A [FleetFeed] built on first use (the app makes its parts after the paper scalper). */
class LazyFleetFeed(provider: () -> FleetFeed) : FleetFeed {
    private val feed by lazy(provider)
    override fun index(ticker: String, closeMs: Long, nowMs: Long) = feed.index(ticker, closeMs, nowMs)
    override fun external(ticker: String, closeMs: Long, nowMs: Long) = feed.external(ticker, closeMs, nowMs)
    override fun behavioural(yesPrice: Double) = feed.behavioural(yesPrice)
    override fun openOffBook(strategy: ScalpStrategy, leg: OffBookLeg, note: String, nowMs: Long) = feed.openOffBook(strategy, leg, note, nowMs)
    override fun onView(view: FleetView) = feed.onView(view)
    override fun onSettle(ticker: String, result: String) = feed.onSettle(ticker, result)
}
