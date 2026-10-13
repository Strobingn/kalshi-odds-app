package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0357Test {
    private val tk = "KXBTC15M-26OCT122015-15"
    private val close = 1_791_850_500_000L
    private fun q(now: Long, spot: Double, ya: Double, yb: Double, size: Double = 50.0, strike: Double = 100_000.0) = ScalpRule.Quote(
        ticker = tk, nowMs = now, closeMs = close, bookAtMs = now, yesBid = yb, yesBidSize = size, yesAsk = ya, yesAskSize = size,
        spot = spot, strike = strike, sigmaPerSec = 3e-5
    )

    @Test fun twentyVariantsDistinctAndMapped() {
        assertEquals(20, ShockFav.VARIANTS.size)
        assertEquals(20, ShockFav.VARIANTS.map { it.code }.toSet().size)
        assertEquals(28, ScalpModels.ALL.size)
        assertEquals(20, ScalpModels.SF_MODELS.size)
        assertTrue(ShockFav.isShockFav("sf07") && ShockFav.isShockFav("sf20f") && !ShockFav.isShockFav("fcride-h300"))
    }

    @Test fun shockEntersAtAskCappedAndSettles() {
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val t0 = close - 600_000L
        // flat spot, then +8 bp in 5 s; book lags (YES ask 0.40 while fair ≈ 0.6+)
        for (i in 0..5) book.onShockFavQuote(q(t0 + i * 1000L, 100_020.0, 0.41, 0.39), true)
        val out = book.onShockFavQuote(q(t0 + 10_000L, 100_100.0, 0.41, 0.39, size = 7.0), true)
        assertTrue(out.isNotEmpty())
        out.forEach {
            assertEquals("YES", it.side); assertEquals(7, it.contracts); assertEquals(0.41, it.entryPrice!!, 1e-9)
            assertTrue(ShockFav.isShockFav(it.variantId)); assertTrue(ScalpModels.modelOf(it) in ScalpModels.SF_MODELS)
        }
        // no duplicate entry for the same variant while open
        val again = book.onShockFavQuote(q(t0 + 11_000L, 100_200.0, 0.41, 0.39), true).filter { it.state == ScalpState.OPEN && it.signalAtMs == t0 + 11_000L }
        assertTrue(again.none { a -> out.any { it.variantId == a.variantId } })
        val settled = book.settle(tk, "yes", close + 5_000L)
        assertTrue(settled.all { it.state == ScalpState.CLOSED && it.netUsd != null })
    }

    @Test fun lateFavOncePerMarketAndDisabledMeansNothing() {
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val t = close - 40_000L
        assertTrue(book.onShockFavQuote(q(t, 100_400.0, 0.85, 0.83), false).isEmpty())
        val a = book.onShockFavQuote(q(t + 1000, 100_400.0, 0.85, 0.83), true).filter { ShockFav.isFavLeg(it.variantId) }
        assertTrue(a.isNotEmpty())
        val b = book.onShockFavQuote(q(t + 2000, 100_400.0, 0.85, 0.83), true).filter { ShockFav.isFavLeg(it.variantId) }
        assertTrue(b.isEmpty())
    }

    @Test fun exitRules() {
        val base = ShockFav.VARIANTS[0]
        assertEquals("bid caught fair", ShockFav.shockExit(base, 5.0, 300.0, 0.60, 0.605))
        assertEquals("max hold 90s", ShockFav.shockExit(base, 90.0, 300.0, 0.50, 0.60))
        assertEquals("τ < 30 s", ShockFav.shockExit(base, 5.0, 25.0, 0.50, 0.60))
        val combo = ShockFav.VARIANTS[19]
        assertNull(ShockFav.shockExit(combo, 200.0, 25.0, 0.80, 0.95)) // favourite zone rides to settlement
        assertEquals(0.5, ShockFav.brtiFairYes(100.0, 100.0, 3e-5, 30.0, List(30) { 100.0 }), 1e-6)
    }

    @Test fun bankrollCapsEntries() {
        val book = ScalpBook(bankrollUsd = { 3.0 })
        val t0 = close - 600_000L
        for (i in 0..5) book.onShockFavQuote(q(t0 + i * 1000L, 100_020.0, 0.41, 0.39), true)
        val out = book.onShockFavQuote(q(t0 + 10_000L, 100_100.0, 0.41, 0.39), true)
        assertTrue(out.sumOf { it.entryCostUsd } <= 3.0 + 1e-9)
    }
}
