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
        val cheap = ScalpTrade(
            id = "cheap", ticker = ticker, side = "NO", state = ScalpState.PENDING_ENTRY,
            signalAtMs = start, signalAsk = 0.032, fairAtSignal = 0.10,
            ruleVersion = "${ScalpRule.VERSION}|sf42|P",
            note = "sf42 simple dip"
        )
        val refused = ScalpBook(
            InMemoryScalpPersistence().also { it.seed(listOf(cheap)) },
            liveExtras = true,
            bankrollUsd = { 20_000.0 }
        )
        refused.onShockFavQuote(book(start + 2_000L, touch = 4.0), true)
        val blocked = refused.snapshot().first { it.id == "cheap" }
        assertEquals(blocked.note, ScalpState.NO_FILL, blocked.state)
        assertTrue(blocked.note, blocked.note.contains("15¢"))

        val pending = ScalpTrade(
            id = "tenth", ticker = ticker, side = "NO", state = ScalpState.PENDING_ENTRY,
            signalAtMs = start, signalAsk = 0.152, fairAtSignal = 0.20,
            ruleVersion = "${ScalpRule.VERSION}|sf42|P",
            note = "sf42 simple dip"
        )
        val seeded = ScalpBook(
            InMemoryScalpPersistence().also { it.seed(listOf(pending)) },
            liveExtras = true,
            bankrollUsd = { 20_000.0 }
        )
        // DOWN ask = 1 − 84.8¢ = 15.2¢. A 4-lot touch does not shrink the paper clip.
        val q = book(start + 2_000L, touch = 4.0).copy(yesBid = 0.848, yesBidSize = 4.0)
        seeded.onShockFavQuote(q, true)
        val row = seeded.snapshot().first { it.id == "tenth" }
        assertEquals(row.note, ScalpState.OPEN, row.state)
        assertTrue(row.contracts >= PaperClip.MIN)
        assertEquals(0.152, row.entryPrice!!, 1e-9)
        assertTrue(row.entryFeeUsd > 0.0)
    }

    @Test fun makerFillsWhenTheAskTradesAtTheBidNotACentThrough() {
        val start = close - 400_000L
        val pending = ScalpTrade(
            id = "maker", ticker = ticker, side = "NO", state = ScalpState.PENDING_ENTRY,
            signalAtMs = start, signalAsk = 0.154, fairAtSignal = 0.20,
            restingLimit = 0.154, restingSinceMs = start,
            ruleVersion = "${ScalpRule.VERSION}|sf28|P",
            note = "maker bid 15.4¢"
        )
        val book = ScalpBook(
            InMemoryScalpPersistence().also { it.seed(listOf(pending)) },
            liveExtras = true,
            bankrollUsd = { 20_000.0 }
        )
        // DOWN ask = 1 − 84.3¢ = 15.7¢, still above the 15.4¢ bid: no fill yet.
        book.onShockFavQuote(bookQuote(start + 2_000L, yesBid = 0.843), true)
        assertEquals(ScalpState.PENDING_ENTRY, book.snapshot().first { it.id == "maker" }.state)
        // Ask trades AT 15.4¢ (YES bid 84.6¢). That is a fill, not a one-cent trade-through.
        book.onShockFavQuote(bookQuote(start + 3_000L, yesBid = 0.846), true)
        val filled = book.snapshot().first { it.id == "maker" }
        assertEquals(filled.note, ScalpState.OPEN, filled.state)
        assertEquals(0.154, filled.entryPrice!!, 1e-9)
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
        assertTrue(opened.all { it.contracts >= PaperClip.MIN })
    }

    private fun bookQuote(at: Long, yesBid: Double) = book(at).copy(yesBid = yesBid, yesBidSize = 20.0)
}
