package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live fair-gap quote may stack paper clips. The window loss gate still
 * pauses a side that is already bleeding. Fills stay on the displayed book.
 */
class PaperReentryVolumeTest {
    private val ticker = "KXBTC15M-26OCT181315-00"

    @Test fun flatSideReentersImmediatelyAndAnOpenSideWaitsForTheGap() {
        assertTrue(PaperReentry.allowed(0, 1_000L, 1_050L))
        assertEquals("paper re-entry gap", PaperReentry.refusal(2, 1_000L, 1_000L + PaperReentry.GAP_MS - 1))
        assertNull(PaperReentry.refusal(2, 1_000L, 1_000L + PaperReentry.GAP_MS))
        assertEquals("open clips full on this side", PaperReentry.refusal(PaperReentry.MAX_OPEN_PER_SIDE, null, 5_000L))
        assertEquals("open clips full on this side", PaperReentry.refusal(3, null, 5_000L, sideCap = 3))
    }

    @Test fun oneWindowStacksManyWinningRoundTripsWhileTheSignalHolds() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 800_000L
        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        var at = start
        while (at < start + 8_000L) {
            book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
            at += PaperReentry.GAP_MS
        }
        val lift = at
        while (at < lift + 6_000L) {
            book.onQuote(quote(at, yesBid = 0.48, yesAsk = 0.49, spot = 101_000.0), true)
            at += PaperReentry.GAP_MS
        }
        val yes = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL
        }
        val wins = yes.count { it.state == ScalpState.CLOSED && (it.netUsd ?: 0.0) > 0.0 }
        assertTrue("YES clips ${yes.size}", yes.size >= 25)
        assertTrue("winning round trips $wins", wins >= 15)
        yes.filter { it.state == ScalpState.CLOSED && it.entryPrice != null }.forEach { t ->
            assertEquals(ScalpRule.orderFee(t.contracts, t.entryPrice!!), t.entryFeeUsd, 1e-9)
            assertTrue(t.entryPrice!! <= 0.35 + ScalpRule.SLIPPAGE + 1e-9)
        }
        assertTrue(book.openCostUsd() <= 100_000.0 + 1e-6)
    }

    @Test fun aLosingSidePausesAndAFreshSideCanStillOpen() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 800_000L
        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        var at = start
        while (at < start + 6_000L) {
            book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
            at += PaperReentry.GAP_MS
        }
        // Wide spread: no new signal, and the ask still fills clips already working.
        val drain = at + 4_000L
        while (at < drain) {
            book.onQuote(quote(at, yesBid = 0.30, yesAsk = 0.35, spot = 101_000.0), true)
            at += PaperReentry.GAP_MS
        }
        // Stop the open clips. Ask is through the 35¢ limit, so nothing new fills the crash.
        repeat(4) {
            book.onQuote(quote(at, yesBid = 0.20, yesAsk = 0.37, spot = 101_000.0), true)
            at += PaperReentry.GAP_MS
        }
        val yes = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL
        }
        val losers = yes.count { it.state == ScalpState.CLOSED && (it.netUsd ?: 0.0) < 0.0 }
        assertTrue("losers $losers of ${yes.size}", losers >= WindowLossGate.DEFAULT_ADVERSE_FILLS)
        val before = yes.size
        repeat(12) {
            book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
            at += PaperReentry.GAP_MS
        }
        val after = book.snapshot().count {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL
        }
        assertEquals(book.gateLine(KeptScalpers.FAIR_ID), before, after)
        assertTrue(book.gateLine(KeptScalpers.FAIR_ID), book.gateLine(KeptScalpers.FAIR_ID)!!.contains("UP paused"))
        book.onQuote(quote(at, yesBid = 0.64, yesAsk = 0.65, spot = 99_000.0), true)
        book.onQuote(quote(at + PaperReentry.GAP_MS, yesBid = 0.64, yesAsk = 0.65, spot = 99_000.0), true)
        val no = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "NO" && it.state != ScalpState.NO_FILL
        }
        assertTrue(no.joinToString { "${it.state} ${it.note}" }, no.isNotEmpty())
    }

    private fun quote(now: Long, yesBid: Double, yesAsk: Double, spot: Double) = ScalpRule.Quote(
        ticker = ticker, nowMs = now, closeMs = ScalpTicker.closeMs(ticker)!!, bookAtMs = now,
        yesBid = yesBid, yesBidSize = 400.0, yesAsk = yesAsk, yesAskSize = 400.0,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
