package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.14 regression: Simple-dip sf41–sf43 never traded. Their rule needs 4 min of the window's own price history, but the
 * window-start rule only allowed entries in the first sfOpenMin (2–5) minutes — sf41 2, sf42 3, sf43 4 → impossible.
 */
class SimpleDipWindowFixTest {
    private val historyMin = ShockFav.SIMPLE_MIN_HISTORY_MS / 60_000.0

    @Test fun everySimpleDipHasTimeToEnterAfterItsHistory() {
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0), listOf("sf41", "sf42", "sf43", "sf44").map { ScalpV2.sfOpenMin(it) }) // the old limits
        for (code in listOf("sf41", "sf42", "sf43", "sf44")) {
            val p = ShockFav.v(code)
            val open = ShockFav.entryOpenMin(p, ScalpV2.sfOpenMin(code))
            assertTrue("$code open $open", open >= historyMin + 2.0 - 1e-9)
        }
        // v2 copies and their tweak / size copies get the same allowance.
        for (s in listOf("v2-sf41", "v2-sf42", "v2-sf43").map { ScalpV2.spec(it) } + ScalpV2.EXTRA_SPECS)
            assertTrue("${s.id} ${ScalpV2.entryOpenMin(s)}", ScalpV2.entryOpenMin(s) > historyMin + 1e-9)
        // Other strategies keep their window-start limit unchanged.
        assertEquals(ScalpV2.sfOpenMin("sf51"), ShockFav.entryOpenMin(ShockFav.v("sf51"), ScalpV2.sfOpenMin("sf51")), 0.0)
        assertEquals(ScalpV2.spec("v2-sf51").openMin, ScalpV2.entryOpenMin(ScalpV2.spec("v2-sf51")), 0.0)
    }

    @Test fun sf41SignalsADipAfterFourMinutes() {
        val close = 1_792_342_800_000L
        val start = close - 900_000L
        val tk = "KXBTC15M-26OCT181300-00"
        val book = ScalpBook(liveExtras = false, bankrollUsd = { Double.POSITIVE_INFINITY })
        val rnd = java.util.Random(7)
        var spot = 100_000.0
        for (k in 400 downTo 1) { spot += (rnd.nextDouble() - 0.5) * 20; book.tickSpot("BTC", spot, start - k * 1000L) }
        val entries = ArrayList<ScalpTrade>()
        var t = start
        while (t < start + 400_000L) {
            spot += (rnd.nextDouble() - 0.5) * 20
            book.tickSpot("BTC", spot, t)
            val mid = if (t >= start + 300_000L) 0.42 else 0.50 // 8¢ dip after 5 min (≥ sf41's 5¢)
            val q = ScalpRule.Quote(ticker = tk, nowMs = t, closeMs = close, bookAtMs = t, yesBid = mid - 0.01, yesBidSize = 50.0,
                yesAsk = mid + 0.01, yesAskSize = 50.0, spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5)
            entries += book.onShockFavQuote(q, true).filter { it.variantId == "sf41" }
            t += 1_000L
        }
        val first = entries.firstOrNull()
        assertTrue("sf41 must signal an entry after its 4-min history", first != null)
        assertTrue("entry after the history and within the new 6-min limit", first!!.signalAtMs - start in 240_000L..360_000L)
    }
}
