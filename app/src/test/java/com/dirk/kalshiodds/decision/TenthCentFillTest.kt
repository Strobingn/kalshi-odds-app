package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live BTC books are tenth-cent. UP 96.8/96.6/96.5 and DOWN 2.9/2.5/2.2 is a
 * 3.2¢ DOWN ask (1 − 96.8¢) with real size. A taker must lift that ask up to
 * its depth. A maker must fill when the ask trades at or through the bid,
 * not only a full cent through it.
 */
class TenthCentFillTest {
    private val ticker = "KXBTC15M-26OCT181300-00"
    private val close = ScalpTicker.closeMs(ticker)!!

    /** YES bids 96.8/96.6/96.5, NO bids 2.9/2.5/2.2. Sizes are displayed contracts. */
    private fun book(at: Long, touch: Double = 40.0) = ScalpRule.Quote(
        ticker = ticker,
        nowMs = at,
        closeMs = close,
        bookAtMs = at,
        yesBid = 0.968,
        yesBidSize = touch,
        yesAsk = 0.971, // 1 − 2.9¢
        yesAskSize = 18.0,
        spot = 100_000.0,
        strike = 100_000.0,
        sigmaPerSec = 3e-5,
        yesBidsDesc = listOf(0.968 to touch, 0.966 to 30.0, 0.965 to 50.0),
        noBidsDesc = listOf(0.029 to 18.0, 0.025 to 22.0, 0.022 to 40.0)
    )

    @Test fun takerLiftsTheDisplayedDownAskUpToDepth() {
        val start = close - 900_000L
        val scalp = ScalpBook(bankrollUsd = { 20_000.0 }, idFactory = { "tenth" })
        val pending = ScalpTrade(
            id = "tenth", ticker = ticker, side = "NO", state = ScalpState.PENDING_ENTRY,
            signalAtMs = start, signalAsk = 0.032, fairAtSignal = 0.10,
            ruleVersion = "${ScalpRule.VERSION}|sf42|P",
            note = "sf42 simple dip"
        )
        val seeded = ScalpBook(
            InMemoryScalpPersistence().also { it.seed(listOf(pending)) },
            liveExtras = true,
            bankrollUsd = { 20_000.0 }
        )
        // 2s later the same tenth-cent book is still offered. Touch size 4 is less than the 10-lot clip.
        val q = book(start + 2_000L, touch = 4.0)
        seeded.onShockFavQuote(q, true)
        val row = seeded.snapshot().first { it.id == "tenth" }
        assertEquals(row.note, ScalpState.OPEN, row.state)
        assertEquals(4, row.contracts)
        assertEquals(0.032, row.entryPrice!!, 1e-9)
        assertTrue(row.entryFeeUsd > 0.0)
        scalp.hashCode()
    }

    @Test fun makerFillsWhenTheAskTradesAtTheBidNotACentThrough() {
        val start = close - 400_000L
        val pending = ScalpTrade(
            id = "maker", ticker = ticker, side = "NO", state = ScalpState.PENDING_ENTRY,
            signalAtMs = start, signalAsk = 0.029, fairAtSignal = 0.05,
            restingLimit = 0.029, restingSinceMs = start,
            ruleVersion = "${ScalpRule.VERSION}|sf28|P",
            note = "maker bid 2.9¢"
        )
        val book = ScalpBook(
            InMemoryScalpPersistence().also { it.seed(listOf(pending)) },
            liveExtras = true,
            bankrollUsd = { 20_000.0 }
        )
        // DOWN ask = 1 − 96.8¢ = 3.2¢, still above the 2.9¢ bid: no fill yet.
        book.onShockFavQuote(bookQuote(start + 2_000L, yesBid = 0.968), true)
        assertEquals(ScalpState.PENDING_ENTRY, book.snapshot().first { it.id == "maker" }.state)
        // Ask trades AT 2.9¢ (YES bid 97.1¢). That is a fill, not a one-cent trade-through.
        book.onShockFavQuote(bookQuote(start + 3_000L, yesBid = 0.971), true)
        val filled = book.snapshot().first { it.id == "maker" }
        assertEquals(filled.note, ScalpState.OPEN, filled.state)
        assertEquals(0.029, filled.entryPrice!!, 1e-9)
        assertTrue(filled.contracts > 0)
    }

    @Test fun keptSimpleDipOnAThreeCentAskIsNotCancelledAsNoFill() {
        val start = close - 900_000L
        val scalp = ScalpBook(liveExtras = true, bankrollUsd = { 20_000.0 })
        repeat(400) { scalp.tickSpot("BTC", 100_000.0, start - (400 - it) * 1000L) }
        var t = start
        while (t < start + 250_000L) {
            scalp.tickSpot("BTC", 100_000.0, t)
            // Tenth-cent YES ask: 42.0¢ for four minutes, then 35.1¢ (a 6.9¢ dip) with only 4 contracts at the touch.
            val q = if (t < start + 240_000L) {
                ScalpRule.Quote(ticker, t, close, t, yesBid = 0.418, yesBidSize = 40.0, yesAsk = 0.420, yesAskSize = 40.0, spot = 100_000.0, strike = 100_000.0, sigmaPerSec = 3e-5)
            } else {
                ScalpRule.Quote(ticker, t, close, t, yesBid = 0.348, yesBidSize = 4.0, yesAsk = 0.351, yesAskSize = 4.0, spot = 100_000.0, strike = 100_000.0, sigmaPerSec = 3e-5)
            }
            scalp.onShockFavQuote(q, true)
            t += 1_000L
        }
        val rows = scalp.snapshot().filter { it.variantId == "sf42" }
        val opened = rows.filter { it.state == ScalpState.OPEN || it.state == ScalpState.CLOSED || it.state == ScalpState.PENDING_EXIT }
        assertTrue(rows.joinToString(" | ") { "${it.state} ${it.note}" }, opened.size >= 4)
        assertTrue(opened.all { it.contracts == 4 })
    }

    private fun bookQuote(at: Long, yesBid: Double) = book(at).copy(yesBid = yesBid, yesBidSize = 20.0)
}
