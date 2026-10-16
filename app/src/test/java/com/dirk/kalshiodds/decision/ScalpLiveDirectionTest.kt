package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

class ScalpLiveDirectionTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val start = close - 900_000L
    private fun quote(sec: Long, bid: Double, ask: Double) = ScalpRule.Quote(
        ticker, start + sec * 1000, close, start + sec * 1000, bid, 100.0, ask, 100.0, null, null, null)
    private fun book() = ScalpBook(variants = emptyList(), liveExtras = true,
        controls = MemoryScalpControls(ScalpControls(disabledStrategies = ScalpModels.V2_MODELS
            .map { it.code }.filter { it != "v2-sf51" }.toSet())))
    private fun tick(book: ScalpBook, q: ScalpRule.Quote) {
        // Production order: V2 execution before source signal evaluation on the same live book.
        book.onQuote(q, false)
        book.onShockFavQuote(q, true)
    }

    @Test fun openingMoveCanBuyEitherSideAtFiftyFiveFromTheFirstSeconds() {
        for (side in listOf("YES", "NO")) {
            val book = book()
            tick(book, quote(0, .49, .51))
            assertTrue(book.snapshot().none { it.variantId == "v2-sf51" }) // flat: no direction yet
            val q = if (side == "YES") quote(1, .53, .55) else quote(1, .45, .47)
            tick(book, q)
            val pending = book.snapshot().single { it.variantId == "v2-sf51" }
            assertEquals(side, pending.side)
            assertEquals(ScalpState.PENDING_ENTRY, pending.state)
            tick(book, q.copy(nowMs = start + 3000, bookAtMs = start + 3000))
            val held = book.snapshot().single { it.variantId == "v2-sf51" }
            assertEquals(ScalpState.OPEN, held.state)
            assertEquals(.55, held.entryPrice!!, 1e-9)
            assertTrue(ScalpV2.peakRule(held))
            assertEquals(20.0, ScalpV2.stopPercent(held), 0.0)
            assertEquals(.03, ScalpV2.savedNumber(held.note, "v2trail", 0.0), 1e-9)
        }
    }

    @Test fun reversalBeforeFillCancelsOldSideAndQueuesTheCurrentSideWithItsOwnDelay() {
        val book = book()
        tick(book, quote(0, .49, .51))
        tick(book, quote(1, .53, .55))
        tick(book, quote(3, .45, .47))
        val rows = book.snapshot().filter { it.variantId == "v2-sf51" }
        assertEquals(ScalpState.NO_FILL, rows.single { it.side == "YES" }.state)
        assertEquals(ScalpState.PENDING_ENTRY, rows.single { it.side == "NO" }.state)
        tick(book, quote(4, .45, .47))
        assertTrue(book.snapshot().none { it.variantId == "v2-sf51" && it.state == ScalpState.OPEN })
        tick(book, quote(5, .45, .47))
        assertEquals("NO", book.snapshot().single { it.variantId == "v2-sf51" && it.state == ScalpState.OPEN }.side)
    }

    @Test fun staleOrGappedOpeningBookCannotSetBaselineOrBuy() {
        for (gap in listOf(false, true)) {
            val book = book()
            tick(book, quote(0, .89, .91).copy(bookAtMs = if (gap) start else start - 10_000, bookGap = gap))
            tick(book, quote(1, .49, .51))
            assertTrue(book.snapshot().none { it.variantId == "v2-sf51" })
            tick(book, quote(2, .53, .55))
            tick(book, quote(4, .53, .55).copy(bookGap = true))
            assertTrue(book.snapshot().none { it.variantId == "v2-sf51" && it.state == ScalpState.OPEN })
            tick(book, quote(5, .53, .55))
            assertEquals("YES", book.snapshot().single { it.variantId == "v2-sf51" && it.state == ScalpState.OPEN }.side)
        }
    }

    @Test fun dipSelectionComparesBothEligibleSidesAndWaitsOnEqualStrength() {
        val p = ShockFav.v("sf41")
        assertEquals("NO", ShockFav.simpleDipSide(p, .50, .57, .49, .60))
        assertEquals("YES", ShockFav.simpleDipSide(p, .49, .60, .50, .57))
        assertNull(ShockFav.simpleDipSide(p, .50, .60, .50, .60))
        assertEquals("NO", ShockFav.simpleDipSide(p, .56, .90, .49, .60))
        assertEquals("YES", ShockFav.simpleDipSide(p, .55, .65, .56, .90))
        assertNull(ShockFav.simpleDipSide(p, Double.NaN, .90, .49, Double.NaN))
    }

    @Test fun openingWindowIncludesZeroButNeverPreviousOrClosedWindow() {
        assertTrue(ShockFav.o50InEntryWindow(900.0))
        assertTrue(ShockFav.o50InEntryWindow(899.0))
        assertFalse(ShockFav.o50InEntryWindow(900.001))
        assertFalse(ShockFav.o50InEntryWindow(869.999))
        assertFalse(ShockFav.o50InEntryWindow(0.0))
    }
}
