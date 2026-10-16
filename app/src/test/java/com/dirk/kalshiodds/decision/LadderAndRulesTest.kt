package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LadderAndRulesTest {

    @Test
    fun tenDollarClipIncludesRoundedUpFee() {
        val clip = TenDollarClip.size(0.90)!!
        assertTrue(clip.costUsd <= 10.0 + 1e-9)
        assertEquals(KalshiFee.totalCost(clip.contracts, 0.90, KalshiFee.TAKER_COEFFICIENT), clip.costUsd, 1e-12)
        assertTrue(KalshiFee.totalCost(clip.contracts + 1, 0.90, KalshiFee.TAKER_COEFFICIENT) > 10.0)
    }

    @Test
    fun fav15BuysFavouriteOnlyInBandWithSizeAndTime() {
        val enter = Fav15Rule.evaluate(0.92, 0.10, 500.0, 500.0, 400.0, bookFresh = true)
        assertTrue(enter is Fav15Rule.Result.Enter)
        assertEquals("YES", (enter as Fav15Rule.Result.Enter).signal.side)
        assertEquals(0.92, enter.signal.price, 1e-12)
        assertTrue(Fav15Rule.evaluate(0.92, 0.10, 500.0, 500.0, 299.0, true) is Fav15Rule.Result.Skip)
        assertTrue(Fav15Rule.evaluate(0.80, 0.22, 500.0, 500.0, 400.0, true) is Fav15Rule.Result.Skip)
        assertTrue(Fav15Rule.evaluate(0.99, 0.01, 500.0, 500.0, 400.0, true) is Fav15Rule.Result.Skip)
        assertTrue(Fav15Rule.evaluate(0.92, 0.10, 3.0, 500.0, 400.0, true) is Fav15Rule.Result.NoFill)
        assertTrue(Fav15Rule.evaluate(0.92, 0.10, 500.0, 500.0, 400.0, false) is Fav15Rule.Result.NoFill)
        val no = Fav15Rule.evaluate(0.08, 0.93, 500.0, 500.0, 400.0, true) as Fav15Rule.Result.Enter
        assertEquals("NO", no.signal.side)
    }

    @Test
    fun v060UsesFrozenFlbCoefficients() {
        // tb2 at mid 0.5: logit p = 0.15001 → p = sigmoid(0.15001)
        assertEquals(1.0 / (1.0 + kotlin.math.exp(-0.15001)), V060Rule.probability(0.5, 600.0), 1e-12)
        assertEquals(0, V060Rule.bucket(16 * 3600.0))
        assertEquals(1, V060Rule.bucket(4 * 3600.0))
        assertEquals(2, V060Rule.bucket(3599.0))
        assertEquals(86_400L, V060Rule.checkpoint(86_400.0))
        assertEquals(600L, V060Rule.checkpoint(400.0))
        assertNull(V060Rule.checkpoint(80_000.0))
    }

    @Test
    fun v060PicksBestSideAboveThreshold() {
        val quotes = listOf(
            V060Rule.Quote("KXBTCD-26OCT0617-T1", yesBid = 0.80, yesAsk = 0.81, noAsk = 0.20, yesAskSize = 500.0, noAskSize = 500.0),
            V060Rule.Quote("KXBTCD-26OCT0617-T2", yesBid = 0.49, yesAsk = 0.50, noAsk = 0.51, yesAskSize = 500.0, noAskSize = 500.0)
        )
        val pick = V060Rule.bestPick(quotes, 600.0)
        assertNotNull(pick)
        assertTrue(pick!!.evPerDollar >= V060Rule.THRESHOLD)
        assertEquals("YES", pick.side)
        // A fair, tight market near 50¢ with a small residual doesn't clear 6%.
        assertNull(V060Rule.bestPick(listOf(quotes[1]), 20 * 3600.0))
        assertEquals("KXBTCD-26OCT0617", V060Rule.eventOf("KXBTCD-26OCT0617-T1"))
    }

    @Test
    fun ladderPromotionNeedsSampleAndPositiveCiLowerBound() {
        fun items(n: Int, pnl: (Int) -> Double) = (0 until n).map {
            StrategyLadder.Item(cluster = "m$it", filled = true, settled = true, pnlUsd = pnl(it))
        }
        val paper = StrategyLadder.Stage.PAPER
        val few = StrategyLadder.status(StrategyLadder.Id.V060, paper, items(99) { 1.0 })
        assertFalse(few.eligibleForNext)
        val enough = StrategyLadder.status(StrategyLadder.Id.V060, paper, items(100) { if (it % 4 == 0) -2.0 else 1.5 })
        assertTrue(enough.reason, enough.eligibleForNext)
        val noisy = StrategyLadder.status(StrategyLadder.Id.V060, paper, items(100) { if (it % 2 == 0) -1.0 else 1.0 })
        assertFalse(noisy.eligibleForNext)
        // fav15 counts SETTLED, needs 300.
        val unsettled = (0 until 400).map { StrategyLadder.Item("m$it", true, settled = it < 250, pnlUsd = if (it < 250) 0.5 else null) }
        assertFalse(StrategyLadder.status(StrategyLadder.Id.FAV15, paper, unsettled).eligibleForNext)
        val fav = StrategyLadder.status(StrategyLadder.Id.FAV15, paper, items(300) { if (it % 10 == 0) -3.0 else 0.6 })
        assertTrue(fav.eligibleForNext)
        // 0.3.40: no live stage — shadow is the top.
        val top = StrategyLadder.status(StrategyLadder.Id.FAV15, StrategyLadder.Stage.SHADOW, items(300) { 0.6 })
        assertFalse(top.eligibleForNext)
        assertTrue(top.reason.contains("never sent"))
        assertEquals(StrategyLadder.Stage.SHADOW, StrategyLadder.parseStage("LIMITED_LIVE"))
        assertEquals(listOf("PAPER", "SHADOW"), StrategyLadder.Stage.values().map { it.name })
    }

    @Test
    fun ladderStoreOneEntryPerMarketAndSettlesWithFees() {
        var id = 0
        val store = LadderStore(null, nowMs = { 1L }, idFactory = { "e${id++}" })
        val clip = TenDollarClip.size(0.90)!!
        assertNotNull(store.record(Fav15Rule.ID, "KXBTC15M-A", "KXBTC15M-A", "YES", clip, 0.90, ""))
        assertNull(store.record(Fav15Rule.ID, "KXBTC15M-A", "KXBTC15M-A", "YES", clip, 0.90, ""))
        assertNotNull(store.record(V060Rule.ID, "KXBTCD-E-T1", "KXBTCD-E", "YES", clip, 0.90, ""))
        assertNull(store.record(V060Rule.ID, "KXBTCD-E-T2", "KXBTCD-E", "NO", clip, 0.90, ""))
        store.settle("KXBTC15M-A", "yes")
        val e = store.snapshot().entries.first { it.ticker == "KXBTC15M-A" }
        assertEquals(clip.contracts - clip.costUsd, e.pnlUsd!!, 1e-9)
        assertEquals(setOf("KXBTCD-E-T1"), store.openTickers())
        val status = StrategyLadder.status(StrategyLadder.Id.FAV15, store.stage(StrategyLadder.Id.FAV15), store.items(Fav15Rule.ID))
        assertFalse(store.promote(StrategyLadder.Id.FAV15, status))
        assertEquals(StrategyLadder.Stage.PAPER, store.stage(StrategyLadder.Id.FAV15))
    }

    @Test
    fun clusteredBootstrapIsSeededAndClustersByMarket() {
        val values = (0 until 50).flatMap { m -> listOf("m$m" to 1.0, "m$m" to (if (m % 3 == 0) -1.0 else 0.5)) }
        val a = ClusteredBootstrap.meanCi(values)!!
        val b = ClusteredBootstrap.meanCi(values)!!
        assertEquals(a, b)
        assertEquals(50, a.clusters)
        assertTrue(a.lo <= a.mean && a.mean <= a.hi)
    }
}
