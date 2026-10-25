package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One fair-gap scalper, one 15-minute window. Cash and depth can fund many clips.
 * UP and DOWN can both be open. A winning side keeps re-entering. Selling the
 * exit into the next dip is a loss, and that side stops after the losing streak.
 * Each order's fee is ceiled once.
 */
class UnlimitedWindowEntriesTest {
    private val ticker = "KXBTC15M-26OCT181315-00"

    @Test fun winnersKeepReenteringAndTheOtherSideStillOpens() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 800_000L
        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        repeat(8) { i ->
            book.onQuote(quote(start + i * 3_000L, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
        }
        val steady = book.snapshot().count { it.variantId == KeptScalpers.FAIR_ID && it.state != ScalpState.NO_FILL }
        assertEquals(1, steady)
        // Sell the open clip while the bid is still through the target, then buy again.
        var at = start + 24_000L
        book.onQuote(quote(at, yesBid = 0.48, yesAsk = 0.49, spot = 101_000.0), true)
        at += 4_000L
        book.onQuote(quote(at, yesBid = 0.48, yesAsk = 0.49, spot = 101_000.0), true)
        repeat(12) {
            at += 4_000L
            book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
            at += 4_000L
            book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
            at += 4_000L
            book.onQuote(quote(at, yesBid = 0.48, yesAsk = 0.49, spot = 101_000.0), true)
            at += 4_000L
            book.onQuote(quote(at, yesBid = 0.48, yesAsk = 0.49, spot = 101_000.0), true)
        }
        val yes = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL
        }
        assertTrue("YES clips ${yes.size}", yes.size >= 12)
        assertTrue(yes.count { it.state == ScalpState.CLOSED && (it.netUsd ?: 0.0) > 0.0 } >= 8)
        yes.filter { it.entryPrice != null && it.contracts > 0 }.forEach { t ->
            assertEquals(ScalpRule.orderFee(t.contracts, t.entryPrice!!), t.entryFeeUsd, 1e-9)
        }
        val flip = at + 4_000L
        book.onQuote(quote(flip, yesBid = 0.64, yesAsk = 0.65, spot = 99_000.0), true)
        book.onQuote(quote(flip + 4_000L, yesBid = 0.64, yesAsk = 0.65, spot = 99_000.0), true)
        val no = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "NO" && it.state != ScalpState.NO_FILL
        }
        assertTrue(no.joinToString { it.state.name }, no.isNotEmpty())
        assertTrue(book.openCostUsd() <= 100_000.0 + 1e-6)
    }

    @Test fun sellingTheExitIntoTheDipStopsAfterTheLosingStreak() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 800_000L
        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        book.onQuote(quote(start, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
        book.onQuote(quote(start + 4_000L, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
        var at = start + 8_000L
        repeat(8) {
            book.onQuote(quote(at, yesBid = 0.48, yesAsk = 0.49, spot = 100_000.0), true)
            at += 4_000L
            book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
            at += 4_000L
            book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
            at += 4_000L
        }
        val yes = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL
        }
        assertTrue("YES clips ${yes.size}", yes.size < 8)
        val losers = yes.count { it.state == ScalpState.CLOSED && (it.netUsd ?: 0.0) < 0.0 }
        assertTrue("losers $losers", losers >= WindowLossGate.DEFAULT_ADVERSE_FILLS)
        val before = yes.size
        book.onQuote(quote(at, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
        val after = book.snapshot().count {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL
        }
        assertEquals(before, after)
        assertTrue(book.gateLine(KeptScalpers.FAIR_ID)!!.contains("UP paused"))
    }

    private fun quote(now: Long, yesBid: Double, yesAsk: Double, spot: Double) = ScalpRule.Quote(
        ticker = ticker, nowMs = now, closeMs = ScalpTicker.closeMs(ticker)!!, bookAtMs = now,
        yesBid = yesBid, yesBidSize = 400.0, yesAsk = yesAsk, yesAskSize = 400.0,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
