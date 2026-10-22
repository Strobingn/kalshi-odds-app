package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0369Test {
    /** sf rows are exited only by their own lane (TP/stop/60 s), never by the generic profit-target / turn-down rules. */
    @org.junit.Ignore("Kalshi Scalper 1.0.3: pins pre-1.0.3 entries/exits of a strategy now retuned toward the fair-gap rule (24–42¢, BRTI gap 10–21¢ after fee, no stop/trail, ≈4.5 min hold); covered by ReleaseGate103FairGapTest") @Test fun sfExitsComeFromTheirOwnTakeProfit() {
        val closed = HashMap<String, ScalpTrade>()
        for (seed in 1..6) {
            val close = 1_791_850_500_000L + seed * 900_000L
            val tk = "KXBTC15M-26OCT12${2000 + seed}-15"
            val book = ScalpBook(liveExtras = true, bankrollUsd = { Double.POSITIVE_INFINITY })
            val start = close - 900_000L
            for (k in 300 downTo 1) book.tickSpot("BTC", 100_000.0, start - k * 1000L)
            val rnd = java.util.Random(seed.toLong())
            var mid = 0.5; var spot = 100_000.0; var t = start
            while (t < close + 2_000L) {
                mid = (mid + (rnd.nextDouble() - 0.5) * 0.06).coerceIn(0.06, 0.94)
                spot += (rnd.nextDouble() - 0.5) * 40
                book.tickSpot("BTC", spot, t)
                val q = ScalpRule.Quote(ticker = tk, nowMs = t, closeMs = close, bookAtMs = t, yesBid = mid - 0.01, yesBidSize = 50.0,
                    yesAsk = mid + 0.01, yesAskSize = 50.0, spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5)
                (book.onQuote(q, true) + book.onShockFavQuote(q, true)).forEach { if (it.state == ScalpState.CLOSED) closed[it.id] = it }
                t += 1_000L
            }
        }
        val simple = closed.values.filter { it.variantId in setOf("sf41", "sf42", "sf43", "sf44") }
        assertTrue("simple-dip closes ${simple.size}", simple.isNotEmpty())
        val generic = setOf("profit target hit", "fair-value exit: bid crossed fair after the exit fee", "turn-down: fair fell below entry")
        simple.forEach { assertTrue("${it.variantId}: ${it.exitReason}", it.exitReason !in generic) }
        val sf = closed.values.filter { ShockFav.isShockFav(it.variantId) }
        sf.forEach { assertTrue("${it.variantId}: ${it.exitReason}", it.exitReason !in generic) }
        // TP is now variant-specific: an 8¢ TP exit label never appears on a 15¢-TP variant and vice versa
        simple.filter { it.exitReason?.startsWith("take profit") == true }.forEach {
            assertTrue(it.exitReason!!.contains("+${Math.round(ShockFav.v(it.variantId).tp * 100)}¢"))
        }
    }
}

class GreenSize0369Test {
    @Test fun greenScalpersOnlyAfterEnoughProfitableTrades() {
        val store = com.dirk.kalshiodds.decision.InMemoryScalpPersistence()
        val book = ScalpBook(store = store, liveExtras = false, bankrollUsd = { Double.POSITIVE_INFINITY })
        assertTrue(!book.isGreen("sf41"))
        assertTrue(ScalpBook.GREEN_CLIP_DEFAULT in 25..50)
    }
}
