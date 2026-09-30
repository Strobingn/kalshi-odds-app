package com.dirk.kalshiarb.scan

enum class Side { YES, NO }

/** One ask level: price in 1/10 000 dollars (FixedPointDollars E4), whole contracts. */
data class Level(val priceE4: Int, val qty: Long)

/** A Kalshi market as listed under an event (top of book from the listing). */
data class MarketInfo(
    val ticker: String,
    val eventTicker: String,
    val title: String = "",
    val subtitle: String = "",
    val status: String = "active",
    val strikeType: String? = null,
    val floorStrike: Double? = null,
    val capStrike: Double? = null,
    val yesBidE4: Int? = null,
    val yesAskE4: Int? = null,
    val noBidE4: Int? = null,
    val noAskE4: Int? = null,
    val closeTime: String? = null
) {
    val isActive: Boolean get() = status.equals("active", true) || status.equals("open", true)

    /** Top-of-book ask for [side]; falls back to 1 − opposite bid. Null if not buyable. */
    fun askE4(side: Side): Int? {
        val direct = if (side == Side.YES) yesAskE4 else noAskE4
        val opposite = if (side == Side.YES) noBidE4 else yesBidE4
        val ask = direct ?: opposite?.let { 10_000 - it }
        return ask?.takeIf { it in 1..9_999 }
    }

    /** Short human label ("58° to 59°", "Above 108,000", ...). */
    val label: String get() = subtitle.ifBlank { title.ifBlank { ticker } }
}

data class EventInfo(
    val eventTicker: String,
    val seriesTicker: String = "",
    val title: String = "",
    val subTitle: String = "",
    val mutuallyExclusive: Boolean = false,
    val category: String = "",
    val markets: List<MarketInfo> = emptyList()
)

/** Buyable ask ladders for both sides, cheapest first. */
data class MarketBook(val ticker: String, val yesAsks: List<Level>, val noAsks: List<Level>) {

    fun asks(side: Side): List<Level> = if (side == Side.YES) yesAsks else noAsks

    companion object {
        /**
         * Kalshi order books list bids only. A YES ask at price p is a NO bid
         * at 1 − p (and vice versa), with the same size.
         */
        fun fromBids(ticker: String, yesBids: List<Level>, noBids: List<Level>): MarketBook =
            MarketBook(ticker, yesAsks = invert(noBids), noAsks = invert(yesBids))

        /** A one-contract, top-of-book view built from a market listing. */
        fun topOfBook(m: MarketInfo, qty: Long = 1L): MarketBook = MarketBook(
            m.ticker,
            yesAsks = listOfNotNull(m.askE4(Side.YES)?.let { Level(it, qty) }),
            noAsks = listOfNotNull(m.askE4(Side.NO)?.let { Level(it, qty) })
        )

        private fun invert(bids: List<Level>): List<Level> =
            bids.asSequence()
                .filter { it.qty > 0 && it.priceE4 in 1..9_999 }
                .groupBy { 10_000 - it.priceE4 }
                .map { (p, lv) -> Level(p, lv.sumOf { it.qty }) }
                .sortedBy { it.priceE4 }
                .toList()
    }
}

enum class ArbType(val label: String) {
    BOX("Same-market box"),
    ALL_YES("Exhaustive set · buy every YES"),
    ALL_NO("Exclusive set · buy every NO"),
    LADDER("Strike ladder")
}

data class LegSpec(val ticker: String, val label: String, val side: Side)

/**
 * A set of legs whose combined payout is at least [payoutPerSetCents] in
 * every outcome, when one contract of each leg is held to settlement.
 * [verified] = the payoff guarantee was proven from market metadata.
 */
data class Structure(
    val type: ArbType,
    val eventTicker: String,
    val seriesTicker: String,
    val eventTitle: String,
    val legs: List<LegSpec>,
    val payoutPerSetCents: Long,
    val verified: Boolean,
    val note: String = ""
) {
    val key: String = type.name + "|" + legs.joinToString(",") { "${it.ticker}:${it.side}" }
}

data class LegFill(
    val spec: LegSpec,
    val qty: Long,
    /** Volume-weighted price paid, E4. */
    val avgPriceE4: Double,
    /** Deepest (most expensive) level touched, E4. */
    val worstPriceE4: Int,
    val debitCents: Long,
    val feeE4: Long
)

/** A structure priced against real book depth. */
data class Opportunity(
    val structure: Structure,
    /** Number of complete sets (contracts per leg). */
    val sets: Long,
    val legs: List<LegFill>,
    val costCents: Long,
    val feeE4: Long,
    val payoutCents: Long,
    /** Guaranteed profit after fees for [sets] sets; ≤ 0 for near misses. */
    val profitCents: Long,
    /** Best (lowest) fee-inclusive cost minus payout, per set, in cents. Negative = edge. */
    val shortfallPerSetCents: Double,
    /** Top-of-book cost per set before fees, E4. */
    val rawTopCostE4: Long
) {
    val key: String get() = structure.key
    val isProfitable: Boolean get() = profitCents > 0
    val profitPct: Double get() = if (costCents > 0) profitCents * 100.0 / costCents else 0.0
}

data class ScanResult(
    val opportunities: List<Opportunity>,
    val nearMisses: List<Opportunity>,
    /** Priced structures whose payoff could not be proven. Never counted. */
    val unverified: List<Opportunity>
) {
    val lockedProfitCents: Long get() = opportunities.sumOf { it.profitCents }
}
