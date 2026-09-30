package com.dirk.kalshiarb.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTest {

    // ---- helpers -----------------------------------------------------------

    private fun mkt(
        ticker: String,
        strike: String? = null,
        floor: Double? = null,
        cap: Double? = null,
        yesBid: Int? = null,
        yesAsk: Int? = null,
        status: String = "active",
        close: String = "2026-09-30T21:00:00Z"
    ) = MarketInfo(
        ticker = ticker,
        eventTicker = "EV",
        subtitle = ticker,
        status = status,
        strikeType = strike,
        floorStrike = floor,
        capStrike = cap,
        yesBidE4 = yesBid,
        yesAskE4 = yesAsk,
        noBidE4 = yesAsk?.let { 10_000 - it },
        noAskE4 = yesBid?.let { 10_000 - it },
        closeTime = close
    )

    private fun event(vararg m: MarketInfo, me: Boolean = false) =
        EventInfo(eventTicker = "EV", seriesTicker = "KXEV", title = "Test event", mutuallyExclusive = me, markets = m.toList())

    /** A book with [qty] contracts at the listed top of book on both sides. */
    private fun books(e: EventInfo, qty: Long = 50): Map<String, MarketBook> =
        e.markets.associate { it.ticker to MarketBook.topOfBook(it, qty) }

    private fun scan(e: EventInfo, b: Map<String, MarketBook> = books(e)): ScanResult =
        Scanner.evaluate(Scanner.plan(listOf(e)), b)

    // ---- box -----------------------------------------------------------------

    @Test
    fun boxWhenYesPlusNoAskUnderOneDollar() {
        // YES asks at 48¢ (from NO bids at 52¢), NO asks at 48¢ (from YES bids at 52¢).
        val book = MarketBook.fromBids("M", yesBids = listOf(Level(5_200, 20)), noBids = listOf(Level(5_200, 10)))
        assertEquals(listOf(Level(4_800, 10)), book.yesAsks)
        assertEquals(listOf(Level(4_800, 20)), book.noAsks)
        val s = Structures.find(event(mkt("M"))).single { it.type == ArbType.BOX }
        val o = DepthWalker.evaluate(s, mapOf("M" to book))!!
        // 10 sets: each leg 10 @ 48¢ = $4.80 + $0.17472 fee → $4.98. Cost $9.96, payout $10.
        assertEquals(10L, o.sets)
        assertEquals(996L, o.costCents)
        assertEquals(1_000L, o.payoutCents)
        assertEquals(4L, o.profitCents)
        assertTrue(o.isProfitable)
    }

    @Test
    fun normalSpreadIsNotABoxCandidate() {
        val e = event(mkt("M", yesBid = 4_900, yesAsk = 5_000))
        assertTrue(Scanner.plan(listOf(e)).none { it.structure.type == ArbType.BOX })
    }

    // ---- exhaustive / exclusive sets ----------------------------------------

    private fun threeBuckets(bid: Int, ask: Int, me: Boolean = false) = event(
        mkt("LO", strike = "less", cap = 100.0, yesBid = bid, yesAsk = ask),
        mkt("MID", strike = "between", floor = 100.0, cap = 200.0, yesBid = bid, yesAsk = ask),
        mkt("HI", strike = "greater", floor = 200.0, yesBid = bid, yesAsk = ask),
        me = me
    )

    @Test
    fun exhaustiveBucketsBuyAllYes() {
        val r = scan(threeBuckets(bid = 2_900, ask = 3_000))
        val o = r.opportunities.single()
        assertEquals(ArbType.ALL_YES, o.structure.type)
        assertTrue(o.structure.verified)
        assertEquals(50L, o.sets)
        // 3 × (50 @ 30¢ = $15.00 + $0.735 fee → $15.74) = $47.22 vs $50 payout.
        assertEquals(4_722L, o.costCents)
        assertEquals(278L, o.profitCents)
        assertEquals(o.profitCents, r.lockedProfitCents)
    }

    @Test
    fun exclusiveBucketsBuyAllNoWhenYesBidsSumOverOne() {
        // YES bids 40¢ ×3 = $1.20 → NO asks 60¢ ×3 = $1.80 for a ≥ $2 payout.
        val r = scan(threeBuckets(bid = 4_000, ask = 4_100))
        val o = r.opportunities.single()
        assertEquals(ArbType.ALL_NO, o.structure.type)
        assertEquals(200L, o.structure.payoutPerSetCents)
        assertEquals(50L, o.sets)
        assertEquals(10_000L, o.payoutCents)
        assertTrue(o.profitCents > 0)
    }

    @Test
    fun gapInBucketsIsUnverifiedAndNotCounted() {
        val e = event(
            mkt("LO", strike = "less", cap = 100.0, yesBid = 2_900, yesAsk = 3_000),
            mkt("MID", strike = "between", floor = 150.0, cap = 200.0, yesBid = 2_900, yesAsk = 3_000),
            mkt("HI", strike = "greater", floor = 200.0, yesBid = 2_900, yesAsk = 3_000)
        )
        assertEquals(Structures.Coverage.EXCLUSIVE, Structures.rangeCoverage(e.markets, false))
        val r = scan(e)
        assertTrue(r.opportunities.isEmpty())
        assertEquals(0L, r.lockedProfitCents)
        val u = r.unverified.single()
        assertEquals(ArbType.ALL_YES, u.structure.type)
        assertFalse(u.structure.verified)
        assertTrue(u.isProfitable) // looks free, but a value in (100, 150) pays nothing
    }

    @Test
    fun integerGridGapNeedsMutuallyExclusiveFlag() {
        val ms = listOf(
            mkt("A", strike = "less", cap = 58.0),
            mkt("B", strike = "between", floor = 58.0, cap = 59.0),
            mkt("C", strike = "between", floor = 60.0, cap = 61.0),
            mkt("D", strike = "greater", floor = 61.0)
        )
        assertEquals(Structures.Coverage.EXCLUSIVE_AND_EXHAUSTIVE, Structures.rangeCoverage(ms, true))
        assertEquals(Structures.Coverage.EXCLUSIVE, Structures.rangeCoverage(ms, false))
    }

    @Test
    fun centGridIsDetected() {
        val ms = listOf(
            mkt("A", strike = "less", cap = 108_000.0),
            mkt("B", strike = "between", floor = 108_000.0, cap = 108_249.99),
            mkt("C", strike = "greater", floor = 108_249.99)
        )
        assertEquals(0.01, Structures.gridUnit(ms), 1e-12)
        assertEquals(Structures.Coverage.EXCLUSIVE_AND_EXHAUSTIVE, Structures.rangeCoverage(ms, false))
    }

    @Test
    fun overlappingBucketsAreNotASet() {
        val ms = listOf(
            mkt("A", strike = "between", floor = 100.0, cap = 200.0),
            mkt("B", strike = "between", floor = 150.0, cap = 250.0)
        )
        assertEquals(Structures.Coverage.NONE, Structures.rangeCoverage(ms, false))
        assertTrue(Structures.find(event(*ms.toTypedArray())).none { it.type == ArbType.ALL_YES || it.type == ArbType.ALL_NO })
    }

    @Test
    fun closedMarketInEventMakesAllYesUnverified() {
        val e = event(
            mkt("LO", strike = "less", cap = 100.0, yesBid = 2_900, yesAsk = 3_000),
            mkt("MID", strike = "between", floor = 100.0, cap = 200.0, yesBid = 2_900, yesAsk = 3_000, status = "closed"),
            mkt("HI", strike = "greater", floor = 200.0, yesBid = 2_900, yesAsk = 3_000)
        )
        val yes = Structures.find(e).single { it.type == ArbType.ALL_YES }
        assertFalse(yes.verified)
        assertEquals(2, yes.legs.size)
    }

    @Test
    fun mutuallyExclusiveNonRangeEventOnlyProvesAllNo() {
        val e = event(mkt("X", yesBid = 3_000, yesAsk = 3_100), mkt("Y", yesBid = 3_000, yesAsk = 3_100), me = true)
        val s = Structures.find(e)
        assertFalse(s.single { it.type == ArbType.ALL_YES }.verified)
        assertTrue(s.single { it.type == ArbType.ALL_NO }.verified)
    }

    @Test
    fun independentMarketsGetNoSetStructures() {
        val e = event(mkt("X", yesBid = 3_000, yesAsk = 3_100), mkt("Y", yesBid = 3_000, yesAsk = 3_100))
        assertTrue(Structures.find(e).none { it.type == ArbType.ALL_YES || it.type == ArbType.ALL_NO })
    }

    // ---- ladder ---------------------------------------------------------------

    @Test
    fun ladderBuysYesLowAndNoHighWithDepth() {
        val e = event(
            mkt("K100", strike = "greater", floor = 100_000.0, yesBid = 3_900, yesAsk = 4_000),
            mkt("K101", strike = "greater", floor = 101_000.0, yesBid = 5_000, yesAsk = 5_100)
        )
        val s = Structures.find(e).single { it.type == ArbType.LADDER }
        assertEquals(listOf(LegSpec("K100", "K100", Side.YES), LegSpec("K101", "K101", Side.NO)), s.legs)
        val b = mapOf(
            "K100" to MarketBook("K100", yesAsks = listOf(Level(4_000, 5), Level(4_500, 10), Level(6_000, 100)), noAsks = emptyList()),
            "K101" to MarketBook("K101", yesAsks = emptyList(), noAsks = listOf(Level(5_000, 8), Level(5_200, 100)))
        )
        val o = DepthWalker.evaluate(s, b)!!
        // Past 8 sets the marginal set costs 45¢ + 52¢ + fees > $1, so size stops at 8.
        assertEquals(8L, o.sets)
        assertEquals(763L, o.costCents)
        assertEquals(37L, o.profitCents)
        assertEquals(8L, o.legs[0].qty)
        assertEquals(4_500, o.legs[0].worstPriceE4)
        assertEquals((5 * 4_000 + 3 * 4_500) / 8.0, o.legs[0].avgPriceE4, 1e-9)
        assertEquals(5_000, o.legs[1].worstPriceE4)
    }

    @Test
    fun belowLadderMirrors() {
        val e = event(
            mkt("L1", strike = "less", cap = 100.0),
            mkt("L2", strike = "less", cap = 200.0)
        )
        val s = Structures.find(e).single { it.type == ArbType.LADDER }
        assertEquals(listOf(LegSpec("L2", "L2", Side.YES), LegSpec("L1", "L1", Side.NO)), s.legs)
    }

    @Test
    fun monotoneLadderIsNotAnOpportunity() {
        val e = event(
            mkt("K100", strike = "greater", floor = 100.0, yesBid = 6_000, yesAsk = 6_100),
            mkt("K101", strike = "greater", floor = 101.0, yesBid = 4_000, yesAsk = 4_100)
        )
        val r = scan(e)
        assertTrue(r.opportunities.isEmpty())
        assertTrue(r.nearMisses.isEmpty())
    }

    @Test
    fun differentExpiriesDoNotLadder() {
        val e = event(
            mkt("K100", strike = "greater", floor = 100.0, close = "2026-09-30T21:00:00Z"),
            mkt("K101", strike = "greater", floor = 101.0, close = "2026-10-01T21:00:00Z")
        )
        assertTrue(Structures.find(e).none { it.type == ArbType.LADDER })
    }

    @Test
    fun differentSubjectsDoNotLadder() {
        // First live scan flagged "BC by 2+" + NOT "SMU by 10+" as risk-free; both lose if SMU wins by 15.
        assertEquals("KXNCAAFSPREAD-26OCT03BCSMU-BC", Structures.ladderSubject("KXNCAAFSPREAD-26OCT03BCSMU-BC2"))
        assertEquals("KXNCAAFSPREAD-26OCT03BCSMU-SMU", Structures.ladderSubject("KXNCAAFSPREAD-26OCT03BCSMU-SMU10"))
        assertEquals("KXNFLRSHYDS-X-CLERSANDERS23", Structures.ladderSubject("KXNFLRSHYDS-X-CLERSANDERS23-25"))
        assertEquals(Structures.ladderSubject("KXBTCD-26SEP3017-T84999.99"), Structures.ladderSubject("KXBTCD-26SEP3017-T85249.99"))
        val e = event(
            mkt("S-BC2", strike = "greater", floor = 1.5),
            mkt("S-SMU10", strike = "greater", floor = 9.5),
            mkt("S-BC5", strike = "greater", floor = 4.5)
        )
        val lad = Structures.find(e).filter { it.type == ArbType.LADDER }
        assertEquals(listOf(listOf("S-BC2", "S-BC5")), lad.map { s -> s.legs.map { it.ticker } })
    }

    @Test
    fun bucketShapedLadderIsUnverified() {
        val mids = listOf(3 to 500, 4 to 3_500, 5 to 4_700, 6 to 1_200, 7 to 300)
        val ms = mids.map { (k, p) -> mkt("SS-$k.0", strike = "less", cap = k.toDouble(), yesBid = p - 100, yesAsk = p + 100) }
        val lad = Structures.find(event(*ms.toTypedArray())).filter { it.type == ArbType.LADDER }
        assertTrue(lad.isNotEmpty())
        assertTrue(lad.none { it.verified })
        assertTrue(Structures.find(event(*ms.toTypedArray(), me = true)).none { it.type == ArbType.LADDER })
    }

    @Test
    fun differentRulesDoNotLadder() {
        val r7 = mkt("R-7.0", strike = "less", cap = 7.0)
            .copy(rulesPrimary = "If fewer than 7 Starship flights reach space in 2026, resolves Yes.")
        val r5 = mkt("R-5.0", strike = "less", cap = 5.0)
            .copy(rulesPrimary = "If fewer than 5 Starship flights reach space before Jul 1, 2026, resolves Yes.")
        assertTrue(Structures.find(event(r7, r5)).none { it.type == ArbType.LADDER })
        val same = r5.copy(rulesPrimary = r7.rulesPrimary!!.replace("7", "5"))
        assertEquals(1, Structures.find(event(r7, same)).count { it.type == ArbType.LADDER })
        assertEquals("fewer than # flights, $# each", Structures.rulesTemplate("Fewer than 12 flights, $1,000.50 each"))
    }

    // ---- depth / near miss -----------------------------------------------------

    @Test
    fun takeWalksLevelsAndRejectsThinBooks() {
        val asks = listOf(Level(100, 3), Level(200, 4))
        assertEquals(listOf(Level(100, 3), Level(200, 2)), DepthWalker.take(asks, 5))
        assertNull(DepthWalker.take(asks, 8))
    }

    @Test
    fun nearMissWithinTwoCents() {
        // Σ YES asks = $0.96 raw; ~4.6¢ of fees push it just over $1.
        val e = event(
            mkt("LO", strike = "less", cap = 100.0, yesBid = 3_100, yesAsk = 3_200),
            mkt("MID", strike = "between", floor = 100.0, cap = 200.0, yesBid = 3_100, yesAsk = 3_200),
            mkt("HI", strike = "greater", floor = 200.0, yesBid = 3_100, yesAsk = 3_200)
        )
        val r = scan(e, books(e, qty = 1_000))
        assertTrue(r.opportunities.isEmpty())
        val n = r.nearMisses.single()
        assertEquals(ArbType.ALL_YES, n.structure.type)
        assertFalse(n.isProfitable)
        assertTrue(n.shortfallPerSetCents > 0 && n.shortfallPerSetCents <= Scanner.NEAR_MISS_CENTS)
    }

    @Test
    fun missingBookMeansNotPriced() {
        val e = threeBuckets(bid = 2_900, ask = 3_000)
        val partial = books(e).filterKeys { it != "MID" }
        assertTrue(scan(e, partial).opportunities.isEmpty())
        assertNotNull(Scanner.plan(listOf(e)).firstOrNull { it.structure.type == ArbType.ALL_YES })
    }

    @Test
    fun booksToFetchRespectsBudgetWholeCandidates() {
        val e = threeBuckets(bid = 2_900, ask = 3_000)
        val plan = Scanner.plan(listOf(e))
        assertEquals(3, Scanner.booksToFetch(plan, 3).size)
        assertTrue(Scanner.booksToFetch(plan, 2).size <= 2)
    }
}
