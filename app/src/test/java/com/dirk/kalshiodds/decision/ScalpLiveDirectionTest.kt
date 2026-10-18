package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

class ScalpLiveDirectionTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val start = close - 900_000L
    private fun quote(sec: Long, bid: Double, ask: Double) = ScalpRule.Quote(
        ticker, start + sec * 1000, close, start + sec * 1000, bid, 100.0, ask, 100.0, null, null, null)
    private fun book(store: InMemoryScalpPersistence = InMemoryScalpPersistence()) = ScalpBook(store = store, variants = emptyList(), liveExtras = true,
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
            assertEquals(4.0, ScalpV2.stopPercent(held), 0.0)
            assertEquals(.02, ScalpV2.savedNumber(held.note, "v2trail", 0.0), 1e-9) // 0.3.89: sf51's own 2¢ trail, not the global 3¢
        }
    }

    @Test fun reversalBeforeFillCancelsOldSideAndQueuesTheCurrentSideWithItsOwnDelay() {
        val book = book()
        tick(book, quote(0, .49, .51))
        tick(book, quote(1, .53, .55))
        tick(book, quote(4, .45, .47))
        assertEquals(ScalpState.NO_FILL, book.snapshot().single { it.variantId == "sf51" && it.side == "YES" }.state)
        val rows = book.snapshot().filter { it.variantId == "v2-sf51" }
        assertEquals(ScalpState.NO_FILL, rows.single { it.side == "YES" }.state)
        assertEquals(ScalpState.PENDING_ENTRY, rows.single { it.side == "NO" }.state)
        tick(book, quote(5, .45, .47))
        assertTrue(book.snapshot().none { it.variantId == "v2-sf51" && it.state == ScalpState.OPEN })
        tick(book, quote(6, .45, .47))
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

    @Test fun missingMidpointOrNeutralMoveWaitsWithoutCancellingOrFillingTheOrder() {
        for (side in listOf("YES", "NO")) for (oneSided in listOf(false, true)) {
            val book = book()
            tick(book, quote(0, .49, .51))
            val signal = if (side == "YES") quote(1, .53, .55) else quote(1, .45, .47)
            tick(book, signal)
            val uncertain = if (!oneSided) quote(4, .49, .51) else
                signal.copy(nowMs = start + 4000, bookAtMs = start + 4000,
                    yesBid = if (side == "YES") null else signal.yesBid,
                    yesAsk = if (side == "NO") null else signal.yesAsk)
            tick(book, uncertain)
            val pending = book.snapshot().filter { it.variantId in listOf("sf51", "v2-sf51") }
            assertEquals(2, pending.size)
            assertTrue(pending.all { it.state == ScalpState.PENDING_ENTRY && it.side == side })
            tick(book, signal.copy(nowMs = start + 5000, bookAtMs = start + 5000))
            assertEquals(ScalpState.OPEN, book.snapshot().single { it.variantId == "v2-sf51" }.state)
        }
    }

    @Test fun neutralAndOneSidedOrdersExpireWithoutSpotDataAndCannotFillLater() {
        for (oneSided in listOf(false, true)) {
            val book = book()
            tick(book, quote(0, .49, .51))
            tick(book, quote(1, .53, .55))
            val ids = book.snapshot().filter { it.variantId in listOf("sf51", "v2-sf51") }.map { it.id }.toSet()
            val uncertain = quote(8, .49, .51).let { if (oneSided) it.copy(yesBid = null) else it }
            book.onQuote(uncertain, false)
            book.onShockFavQuote(uncertain, false)
            assertTrue(book.snapshot().filter { it.id in ids }.all { it.state == ScalpState.NO_FILL })
            tick(book, quote(40, .53, .55))
            assertTrue(book.snapshot().filter { it.id in ids }.all { it.state == ScalpState.NO_FILL })
        }
    }

    @Test fun openingCutoffExpiresOrdersEvenBeforeTheirSixSecondTtl() {
        val book = book()
        tick(book, quote(0, .49, .51))
        tick(book, quote(29, .53, .55))
        val ids = book.snapshot().filter { it.variantId in listOf("sf51", "v2-sf51") }.map { it.id }.toSet()
        book.onQuote(quote(31, .53, .55), false)
        assertEquals(2, ids.size)
        assertTrue(book.snapshot().filter { it.id in ids }.all { it.state == ScalpState.NO_FILL })
    }

    @Test fun restartKeepsTheOriginalOpeningMidpointDespiteRetracement() {
        val store = InMemoryScalpPersistence()
        val first = book(store)
        tick(first, quote(0, .49, .51))
        tick(first, quote(1, .53, .55))
        first.flushWrites()
        val restarted = book(store)
        tick(restarted, quote(3, .51, .53)) // down since the signal, still UP relative to this window's 50¢ open
        val trade = restarted.snapshot().single { it.variantId == "v2-sf51" }
        assertEquals("YES", trade.side)
        assertEquals(ScalpState.OPEN, trade.state)
        assertEquals(.50, ScalpV2.savedNumber(trade.note, "o50open", 0.0), 1e-9)
    }

    @Test fun legacyPendingOrderWithoutOpeningReferenceIsCancelledInsteadOfRebased() {
        val store = InMemoryScalpPersistence()
        store.seed(listOf("sf51", "v2-sf51").map { variant -> ScalpTrade(
            variant, ticker, "YES", ScalpState.PENDING_ENTRY, start + 1000, .55, .5,
            ruleVersion = "${ScalpRule.VERSION}|$variant|P") })
        val restored = book(store)
        restored.onQuote(quote(3, .45, .47), false)
        assertTrue(restored.snapshot().filter { it.id in listOf("sf51", "v2-sf51") }.all { it.state == ScalpState.NO_FILL })
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
