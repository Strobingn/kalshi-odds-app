package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One fair-gap scalper, one 15-minute window, the same trigger on every tick.
 * Cash and displayed depth are enough for well over 50 clips. UP and DOWN
 * can both be open. Each order's fee is ceiled once.
 */
class UnlimitedWindowEntriesTest {
    private val ticker = "KXBTC15M-26OCT181315-00"

    @Test fun fiftyEntriesThenTheOtherSide() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 800_000L
        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        repeat(60) { i ->
            book.onQuote(quote(start + i * 4_000L, yesBid = 0.34, yesAsk = 0.35, spot = 101_000.0), true)
        }
        val yes = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" &&
                it.state != ScalpState.NO_FILL
        }
        assertTrue("YES clips ${yes.size}", yes.size >= 50)
        yes.filter { it.entryPrice != null && it.contracts > 0 }.forEach { t ->
            assertEquals(ScalpRule.orderFee(t.contracts, t.entryPrice!!), t.entryFeeUsd, 1e-9)
        }
        val flip = start + 60 * 4_000L
        repeat(4) { i ->
            book.onQuote(quote(flip + i * 4_000L, yesBid = 0.64, yesAsk = 0.65, spot = 99_000.0), true)
        }
        val no = book.snapshot().filter {
            it.variantId == KeptScalpers.FAIR_ID && it.side == "NO" &&
                it.state != ScalpState.NO_FILL
        }
        assertTrue(no.joinToString { it.state.name }, no.isNotEmpty())
        assertTrue(book.openCostUsd() <= 100_000.0 + 1e-6)
    }

    private fun quote(now: Long, yesBid: Double, yesAsk: Double, spot: Double) = ScalpRule.Quote(
        ticker = ticker, nowMs = now, closeMs = ScalpTicker.closeMs(ticker)!!, bookAtMs = now,
        yesBid = yesBid, yesBidSize = 400.0, yesAsk = yesAsk, yesAskSize = 400.0,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
