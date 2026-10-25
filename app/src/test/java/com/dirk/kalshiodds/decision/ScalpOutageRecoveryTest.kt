package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kalshi was down overnight. When the book comes back, a clip opened on the
 * previous window must not pin the cash, a stale or gapped tick must say why,
 * and the next fresh tick on the current window must open again.
 */
class ScalpOutageRecoveryTest {
    private val previous = "KXBTC15M-26OCT181300-00"
    private val current = "KXBTC15M-26OCT181315-00"

    @Test fun outageThenOneFreshTickResumesOnTheCurrentWindow() {
        val previousClose = ScalpTicker.closeMs(previous)!!
        val currentClose = ScalpTicker.closeMs(current)!!
        assertEquals(900_000L, currentClose - previousClose)
        val now = currentClose - 600_000L
        assertTrue(now > previousClose)

        val clip = PaperClip.MIN
        val orphanCost = 10 * 0.50 + ScalpRule.orderFee(10, 0.50)
        val nextCost = clip * 0.35 + ScalpRule.orderFee(clip, 0.35)
        // One fresh clip fits after the orphan leaves the book. Both together do not.
        val bankroll = maxOf(orphanCost, nextCost) + minOf(orphanCost, nextCost) / 2.0
        val orphan = ScalpTrade(
            id = "before-outage", ticker = previous, side = "YES", state = ScalpState.OPEN,
            signalAtMs = previousClose - 400_000L, signalAsk = 0.50, fairAtSignal = 0.55,
            contracts = 10, entryPrice = 0.50, entryFeeUsd = ScalpRule.orderFee(10, 0.50),
            entryAtMs = previousClose - 390_000L,
            ruleVersion = "${ScalpRule.VERSION}|${KeptScalpers.FAIR_ID}|P",
            note = "opened before Kalshi went down"
        )
        val book = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(orphan)) }, bankrollUsd = { bankroll })

        book.onQuote(quote(now, fresh = false), enabled = true)
        assertTrue(book.snapshot().none { it.id == "before-outage" && (it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_ENTRY || it.state == ScalpState.PENDING_EXIT) })
        assertEquals(0.0, book.openCostUsd(), 1e-6)
        assertTrue(book.snapshot().none { it.ticker == current })
        val during = book.gateLine(KeptScalpers.FAIR_ID)
        assertTrue(during, during != null && during.contains(ScalpGateLog.OUTAGE))

        book.onQuote(quote(now + 1_000L, fresh = true, gap = true), enabled = true)
        assertTrue(book.snapshot().none { it.ticker == current && it.state == ScalpState.PENDING_ENTRY })
        assertTrue(book.gateLine(KeptScalpers.FAIR_ID)!!.contains(ScalpGateLog.OUTAGE))

        book.onQuote(quote(now + 2_000L, fresh = true), enabled = true)
        val resumed = book.snapshot().filter { it.ticker == current && it.variantId == KeptScalpers.FAIR_ID }
        assertTrue(resumed.joinToString { "${it.state} ${it.note}" }, resumed.any { it.state == ScalpState.PENDING_ENTRY || it.state == ScalpState.OPEN })
        assertNull(book.gateLine(KeptScalpers.FAIR_ID))
        assertTrue(book.openCostUsd() <= bankroll + 1e-6)
    }

    @Test fun currentWindowIgnoresTheExpiredMarketAndAMissingCloseField() {
        val close = ScalpTicker.closeMs(current)!!
        val now = close - 120_000L
        val picked = ScalpRecovery.currentTicker(
            listOf(
                ScalpRecovery.Card(previous, ScalpTicker.closeMs(previous)),
                ScalpRecovery.Card(current, null),
                ScalpRecovery.Card("KXBTC15M-26OCT181330-00", null)
            ),
            now
        )
        assertEquals(current, picked)
        assertNull(ScalpRecovery.currentTicker(listOf(ScalpRecovery.Card(previous, ScalpTicker.closeMs(previous))), now))
    }

    private fun quote(now: Long, fresh: Boolean, gap: Boolean = false) = ScalpRule.Quote(
        ticker = current,
        nowMs = now,
        closeMs = ScalpTicker.closeMs(current)!!,
        bookAtMs = if (fresh) now else now - 30_000L,
        yesBid = 0.34, yesBidSize = 50.0, yesAsk = 0.35, yesAskSize = 50.0,
        spot = 101_000.0, strike = 100_000.0, sigmaPerSec = 3e-5,
        bookGap = gap
    )
}
