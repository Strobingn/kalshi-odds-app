package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.worker.SyncWorker
import org.junit.Test

class ReleaseGate0366Test {
    private val close = 1_791_850_500_000L
    private fun q(tk: String, now: Long, spot: Double, ya: Double, yb: Double) = ScalpRule.Quote(
        ticker = tk, nowMs = now, closeMs = close, bookAtMs = now, yesBid = yb, yesBidSize = 50.0, yesAsk = ya, yesAskSize = 50.0,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5)

    /** The live app book (liveExtras = true, as AppContainer builds it) must keep producing scalps without throwing. */
    @Test fun liveBookRunsAFullWindow() {
        val book = ScalpBook(liveExtras = true, bankrollUsd = { Double.POSITIVE_INFINITY })
        val tk = "KXBTC15M-26OCT122015-15"
        val start = close - 900_000L
        for (k in 300 downTo 1) book.tickSpot("BTC", 100_000.0 * (1 + (if (k % 2 == 0) 5e-5 else -5e-5)), start - k * 1000L)
        val rnd = java.util.Random(7)
        var mid = 0.5; var spot = 100_000.0
        var t = start
        while (t < close + 2_000L) {
            mid = (mid + (rnd.nextDouble() - 0.5) * 0.04).coerceIn(0.06, 0.94)
            spot += (rnd.nextDouble() - 0.5) * 40
            book.tickSpot("BTC", spot, t)
            val qq = q(tk, t, spot, mid + 0.01, mid - 0.01)
            book.onQuote(qq, true)
            book.onShockFavQuote(qq, true)
            t += 1_000L
        }
        book.settle(tk, "yes", close + 5_000L)
        val all = book.all.value
        assertTrue("trades: ${all.size}", all.isNotEmpty())
        assertTrue(all.any { it.state == ScalpState.CLOSED })
        assertTrue(all.any { ScalpV2.isV2(it.variantId) })
    }

    @Test fun backlogDrainOutpacesScalpers() {
        // ≥ 6,000 rows per run (12 × 500) plus the newest 300 every run — 0.3.65 sent only 500 per run.
        assertTrue(SyncWorker.SCALP_CHUNKS * SyncWorker.SCALP_BATCH >= 6_000)
        assertTrue(SyncWorker.SCALP_NEWEST >= 300)
    }

    @Test fun v2ModelLookupWithoutCopy() {
        val t = com.dirk.kalshiodds.decision.ScalpTrade(id = "x", ticker = "KXBTC15M-26OCT122015-15", side = "YES", state = ScalpState.CLOSED,
            signalAtMs = 0L, signalAsk = 0.4, fairAtSignal = 0.5, ruleVersion = "${ScalpRule.VERSION}|v2-sf30|P")
        assertEquals(ScalpModels.v2Of(ScalpModels.Model.SF30), ScalpModels.modelOf(t))
    }
}
