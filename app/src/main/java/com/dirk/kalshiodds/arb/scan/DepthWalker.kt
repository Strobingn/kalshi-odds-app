package com.dirk.kalshiodds.arb.scan

/**
 * Prices a [Structure] against real order-book depth.
 *
 * Buying q sets means buying q contracts of every leg, each leg as one taker
 * order that sweeps its ask ladder cheapest-first. Profit(q) =
 * q × payout − Σ legs debit(q) with Kalshi's fee ([ArbFee]).
 *
 * Between book-level boundaries every leg's marginal price is constant, so
 * profit is linear there (up to cent rounding) and its maximum sits on a
 * boundary. We evaluate every q up to [EXHAUSTIVE_Q], plus each boundary and
 * boundary+1 beyond that, and keep the q with the largest locked profit.
 */
object DepthWalker {

    const val EXHAUSTIVE_Q = 300L
    /** Ignore absurd depth so the arithmetic stays well inside Long. */
    const val MAX_SETS = 5_000_000L

    /** Contracts taken from [asks] (cheapest first) to fill [qty]; null if the book is too thin. */
    fun take(asks: List<Level>, qty: Long): List<Level>? {
        if (qty <= 0L) return emptyList()
        var left = qty
        val out = ArrayList<Level>()
        for (lv in asks) {
            if (left <= 0L) break
            val q = minOf(left, lv.qty)
            if (q > 0L) {
                out += Level(lv.priceE4, q)
                left -= q
            }
        }
        return if (left > 0L) null else out
    }

    fun evaluate(structure: Structure, books: Map<String, MarketBook>): Opportunity? {
        val ladders = structure.legs.map { leg ->
            val b = books[leg.ticker] ?: return null
            b.asks(leg.side).filter { it.qty > 0 && it.priceE4 in 1..9_999 }.sortedBy { it.priceE4 }
        }
        if (ladders.isEmpty() || ladders.any { it.isEmpty() }) return null
        val depth = ladders.minOf { l -> l.sumOf { it.qty } }.coerceAtMost(MAX_SETS)
        if (depth <= 0L) return null

        val candidates = sortedSetOf<Long>()
        for (q in 1L..minOf(depth, EXHAUSTIVE_Q)) candidates += q
        for (l in ladders) {
            var cum = 0L
            for (lv in l) {
                cum += lv.qty
                if (cum > depth) break
                candidates += cum
                if (cum + 1 <= depth) candidates += cum + 1
            }
        }
        candidates += depth

        val payout = structure.payoutPerSetCents
        var bestProfitQ = -1L
        var bestProfit = Long.MIN_VALUE
        var bestShortQ = -1L
        var bestShort = Double.POSITIVE_INFINITY
        for (q in candidates) {
            var debit = 0L
            for (l in ladders) debit += ArbFee.debitCents(take(l, q) ?: return null)
            val profit = q * payout - debit
            // Ties go to the larger size: same locked profit, more sets available.
            if (profit >= bestProfit) {
                bestProfit = profit
                bestProfitQ = q
            }
            val short = (debit - q * payout).toDouble() / q
            if (short < bestShort) {
                bestShort = short
                bestShortQ = q
            }
        }
        val q = if (bestProfit > 0) bestProfitQ else bestShortQ
        return build(structure, ladders, q, bestShort)
    }

    private fun build(s: Structure, ladders: List<List<Level>>, q: Long, shortfall: Double): Opportunity {
        val fills = s.legs.mapIndexed { i, spec ->
            val taken = take(ladders[i], q)!!
            val contracts = taken.sumOf { it.qty }
            val notional = taken.sumOf { it.qty * it.priceE4 }
            LegFill(
                spec = spec,
                qty = contracts,
                avgPriceE4 = notional.toDouble() / contracts,
                worstPriceE4 = taken.maxOf { it.priceE4 },
                debitCents = ArbFee.debitCents(taken),
                feeE4 = ArbFee.feeE4(taken)
            )
        }
        val cost = fills.sumOf { it.debitCents }
        val payout = q * s.payoutPerSetCents
        return Opportunity(
            structure = s,
            sets = q,
            legs = fills,
            costCents = cost,
            feeE4 = fills.sumOf { it.feeE4 },
            payoutCents = payout,
            profitCents = payout - cost,
            shortfallPerSetCents = shortfall,
            rawTopCostE4 = ladders.sumOf { it.first().priceE4.toLong() }
        )
    }
}
