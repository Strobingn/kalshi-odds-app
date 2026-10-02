package com.dirk.kalshiodds.arb.scan

import java.math.BigDecimal

/**
 * Finds sets of contracts whose payout is ≥ a fixed amount in every outcome.
 * Pure: metadata in, [Structure]s out. Pricing happens in [DepthWalker].
 *
 * - BOX: YES + NO of the same market always pays $1.
 * - ALL_YES: one YES in each market of an exhaustive set pays ≥ $1
 *   (at least one market resolves YES). Needs *exhaustive* coverage.
 * - ALL_NO: one NO in each of N mutually exclusive markets pays ≥ $(N−1)
 *   (at most one market resolves YES). Needs *exclusivity* only.
 * - LADDER: YES "above a" + NO "above b" with a < b pays $1 below a and
 *   above b, $2 in between. Mirror for "below" ladders.
 */
object Structures {

    private val LOWER_OPEN = setOf("less", "less_or_equal")
    private val UPPER_OPEN = setOf("greater", "greater_or_equal")
    private const val BETWEEN = "between"

    fun find(event: EventInfo): List<Structure> {
        val out = ArrayList<Structure>()
        val active = event.markets.filter { it.isActive }
        for (m in active) out += box(event, m)
        out += sets(event)
        out += ladders(event, active)
        return out
    }

    private fun box(e: EventInfo, m: MarketInfo) = Structure(
        type = ArbType.BOX,
        eventTicker = e.eventTicker,
        seriesTicker = e.seriesTicker,
        eventTitle = e.title,
        legs = listOf(LegSpec(m.ticker, m.label, Side.YES), LegSpec(m.ticker, m.label, Side.NO)),
        payoutPerSetCents = 100,
        verified = true
    )

    // ---- exhaustive / exclusive sets -------------------------------------

    enum class Coverage { NONE, EXCLUSIVE, EXCLUSIVE_AND_EXHAUSTIVE }

    internal data class Interval(
        val lo: Double, val loInc: Boolean,
        val hi: Double, val hiInc: Boolean
    )

    internal fun interval(m: MarketInfo): Interval? {
        val t = m.strikeType?.lowercase() ?: return null
        return when (t) {
            "less" -> m.capStrike?.let { Interval(Double.NEGATIVE_INFINITY, false, it, false) }
            "less_or_equal" -> m.capStrike?.let { Interval(Double.NEGATIVE_INFINITY, false, it, true) }
            "greater" -> m.floorStrike?.let { Interval(it, false, Double.POSITIVE_INFINITY, false) }
            "greater_or_equal" -> m.floorStrike?.let { Interval(it, true, Double.POSITIVE_INFINITY, false) }
            BETWEEN -> {
                val lo = m.floorStrike ?: return null
                val hi = m.capStrike ?: return null
                if (hi < lo) null else Interval(lo, true, hi, true)
            }
            else -> null
        }
    }

    /**
     * Proves what a bucket/range event covers from its strikes.
     *
     * Exclusive: no two buckets overlap. Exhaustive: buckets chain from −∞
     * to +∞ with no hole. Touching edges (`< 58` then `58–59`) are a proof.
     * A one-grid-step hole between inclusive edges (`58–59` then `60–61`,
     * or `…249.99` then `250.00`) is accepted only when Kalshi also flags
     * the event `mutually_exclusive`, i.e. it was designed as a partition.
     */
    fun rangeCoverage(markets: List<MarketInfo>, mutuallyExclusiveFlag: Boolean): Coverage {
        if (markets.size < 2) return Coverage.NONE
        val ivs = markets.map { interval(it) ?: return Coverage.NONE }
            .sortedWith(compareBy<Interval>({ it.lo }, { it.hi }))
        val unit = gridUnit(markets)
        var exclusive = true
        var exhaustive = ivs.first().lo == Double.NEGATIVE_INFINITY &&
            ivs.last().hi == Double.POSITIVE_INFINITY
        for (i in 1 until ivs.size) {
            val prev = ivs[i - 1]
            val next = ivs[i]
            val overlap = next.lo < prev.hi || (next.lo == prev.hi && next.loInc && prev.hiInc)
            if (overlap) {
                exclusive = false
                continue
            }
            if (next.lo == prev.hi) {
                // Touching: covered unless both edges exclude the point.
                if (!next.loInc && !prev.hiInc) exhaustive = false
            } else {
                val gap = next.lo - prev.hi
                val gridStep = prev.hiInc && next.loInc && gap <= unit * (1 + 1e-6)
                if (!(gridStep && mutuallyExclusiveFlag)) exhaustive = false
            }
        }
        return when {
            !exclusive -> Coverage.NONE
            exhaustive -> Coverage.EXCLUSIVE_AND_EXHAUSTIVE
            else -> Coverage.EXCLUSIVE
        }
    }

    /** Smallest decimal step used by the strikes (1 for integers, 0.01 for cents). */
    internal fun gridUnit(markets: List<MarketInfo>): Double {
        var scale = 0
        for (m in markets) {
            for (v in listOfNotNull(m.floorStrike, m.capStrike)) {
                if (!v.isFinite()) continue
                val s = BigDecimal(v.toString()).stripTrailingZeros().scale()
                if (s > scale) scale = s
            }
        }
        return BigDecimal.ONE.movePointLeft(scale.coerceAtMost(8)).toDouble()
    }

    private fun sets(e: EventInfo): List<Structure> {
        val all = e.markets
        val active = all.filter { it.isActive }
        if (active.size < 2) return emptyList()
        val allActive = active.size == all.size
        val isRangeEvent = all.all { interval(it) != null }
        val coverage = if (isRangeEvent) rangeCoverage(all, e.mutuallyExclusive) else Coverage.NONE
        if (!e.mutuallyExclusive && coverage == Coverage.NONE) return emptyList()

        val yesVerified = allActive && coverage == Coverage.EXCLUSIVE_AND_EXHAUSTIVE
        val yesNote = when {
            yesVerified -> "Buckets cover every outcome"
            !allActive -> "Unverified: some markets in this event are not trading"
            isRangeEvent -> "Unverified: buckets leave a gap"
            else -> "Unverified: can't prove one of these must resolve YES"
        }
        val noVerified = e.mutuallyExclusive || coverage != Coverage.NONE
        val n = active.size

        val out = ArrayList<Structure>(2)
        out += Structure(
            type = ArbType.ALL_YES,
            eventTicker = e.eventTicker,
            seriesTicker = e.seriesTicker,
            eventTitle = e.title,
            legs = active.map { LegSpec(it.ticker, it.label, Side.YES) },
            payoutPerSetCents = 100,
            verified = yesVerified,
            note = yesNote
        )
        out += Structure(
            type = ArbType.ALL_NO,
            eventTicker = e.eventTicker,
            seriesTicker = e.seriesTicker,
            eventTitle = e.title,
            legs = active.map { LegSpec(it.ticker, it.label, Side.NO) },
            payoutPerSetCents = (n - 1) * 100L,
            verified = noVerified,
            note = if (noVerified) "At most one of $n resolves YES" else "Unverified: exclusivity not proven"
        )
        return out
    }

    // ---- strike ladders ---------------------------------------------------

    /**
     * What a strike is measured on: the ticker minus its strike number. One
     * event can hold ladders on different things (BC by 2+ and SMU by 10+ in a
     * spread event; each player's rushing yards). Only same-subject strikes are
     * nested; "BC by 2+" + NOT "SMU by 10+" loses both legs if SMU wins by 15.
     */
    /**
     * Nested strikes price monotonically, give or take one stray level. Two or
     * more breaks (a hump summing to ~$1) means exact-value buckets whatever
     * strike_type says (KXSTARSHIPSPACE-26: 3:5c 4:35c 5:47c 6:12c 7:3c).
     */
    fun shapeSuspect(above: Boolean, sorted: List<MarketInfo>, tolE4: Int = 500): String? {
        val mids = sorted.mapNotNull { m ->
            val b = m.yesBidE4
            val a = m.yesAskE4
            if (b == null || a == null || a <= 0) null else (b + a) / 2
        }
        val breaks = mids.zipWithNext().count { (x, y) -> if (above) y > x + tolE4 else y < x - tolE4 }
        return if (breaks >= 2) "Prices not monotone across strikes ($breaks breaks): looks like buckets, check the rules" else null
    }

    /** Rules text with every number blanked: nested strikes differ only in the number. */
    fun rulesTemplate(rules: String?): String =
        (rules ?: "").trim().lowercase().replace(Regex("[0-9][0-9,]*(\\.[0-9]+)?"), "#")

    fun ladderSubject(ticker: String): String {
        val parts = ticker.split("-").toMutableList()
        if (parts.size >= 2 && parts.last().matches(Regex("[0-9.]+"))) {
            return parts.dropLast(1).joinToString("-")
        }
        parts[parts.size - 1] = parts.last().replace(Regex("[0-9.]+$"), "")
        return parts.joinToString("-")
    }

    private fun ladders(e: EventInfo, active: List<MarketInfo>): List<Structure> {
        val out = ArrayList<Structure>()
        // Nested strikes overlap, so a mutually exclusive event holds no ladder.
        if (e.mutuallyExclusive) return out
        val groups = active.filter {
            val t = it.strikeType?.lowercase()
            (t in UPPER_OPEN && it.floorStrike != null) || (t in LOWER_OPEN && it.capStrike != null)
        }.groupBy { listOf(it.strikeType!!.lowercase(), it.closeTime ?: "", ladderSubject(it.ticker), rulesTemplate(it.rulesPrimary)) }

        for ((key, ms) in groups) {
            if (ms.size < 2) continue
            val above = key[0] in UPPER_OPEN
            val sorted = if (above) ms.sortedBy { it.floorStrike } else ms.sortedBy { it.capStrike }
            val suspect = shapeSuspect(above, sorted)
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                val low = sorted[i]
                val high = sorted[j]
                val a = if (above) low.floorStrike!! else low.capStrike!!
                val b = if (above) high.floorStrike!! else high.capStrike!!
                if (!(a < b)) continue
                // Above-ladder: YES(>a) + NO(>b). Below-ladder: YES(<b) + NO(<a).
                val legs = if (above) {
                    listOf(LegSpec(low.ticker, low.label, Side.YES), LegSpec(high.ticker, high.label, Side.NO))
                } else {
                    listOf(LegSpec(high.ticker, high.label, Side.YES), LegSpec(low.ticker, low.label, Side.NO))
                }
                out += Structure(
                    type = ArbType.LADDER,
                    eventTicker = e.eventTicker,
                    seriesTicker = e.seriesTicker,
                    eventTitle = e.title,
                    legs = legs,
                    payoutPerSetCents = 100,
                    verified = suspect == null,
                    note = suspect ?: "Pays $1 outside the strikes, $2 between them"
                )
            }
        }
        return out
    }
}
