package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One BTC 15-minute window. The book is stale for the first 9 minutes (Kalshi
 * outage) and fresh after that, with a 5¢ simple dip on the recovered book.
 * Spots are present every second, so volatility is not the thing that is missing.
 *
 * sf41 may enter only in the first 6 minutes and sf42 only in the first 7
 * (ScalpV2.sfOpenMin + the 4-minute dip history, enforced by the old sfEnter
 * cutoff). A dip that shows up at minute 9 is after both cutoffs.
 */
class WindowOutageEntryTest {
    private val ticker = "KXBTC15M-26OCT181315-00"

    @Test fun recoveredDipAfterTheOutageStillEnters() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 900_000L
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val recoverAt = start + 9 * 60_000L
        var t = start
        while (t < close - 60_000L) {
            val fresh = t >= recoverAt
            val ask = if (fresh) 0.351 else 0.420
            val bid = ask - 0.003
            book.onShockFavQuote(
                ScalpRule.Quote(
                    ticker = ticker, nowMs = t, closeMs = close,
                    bookAtMs = if (fresh) t else t - 30_000L,
                    yesBid = bid, yesBidSize = 40.0, yesAsk = ask, yesAskSize = 40.0,
                    spot = 100_000.0, strike = 100_000.0, sigmaPerSec = 3e-5
                ),
                enabled = true
            )
            t += 1_000L
        }
        val sf42 = ShockFav.v("sf42")
        val cutoff42 = ShockFav.entryOpenMin(sf42, book.sfOpenMin("sf42"))
        assertEquals("YES", ShockFav.simpleDipSide(sf42, 0.351, 0.420, null, 0.0))
        val rows = book.snapshot().filter { it.variantId == "sf41" || it.variantId == "sf42" }
        val entered = rows.filter { it.state == ScalpState.PENDING_ENTRY || it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.CLOSED }
        assertTrue(
            "sf42 entry cutoff is $cutoff42 min after the open (sfOpenMin ${book.sfOpenMin("sf42")} + 4 min history). " +
                "The dip is at minute 9, after the outage. Rows: " +
                rows.joinToString { "${it.variantId} ${it.state}" },
            entered.isNotEmpty()
        )
    }
}
