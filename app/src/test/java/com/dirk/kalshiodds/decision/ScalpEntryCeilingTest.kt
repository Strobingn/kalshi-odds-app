package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

class ScalpEntryCeilingTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val at = close - 300_000L
    private fun q(ms: Long, price: Double, side: String = "YES") = ScalpRule.Quote(
        ticker, ms, close, ms, if (side == "YES") price - .01 else 1 - price, 100.0,
        if (side == "YES") price else 1 - (price - .01), 100.0, 100_000.0, 100_000.0, 3e-5)
    private fun pending(variant: String, side: String = "YES", price: Double = .50) = ScalpTrade(
        "entry", ticker, side, ScalpState.PENDING_ENTRY, at, price, .8,
        ruleVersion = "${ScalpRule.VERSION}|$variant|P")
    private fun book(t: ScalpTrade) = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(t)) },
        variants = emptyList(), liveExtras = true)
    private fun row(book: ScalpBook) = book.snapshot().first { it.id == "entry" }

    @org.junit.Ignore("Kalshi Scalper 1.0.0: pins Kashi rules/identity superseded by Dirk's new hard rules (55c ceiling, 3c trail, window-start entries, scalp: prefix); covered by ScalperRulesTest") @Test fun everyV2SpecSettingsAndLearningStayUnderTheCeiling() {
        for (m in ScalpModels.V2_MODELS) {
            assertTrue(m.code, ScalpV2.spec(m.code).maxAsk <= .50)
            for (manual in listOf(false, true)) {
                val plan = ScalpControls(manual = manual, maxAskCents = 99).plan(
                    ScalpV2.plan(m.code, emptyList(), at))
                assertTrue(plan.spec.maxAsk <= .50)
            }
        }
        assertEquals(50, ScalpControls(maxAskCents = 99).normalized().maxAskCents)
        assertTrue(ScalpV2.entryOk(.50, .99))
        listOf(.51, .90, Double.NaN, Double.POSITIVE_INFINITY).forEach { assertFalse(ScalpV2.entryOk(it, .99)) }
        assertFalse(ScalpV2.entryOk(.50, .49))
        assertFalse(ForecastScalp.wantsEntry(.50, .01, .51, .30, 300.0))
        assertTrue(ForecastScalp.wantsEntry(.50, .01, .50, .30, 300.0))
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.0: pins Kashi rules/identity superseded by Dirk's new hard rules (55c ceiling, 3c trail, window-start entries, scalp: prefix); covered by ScalperRulesTest") @Test fun delayedOriginalAndRestoredV2FillsRespectBothSidesAndFiftyCentBoundary() {
        for (variant in listOf(ScalpParams.CLASSIC.id, "v2-sf41", "v2-sf41t2")) {
            for (side in listOf("YES", "NO")) {
                for (price in listOf(.50, .51)) {
                    val book = book(pending(variant, side).copy(note = "v2ask=0.99"))
                    book.onQuote(q(at + 3_000, price, side), false)
                    val t = row(book)
                    if (price == .50) {
                        assertEquals("$variant $side", ScalpState.OPEN, t.state)
                        assertEquals(.50, t.entryPrice!!, 1e-9)
                    } else {
                        assertNotEquals("$variant $side", ScalpState.OPEN, t.state)
                        assertEquals(0, t.contracts)
                    }
                }
            }
        }
    }

    @Test fun oldMakerAndForecastLimitsAboveFiftyAreCancelledInsteadOfFilled() {
        val maker = ScalpModels.MAKER_DIP_PARAMS.id
        val cf = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.CF_REPRICE).id
        for (variant in listOf(maker, cf, ForecastScalp.variantId(true, 60))) {
            val t = pending(variant, price = .60).copy(restingLimit = .60, queueAhead = 10.0, levelSize = 10.0)
            val book = book(t)
            if (ForecastScalp.isForecast(t)) book.onForecastQuote(q(at + 1_000, .49), 100.0, 100.0, false)
            else book.onQuote(q(at + 1_000, .49), false)
            assertEquals(variant, ScalpState.NO_FILL, row(book).state)
            assertNull(row(book).entryPrice)
        }
    }

    @Test fun shockDelayedTakerAndMakerJoinCannotBuyAboveFifty() {
        for (variant in listOf("sf41", "sf28")) {
            val book = book(pending(variant).copy(restingLimit = if (variant == "sf28") .60 else null))
            for (sec in -130..0) book.tickSpot("BTC", 100_000.0, at + sec * 1000L)
            book.onShockFavQuote(q(at + 2_000, .60), false)
            assertEquals(variant, ScalpState.NO_FILL, row(book).state)
        }
        val book = book(pending("sf28"))
        for (sec in -130..0) book.tickSpot("BTC", 100_000.0, at + sec * 1000L)
        book.onShockFavQuote(q(at + 2_000, .60), false)
        assertEquals(ScalpState.NO_FILL, row(book).state)
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.0: pins Kashi rules/identity superseded by Dirk's new hard rules (55c ceiling, 3c trail, window-start entries, scalp: prefix); covered by ScalperRulesTest") @Test fun makerSignalsCanUseAnEligibleBidWhenTheAskIsAboveFifty() {
        val maker = ScalpParams.MAKER_VARIANTS.first { it.strategy == ScalpStrategy.FAIR_GAP }
        val quote = q(at, .51).copy(yesBid = .49, spot = 100_300.0)
        assertNotNull(ScalpRule.entrySignal(quote, maker, listOf("YES")).first)
        assertNull(ScalpRule.entrySignal(quote, maker.copy(maker = false), listOf("YES")).first)

        val book = ScalpBook(variants = emptyList())
        for (sec in -300..0) book.tickSpot("BTC", 100_000.0, at + sec * 1000L)
        // Downward spot shock creates a YES fade-maker signal. Ask 51¢, bid 49¢.
        book.onShockFavQuote(quote.copy(nowMs = at + 5_000, bookAtMs = at + 5_000, spot = 99_900.0), true)
        val signal = book.snapshot().first { it.variantId == "sf28" }
        assertEquals("YES", signal.side)
        book.onShockFavQuote(quote.copy(nowMs = at + 7_000, bookAtMs = at + 7_000, spot = 99_900.0), false)
        assertEquals(.49, book.snapshot().first { it.id == signal.id }.restingLimit!!, 1e-9)
        book.onShockFavQuote(q(at + 8_000, .48).copy(spot = 99_900.0), false)
        val filled = book.snapshot().first { it.id == signal.id }
        assertEquals(ScalpState.OPEN, filled.state)
        assertEquals(.49, filled.entryPrice!!, 1e-9)
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.0: pins Kashi rules/identity superseded by Dirk's new hard rules (55c ceiling, 3c trail, window-start entries, scalp: prefix); covered by ScalperRulesTest") @Test fun depthWalkingExcludesExpensiveTailEvenIfAverageWouldBeCheap() {
        val levels = listOf(.10 to 9.0, .50 to 1.0, .51 to 90.0)
        val (n, usd, _) = ScalpControls().walkBuy(levels, 20, .99)
        assertEquals(10, n)
        assertEquals(1.40, usd, 1e-9)
        for (side in listOf("YES", "NO")) {
            val book = book(pending("v2-sf41x5", side, .40).copy(note = "v2ask=0.99"))
            val quote = q(at + 2_000, .40, side)
            val bids = listOf(.60 to 2.0, .49 to 100.0) // asks 40¢ then 51¢
            book.onQuote(if (side == "YES") quote.copy(noBidsDesc = bids) else quote.copy(yesBidsDesc = bids), false)
            assertEquals(ScalpState.OPEN, row(book).state)
            assertEquals(2, row(book).contracts)
            assertEquals(.40, row(book).entryPrice!!, 1e-9)
        }
    }

    @Test fun existingPositionsCanStillExitAboveFifty() {
        for (variant in listOf(ScalpParams.CLASSIC.id, "v2-sf41")) {
            val book = book(pending(variant, price = .80).copy(state = ScalpState.PENDING_EXIT,
                contracts = 10, entryPrice = .80, entryAtMs = at, exitDecidedAtMs = at, exitReason = "saved stop"))
            book.onQuote(q(at + 3_000, .71), false)
            assertEquals(ScalpState.CLOSED, row(book).state)
            assertEquals(7.0, row(book).proceedsUsd, 1e-9)
        }
    }
}
