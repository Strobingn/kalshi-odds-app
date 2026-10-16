package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0359Test {
    private val close = 1_791_850_500_000L
    private fun q(tk: String, now: Long, spot: Double, ya: Double, yb: Double, ybq: Double = 50.0, yaq: Double = 50.0) = ScalpRule.Quote(
        ticker = tk, nowMs = now, closeMs = close, bookAtMs = now, yesBid = yb, yesBidSize = ybq, yesAsk = ya, yesAskSize = yaq,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5)
    private fun warm(b: ScalpBook, coin: String, t: Long, px: Double) { for (k in 300 downTo 1) b.tickSpot(coin, px * (1 + (if (k % 2 == 0) 5e-5 else -5e-5)), t - k * 1000L) }

    @org.junit.Ignore("Kalshi Scalper 1.0.1: sf entries are window-start only now (late-fav / mid-window entries superseded); covered by ScalperRulesTest")
    @Test fun unlimitedBankrollNeverSkips() {
        val book = ScalpBook(bankrollUsd = { Double.POSITIVE_INFINITY })
        val t = close - 40_000L; val tk = "KXBTC15M-26OCT122015-15"
        warm(book, "BTC", t, 100_400.0)
        assertTrue(book.onShockFavQuote(q(tk, t, 100_400.0, 0.50, 0.48), true).isNotEmpty())
    }

    @Test fun ethSolNeverEnterButBtcDoes() {
        val book = ScalpBook(bankrollUsd = { Double.POSITIVE_INFINITY })
        val t = close - 40_000L
        warm(book, "ETH", t, 100_400.0)
        assertTrue(book.onShockFavQuote(q("KXETH15M-26OCT122015-15", t, 100_400.0, 0.50, 0.48), true).isEmpty())
    }

    @Test fun survivorsSortFirstAndLabels() {
        val order = ScalpModels.CARD_ORDER
        val firstNoEdge = order.indexOfFirst { ScalpModels.noEdge2s(it) }
        assertTrue(order.drop(firstNoEdge).all { ScalpModels.noEdge2s(it) })
        assertFalse(ScalpModels.noEdge2s(ScalpModels.Model.SF19))
        assertTrue(ScalpModels.noEdge2s(ScalpModels.Model.SF01))
        assertEquals(order.toSet(), ScalpModels.ALL.toSet())
    }

    @Test fun laggardBuysBtcWhenEthSolMoved() {
        val rings = mapOf("BTC" to ShockFav.SpotRing(), "ETH" to ShockFav.SpotRing(), "SOL" to ShockFav.SpotRing())
        for (s in 0L..20L) { rings["BTC"]!!.put(s, 100.0); rings["ETH"]!!.put(s, if (s >= 15) 100.2 else 100.0); rings["SOL"]!!.put(s, if (s >= 15) 100.3 else 100.0) }
        val p = ShockFav.VARIANTS.first { it.kind == ShockFav.Kind.LAGGARD }
        assertEquals("YES", ShockFav.laggardSide(p, "BTC", rings, 20L, 600.0))
        assertEquals(null, ShockFav.laggardSide(p, "BTC", rings, 20L, 30.0))
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.1: sf entries are window-start only now (late-fav / mid-window entries superseded); covered by ScalperRulesTest")
    @Test fun makerFadeFillsOnlyOnTradeThrough() {
        val book = ScalpBook(bankrollUsd = { Double.POSITIVE_INFINITY })
        val tk = "KXBTC15M-26OCT122015-15"; val t0 = close - 600_000L
        warm(book, "BTC", t0, 100_000.0)
        for (i in 0..4) book.tickSpot("BTC", 100_000.0, t0 + i * 1000L)
        val sig = book.onShockFavQuote(q(tk, t0 + 5_000L, 100_100.0, 0.61, 0.59), true).filter { ShockFav.byVariantId(it.variantId)?.kind == ShockFav.Kind.FADE_MAKER }
        assertEquals(1, sig.size); assertEquals("NO", sig[0].side)
        val posted = book.onShockFavQuote(q(tk, t0 + 7_100L, 100_100.0, 0.61, 0.59), true).first { it.id == sig[0].id }
        assertEquals(0.39, posted.restingLimit!!, 1e-9) // NO bid = 1 − YES ask
        // NO ask 0.39 (= 1 − YES bid 0.61) equals the limit: touch, not trade-through → no fill
        assertTrue(book.onShockFavQuote(q(tk, t0 + 8_000L, 100_100.0, 0.62, 0.61), true).none { it.id == sig[0].id && it.state == ScalpState.OPEN })
        val filled = book.onShockFavQuote(q(tk, t0 + 9_000L, 100_100.0, 0.64, 0.62), true).first { it.id == sig[0].id }
        assertEquals(ScalpState.OPEN, filled.state); assertEquals(0.0, filled.entryFeeUsd, 1e-12)
    }
}
