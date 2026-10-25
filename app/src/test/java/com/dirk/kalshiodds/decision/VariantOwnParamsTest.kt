package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.27 window KXBTC15M-26OCT220945-45: variant families printed identical
 * trades, simple dips closed with 0 wins, and a 6¢ stop sold at 1¢ after a
 * 270s fair-gap hold. These tests pin the three fixes.
 */
class VariantOwnParamsTest {
    private val ticker = "KXBTC15M-26OCT220945-45"

    @Test fun steadyDipStacksClipsAndALaterDipStacksMore() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 600_000L
        val book = ScalpBook(liveExtras = true, bankrollUsd = { 100_000.0 })
        var t = start
        val flatUntil = start + 250_000L
        while (t <= flatUntil) {
            book.onShockFavQuote(q(t, yesBid = 0.49, yesAsk = 0.50), true)
            t += 1_000L
        }
        val dipUntil = flatUntil + 8_000L
        while (t <= dipUntil) {
            book.onShockFavQuote(q(t, yesBid = 0.39, yesAsk = 0.40), true)
            t += 1_000L
        }
        val sf42 = book.snapshot().filter { it.variantId == "sf42" && it.state != ScalpState.NO_FILL }
        assertTrue(sf42.joinToString { "${it.state} ${it.contracts}" }, sf42.size >= 4)
        while (t <= dipUntil + 30_000L) {
            book.onShockFavQuote(q(t, yesBid = 0.49, yesAsk = 0.50), true)
            t += 1_000L
        }
        val secondDip = t + 5_000L
        while (t <= secondDip) {
            book.onShockFavQuote(q(t, yesBid = 0.39, yesAsk = 0.40), true)
            t += 1_000L
        }
        val again = book.snapshot().filter { it.variantId == "sf42" && it.state != ScalpState.NO_FILL }
        assertTrue("first dip ${sf42.size}, later ${again.size}", again.size >= sf42.size)
    }

    @Test fun simpleVariantsUseTheirOwnClipAndStop() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 600_000L
        val book = ScalpBook(liveExtras = true, bankrollUsd = { 100_000.0 })
        var t = start
        while (t < start + 250_000L) {
            book.onShockFavQuote(q(t, yesBid = 0.49, yesAsk = 0.50), true)
            t += 1_000L
        }
        val dipAt = t
        while (t <= dipAt + 4_000L) {
            book.onShockFavQuote(q(t, yesBid = 0.39, yesAsk = 0.40), true)
            t += 1_000L
        }
        val v1 = book.snapshot().filter { it.variantId == "sf41-v1" && it.state != ScalpState.NO_FILL }
        val v3 = book.snapshot().filter { it.variantId == "sf41-v3" && it.state != ScalpState.NO_FILL }
        assertTrue(v1.joinToString { it.state.name }, v1.size >= 2)
        assertTrue(v3.joinToString { it.state.name }, v3.size >= 2)
        assertTrue(v1.all { it.contracts >= PaperClip.MIN })
        assertTrue(v3.all { it.contracts >= PaperClip.MIN })
        val openV1 = v1.first { it.state == ScalpState.OPEN }
        val openV3 = v3.first { it.state == ScalpState.OPEN }
        val entry = openV1.entryPrice!!
        book.onShockFavQuote(q(t, yesBid = entry - 0.13, yesAsk = entry - 0.12), true)
        val stopped = book.snapshot().first { it.id == openV1.id }
        val held = book.snapshot().first { it.id == openV3.id }
        assertEquals(stopped.exitReason, ScalpState.CLOSED, stopped.state)
        assertTrue(stopped.exitReason!!.startsWith("stop"))
        assertEquals(ScalpState.OPEN, held.state)
        val sold = ScalpFills.sales(stopped).first()
        assertEquals(entry - 0.13, sold.price, 1e-9)
        assertTrue(sold.contracts >= PaperClip.MIN)
    }

    @Test fun makerStopSellsAtTheDisplayedBidInsteadOfHolding() {
        val close = ScalpTicker.closeMs(ticker)!!
        val now = close - 400_000L
        val id = "dip-g05-t08-s06-d10-w180-mk-v3"
        val open = ScalpTrade(
            id = "mk", ticker = ticker, side = "YES", state = ScalpState.OPEN,
            signalAtMs = now - 20_000L, signalAsk = 0.44, fairAtSignal = 0.50,
            contracts = 20, entryPrice = 0.44, entryFeeUsd = ScalpRule.orderFee(20, 0.44),
            entryAtMs = now - 15_000L,
            ruleVersion = "${ScalpRule.VERSION}|$id|P",
            note = "maker dip variant"
        )
        val book = ScalpBook(
            InMemoryScalpPersistence().also { it.seed(listOf(open)) },
            liveExtras = true,
            bankrollUsd = { 100_000.0 }
        )
        book.onQuote(q(now, yesBid = 0.30, yesAsk = 0.31, bidSize = 8.0), true)
        val row = book.snapshot().first { it.id == "mk" }
        assertTrue(row.exitReason!!, row.exitReason!!.contains("stop", ignoreCase = true))
        assertTrue(row.exitReason!!, !row.exitReason!!.contains("fair-gap"))
        val sold = ScalpFills.sales(row)
        assertEquals(8, sold.sumOf { it.contracts })
        assertEquals(0.30, sold.first().price, 1e-9)
        assertEquals(ScalpRule.orderFee(8, 0.30), row.exitFeeUsd, 1e-9)
        assertEquals(12, row.remaining)
    }

    private fun q(now: Long, yesBid: Double, yesAsk: Double, bidSize: Double = 100.0) = ScalpRule.Quote(
        ticker = ticker, nowMs = now, closeMs = ScalpTicker.closeMs(ticker)!!, bookAtMs = now,
        yesBid = yesBid, yesBidSize = bidSize, yesAsk = yesAsk, yesAskSize = 100.0,
        spot = 100_000.0, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
