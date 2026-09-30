package com.dirk.kalshiarb.scan

/**
 * Pure scan pipeline:
 *  1. [plan]: every [Structure] in the listed events whose top-of-book cost
 *     *before fees* is already within [NEAR_MISS_CENTS] of its payout.
 *     Fees are never negative, so nothing outside this cut can be an
 *     opportunity or a near miss.
 *  2. The caller fetches order books for the planned tickers (budgeted, in
 *     plan order).
 *  3. [evaluate]: fee-inclusive, depth-walked pricing; split into
 *     opportunities / near misses / unverified.
 */
object Scanner {

    /** A near miss loses at most this much per set after fees. */
    const val NEAR_MISS_CENTS = 2.0

    data class Candidate(val structure: Structure, val rawTopCostE4: Long) {
        /** Top-of-book edge before fees, E4 (positive = cheaper than payout). */
        val rawEdgeE4: Long get() = structure.payoutPerSetCents * 100 - rawTopCostE4
        val tickers: List<String> get() = structure.legs.map { it.ticker }.distinct()
    }

    fun plan(events: List<EventInfo>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (e in events) {
            val byTicker = e.markets.associateBy { it.ticker }
            for (s in Structures.find(e)) {
                var raw = 0L
                var ok = true
                for (leg in s.legs) {
                    val ask = byTicker[leg.ticker]?.askE4(leg.side)
                    if (ask == null) {
                        ok = false
                        break
                    }
                    raw += ask
                }
                if (!ok) continue
                val c = Candidate(s, raw)
                // A box is only interesting when the book is crossed; a normal
                // spread is not a "near miss", just the spread.
                val floor = if (s.type == ArbType.BOX) 1L else -(NEAR_MISS_CENTS * 100).toLong()
                if (c.rawEdgeE4 >= floor) out += c
            }
        }
        return prioritize(out)
    }

    /**
     * Verified first, then the biggest pre-fee edge, then multi-market sets
     * (the structures single-market tools miss), then fewer books to fetch.
     */
    fun prioritize(cands: List<Candidate>): List<Candidate> =
        cands.sortedWith(
            compareByDescending<Candidate> { it.structure.verified }
                .thenByDescending { it.rawEdgeE4 }
                .thenByDescending { it.structure.type == ArbType.ALL_YES || it.structure.type == ArbType.ALL_NO }
                .thenBy { it.tickers.size }
        )

    /**
     * Tickers to fetch, in plan order, stopping before [maxBooks]. A
     * candidate is only included whole (all of its tickers fit).
     */
    fun booksToFetch(cands: List<Candidate>, maxBooks: Int): List<String> {
        val chosen = LinkedHashSet<String>()
        for (c in cands) {
            val need = c.tickers.filter { it !in chosen }
            if (chosen.size + need.size > maxBooks) continue
            chosen += need
        }
        return chosen.toList()
    }

    fun evaluate(cands: List<Candidate>, books: Map<String, MarketBook>): ScanResult {
        val opps = ArrayList<Opportunity>()
        val near = ArrayList<Opportunity>()
        val unverified = ArrayList<Opportunity>()
        for (c in cands) {
            val o = DepthWalker.evaluate(c.structure, books) ?: continue
            when {
                !c.structure.verified -> {
                    if (o.shortfallPerSetCents <= NEAR_MISS_CENTS) unverified += o
                }
                o.isProfitable -> opps += o
                o.shortfallPerSetCents <= NEAR_MISS_CENTS -> near += o
            }
        }
        return ScanResult(
            opportunities = opps.sortedByDescending { it.profitCents },
            nearMisses = near.sortedBy { it.shortfallPerSetCents },
            unverified = unverified.sortedBy { it.shortfallPerSetCents }
        )
    }
}
