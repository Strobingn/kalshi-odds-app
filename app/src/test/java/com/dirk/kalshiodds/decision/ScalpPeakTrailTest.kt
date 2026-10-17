package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

class ScalpPeakTrailTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val at = close - 300_000L
    private fun controls(code: String = "v2-sf41") = MemoryScalpControls(ScalpControls(stopLossPercent = 20,
        disabledStrategies = ScalpModels.V2_MODELS.map { it.code }.filter { it != code }.toSet()))
    private fun source(side: String = "YES") = ScalpTrade("source", ticker, side, ScalpState.PENDING_ENTRY,
        at, .40, .50, ruleVersion = "${ScalpRule.VERSION}|sf41|P")
    private fun q(ms: Long, bid: Double = .39, ask: Double = .40, size: Double = 100.0, side: String = "YES") =
        ScalpRule.Quote(ticker, ms, close, ms,
            if (side == "YES") bid else 1 - ask, size,
            if (side == "YES") ask else 1 - bid, size, 100_000.0, 100_000.0, 3e-5)
    private fun held(book: ScalpBook, code: String = "v2-sf41") = book.snapshot().first { it.variantId == code }

    @Test fun threeCentsIsAbsoluteAndTwentyPercentUsesEntryPrice() {
        assertNull(ScalpV2.peakExit(.40, 20.0, .90, .90, .03))
        assertNull(ScalpV2.peakExit(.40, 20.0, .90, .88, .03))
        assertTrue(ScalpV2.peakExit(.40, 20.0, .90, .87, .03)!!.contains("trail 3¢"))
        assertNull(ScalpV2.peakExit(.40, 20.0, .95, .93, .03))
        assertTrue(ScalpV2.peakExit(.40, 20.0, .95, .92, .03)!!.contains("trail 3¢"))
        assertTrue(ScalpV2.peakExit(.10, 20.0, .20, .17, .03)!!.contains("trail 3¢"))
        assertEquals(.32, ScalpV2.percentStopPrice(.40, 20.0), 1e-9)
        assertEquals(.008, ScalpV2.percentStopPrice(.01, 20.0), 1e-9) // no 1¢ clamp above a cheap contract's stop
    }

    @Test fun bothSidesHoldAtNinetyRatchetToNinetyFiveAndExitAtNinetyTwoAcrossRestarts() {
        for (side in listOf("YES", "NO")) {
            val cfg = controls()
            val store = InMemoryScalpPersistence()
            val book = ScalpBook(store, variants = emptyList(), liveExtras = true, controls = cfg)
            book.importRestored(listOf(source(side)))
            book.onQuote(q(at + 2000, side = side), false)
            assertTrue(ScalpV2.peakRule(held(book)))
            assertEquals(.08, ScalpV2.savedStop(held(book)), 1e-9)
            book.onQuote(q(at + 3000, .90, .91, side = side), false)
            book.onQuote(q(at + 4000, .90, .91, side = side), false)
            assertEquals(ScalpState.OPEN, held(book).state)
            book.onQuote(q(at + 5000, .95, .96, side = side), false)
            assertEquals(ScalpState.OPEN, held(book).state)
            book.flushWrites()
            cfg.save(cfg.state.value.copy(peakTrailCents = 10, stopLossPercent = 70, enabled = false))
            val reopened = ScalpBook(store, variants = emptyList(), liveExtras = true, controls = cfg)
            reopened.onQuote(q(at + 6000, .92, .93, side = side), false)
            assertEquals(ScalpState.PENDING_EXIT, held(reopened).state)
            assertTrue(held(reopened).exitReason!!.contains("trail 3¢"))
            assertEquals(0, held(reopened).soldContracts)
            reopened.onQuote(q(at + 7000, .92, .93, size = 2.0, side = side), false)
            assertEquals(2, held(reopened).soldContracts)
            reopened.onQuote(q(at + 7100, .92, .93, size = 2.0, side = side).copy(bookAtMs = at + 7000), false)
            assertEquals(2, held(reopened).soldContracts)
            reopened.flushWrites()
            val again = ScalpBook(store, variants = emptyList(), liveExtras = true, controls = cfg)
            again.onQuote(q(at + 8000, .94, .95, side = side), false) // rebound never cancels a triggered exit
            assertEquals(ScalpState.CLOSED, held(again).state)
            again.flushWrites()
            assertEquals(1, cfg.feedback("v2-sf41").trades)
        }
    }

    @Test fun trailIsActiveBeforeAFeeInclusiveProfitThreshold() {
        val book = ScalpBook(variants = emptyList(), liveExtras = true, controls = controls())
        book.importRestored(listOf(source()))
        book.onQuote(q(at + 2000), false)
        book.onQuote(q(at + 3000, .42, .43), false)
        book.onQuote(q(at + 4000, .39, .40), false)
        assertEquals(ScalpState.PENDING_EXIT, held(book).state)
        assertTrue(held(book).exitReason!!.contains("trail 3¢"))
    }

    @Test fun hardStopIsIndependentOfTrailAndInitialSpreadIsNotAFalsePeak() {
        val cfg = controls()
        val book = ScalpBook(variants = emptyList(), liveExtras = true, controls = cfg)
        book.importRestored(listOf(source()))
        book.onQuote(q(at + 2000, .33, .40), false)
        assertEquals(ScalpState.OPEN, held(book).state)
        book.onQuote(q(at + 3000, .33, .40), false)
        assertEquals(ScalpState.OPEN, held(book).state) // 7¢ spread did not become an artificial trail loss
        book.onQuote(q(at + 4000, .32, .40), false)
        assertEquals(ScalpState.OPEN, held(book).state) // 0.3.85: 1¢ below the entry bid is noise, not a stop
        book.onQuote(q(at + 5000, .22, .30), false)
        assertEquals(ScalpState.PENDING_EXIT, held(book).state)
        assertTrue(held(book).exitReason!!.let { it.contains("20%") || it.contains("trail") }) // floored stop: entry bid − max(20%, spread + fees + 1¢)
    }

    @Test fun percentStopUsesDepthWalkAverageFillAndStaleBooksCannotMovePeak() {
        val cfg = controls("v2-sf41x5")
        val book = ScalpBook(variants = emptyList(), liveExtras = true, controls = cfg)
        book.importRestored(listOf(source()))
        book.onQuote(q(at + 2000).copy(noBidsDesc = listOf(.60 to 2.0, .50 to 3.0)), false)
        val trade = held(book, "v2-sf41x5")
        assertEquals(.46, trade.entryPrice!!, 1e-9)
        assertEquals(.092, ScalpV2.savedStop(trade), 1e-9)
        book.onQuote(q(at + 3000, .99, 1.0).copy(bookGap = true), false)
        book.onQuote(q(at + 4000, .40, .41), false)
        assertEquals(ScalpState.OPEN, held(book, "v2-sf41x5").state)
        book.onQuote(q(at + 5000, .20, .21), false)
        assertTrue(held(book, "v2-sf41x5").exitReason!!.contains("20%"))
    }
}
