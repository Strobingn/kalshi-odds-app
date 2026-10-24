package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * A paper order that takes liquidity fills only against what the order book shows (1.20.4). A buy walks the offers
 * from the cheapest up, at most [MAX_SLIP] above the best ask, and fills no more than is offered there; a sale walks
 * the bids down the same way, and whatever is left past the bids shown is sold at the walk's floor. Every piece pays
 * Kalshi's taker fee at its own price. Pure.
 */
object BookFill {
    /** Furthest a paper taker order walks the book past the best price. */
    const val MAX_SLIP = 0.05
    private const val EPS = 1e-9

    data class Fill(val contracts: Int, val avgPrice: Double, val feeUsd: Double)

    /**
     * Buy up to [want] contracts, best offer first, at no more than [limit]. [levels] are (price, contracts offered) in
     * the side's own dollars. Null when nothing is offered at or under [limit].
     */
    fun buy(levels: List<Pair<Double, Double>>, want: Int, feeRate: Double, limit: Double): Fill? {
        if (want <= 0) return null
        var left = want
        var cost = 0.0
        var fee = 0.0
        for ((p, q) in levels.filter { it.second > 0 && it.first > 0.0 && it.first < 1.0 }.sortedBy { it.first }) {
            if (p > limit + EPS || left <= 0) break
            val take = min(left, floor(q + EPS).toInt())
            if (take <= 0) continue
            cost += take * p
            fee += KalshiFee.total(take, p, feeRate)
            left -= take
        }
        val got = want - left
        return if (got <= 0) null else Fill(got, cost / got, fee)
    }

    /**
     * Sell [have] contracts into the bids, best first, down to [floorPrice]; what the bids shown cannot take is sold
     * at [floorPrice] (a real order would have to go that far, or further, to get out). Never null.
     */
    fun sell(levels: List<Pair<Double, Double>>, have: Int, feeRate: Double, floorPrice: Double): Fill {
        val floorP = floorPrice.coerceIn(0.0, 1.0)
        if (have <= 0) return Fill(0, floorP, 0.0)
        var left = have
        var value = 0.0
        var fee = 0.0
        for ((p, q) in levels.filter { it.second > 0 && it.first > 0.0 && it.first < 1.0 }.sortedByDescending { it.first }) {
            if (p < floorP - EPS || left <= 0) break
            val take = min(left, floor(q + EPS).toInt())
            if (take <= 0) continue
            value += take * p
            fee += KalshiFee.total(take, p, feeRate)
            left -= take
        }
        if (left > 0) {
            value += left * floorP
            if (floorP > 0.0) fee += KalshiFee.total(left, floorP, feeRate)
        }
        return Fill(have, value / have, fee)
    }

    /**
     * The offers on [side] ("YES"/"NO") from [book], cheapest first: a YES offer at `a` is a NO bid at `1 − a`.
     * Null without a book, or when its best offer is more than [maxGap] from [top] (a book that has drifted from the
     * live quote is not trusted).
     */
    fun askLevels(book: BookLevelSnapshot?, side: String, top: Double, maxGap: Double = 0.02): List<Pair<Double, Double>>? {
        val bids = (if (PrintGrid.isYes(side)) book?.no else book?.yes) ?: return null
        val out = bids.filter { it.second > 0 }.map { (1.0 - it.first) to it.second }.sortedBy { it.first }
        val best = out.firstOrNull()?.first ?: return null
        return if (abs(best - top) <= maxGap + EPS) out else null
    }

    /** The bids on [side], best first; null without a book or when its best bid is more than [maxGap] from [top]. */
    fun bidLevels(book: BookLevelSnapshot?, side: String, top: Double, maxGap: Double = 0.02): List<Pair<Double, Double>>? {
        val bids = (if (PrintGrid.isYes(side)) book?.yes else book?.no) ?: return null
        val out = bids.filter { it.second > 0 }.sortedByDescending { it.first }
        val best = out.firstOrNull()?.first ?: return null
        return if (abs(best - top) <= maxGap + EPS) out else null
    }
}
