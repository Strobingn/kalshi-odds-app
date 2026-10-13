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

    /** 300 s of 1 Hz spot with ±0.5 bp noise (σ ≈ 5e-5), ending at [t]. */
    private fun warm(book: ScalpBook, t: Long, px: Double) {
        for (k in 300 downTo 1) book.tickSpot("BTC", px * (1 + (if (k % 2 == 0) 5e-5 else -5e-5)), t - k * 1000L)
    }

    @Test fun twentyVariantsDistinctAndMapped() {
        assertEquals(45, ShockFav.VARIANTS.size)
        assertEquals(45, ShockFav.VARIANTS.map { it.code }.toSet().size)
        assertEquals(47, ScalpModels.ALL.size)
        assertEquals(34, ScalpModels.SF_MODELS.size)
        assertTrue(ShockFav.isShockFav("sf07") && ShockFav.isShockFav("sf20f") && !ShockFav.isShockFav("fcride-h300"))
    }

    @Test fun shockEntersAtAskCappedAndSettles() {
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val t0 = close - 600_000L
        warm(book, t0, 100_000.0)
        // +10 bp in 5 s; book lags (YES ask 0.41 while fair is far higher)
        for (i in 0..4) book.tickSpot("BTC", 100_000.0, t0 + i * 1000L)
        val out = book.onShockFavQuote(q(t0 + 5_000L, 100_100.0, 0.41, 0.39), true).filter { ShockFav.byVariantId(it.variantId)?.kind == ShockFav.Kind.SHOCK_FAV }
        assertTrue(out.isNotEmpty())
        out.forEach { assertEquals(ScalpState.PENDING_ENTRY, it.state); assertEquals("YES", it.side); assertTrue(ScalpModels.modelOf(it) in ScalpModels.SF_MODELS) }
        // 0.3.58: no fill before 2 s; then fill at the ask of that later quote, capped at its size
        assertTrue(book.onShockFavQuote(q(t0 + 6_000L, 100_100.0, 0.41, 0.39), true).none { it.state == ScalpState.OPEN })
        val filled = book.onShockFavQuote(q(t0 + 7_100L, 100_100.0, 0.47, 0.45, size = 7.0), true).filter { it.state == ScalpState.OPEN && out.any { o -> o.id == it.id } }
        assertEquals(out.size, filled.size)
        filled.forEach { assertEquals(7, it.contracts); assertEquals(0.47, it.entryPrice!!, 1e-9) }
        val settled = book.settle(tk, "yes", close + 5_000L)
        assertTrue(settled.all { (it.state == ScalpState.CLOSED && it.netUsd != null) || it.state == ScalpState.NO_FILL })
    }

    @Test fun lateFavOncePerMarketAndDisabledMeansNothing() {
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val t = close - 40_000L
        warm(book, t, 100_400.0)
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
        assertEquals(0.5, ShockFav.fairYes(101.0, 100.0, 3e-5, 300.0, kotlin.math.ln(1.01)), 1e-9)
        assertEquals(0.5, ShockFav.brtiFairYes(100.0, 100.0, 3e-5, 30.0, List(30) { 100.0 }), 1e-6)
    }

    @Test fun bankrollCapsEntries() {
        val book = ScalpBook(bankrollUsd = { 3.0 })
        val t0 = close - 600_000L
        warm(book, t0, 100_000.0)
        for (i in 0..4) book.tickSpot("BTC", 100_000.0, t0 + i * 1000L)
        val out = book.onShockFavQuote(q(t0 + 5_000L, 100_100.0, 0.41, 0.39), true)
        assertTrue(out.size <= 0) // one reserved 10-ct clip @41¢ costs > $3
    }
}
