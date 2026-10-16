package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test
import com.dirk.kalshiodds.decision.ScalpControlSpec.Key as K

class ScalpExecutionLimitsTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val now = close - 400_000L
    private fun q(at: Long = now, bid: Double = .38, size: Double = 1000.0) = ScalpRule.Quote(ticker, at, close, at,
        bid, size, bid + .01, size, null, null, null)
    private fun held(code: String, side: String = "YES") = ScalpTrade(code, ticker, side, ScalpState.OPEN,
        now - 10_000, .40, .50, contracts = 10, entryPrice = .40, entryFeeUsd = .17, entryAtMs = now - 8000,
        ruleVersion = "${ScalpRule.VERSION}|${ScalpControlSpec.variantFor(code)}|P")
    private fun book(rows: List<ScalpTrade>, store: InMemoryScalpPersistence = InMemoryScalpPersistence().also { it.seed(rows) }) =
        ScalpBook(store = store, variants = emptyList(), executionLimits = ScalpExecutionLimits())

    @Test fun all131ModelsExitAtTwoCentLossWithoutSpotFairOrHoldTimers() {
        val rows = ScalpModels.ALL.map { held(it.code) }
        val book = book(rows)
        book.onQuote(q(), false)
        assertEquals(131, book.snapshot().size)
        assertTrue(book.snapshot().all { it.state == ScalpState.CLOSED && it.exitReason == ScalpExecutionLimits.REASON })
        assertTrue(book.snapshot().all { it.soldContracts == 10 })
        assertTrue(book.snapshot().all { it.netUsd == it.proceedsUsd - it.exitFeeUsd - it.entryCostUsd })
    }
    @Test fun downSideUsesItsOwnBidAndStopsAlsoRunOnDirectFamilyQuotePaths() {
        val fc = book(listOf(held("fcride", "NO")))
        fc.onForecastQuote(q(bid = .61), 10.0, 10.0, false)
        assertEquals(ScalpState.CLOSED, fc.snapshot().single().state)
        val sf = book(listOf(held("sf19")))
        sf.onShockFavQuote(q(), false)
        assertEquals(ScalpState.CLOSED, sf.snapshot().single().state)
    }
    @Test fun noBuyAboveFiftyEvenIfPriceCrossesCapAfterSignalForEveryModel() {
        val pending = ScalpModels.ALL.map { held(it.code).copy(state = ScalpState.PENDING_ENTRY, contracts = 0,
            signalAtMs = now - 3000, signalAsk = .50, entryAtMs = null, entryPrice = null, entryFeeUsd = 0.0) }
        val book = book(pending)
        val quote = q(bid = .50)
        book.onQuote(quote, false); book.onForecastQuote(quote, 10.0, 10.0, false); book.onShockFavQuote(quote, false)
        assertFalse(book.snapshot().any { it.entryPrice?.let { px -> px > .50 + 1e-9 } == true })
        assertTrue(ScalpExecutionLimits().buyAllowed(.50))
        assertFalse(ScalpExecutionLimits().buyAllowed(.5001))
    }
    @Test fun stopPartialFillsDoNotReuseDepthAndSurviveRestart() {
        val t = held("v2-sf41")
        val store = InMemoryScalpPersistence().also { it.seed(listOf(t)) }
        val first = book(listOf(t), store)
        first.onQuote(q(size = 2.0), false)
        assertEquals(2, first.snapshot().single().soldContracts)
        first.onQuote(q(at = now + 10, size = 2.0).copy(bookAtMs = now), false)
        assertEquals(2, first.snapshot().single().soldContracts)
        first.flushWrites()
        val restart = book(emptyList(), store)
        restart.onQuote(q(at = now + 1000), false)
        val closed = restart.snapshot().single()
        assertEquals(ScalpState.CLOSED, closed.state)
        assertEquals(listOf(2, 8), ScalpFillLog.recorded(closed).filter { it.kind == ScalpFillLog.Kind.SELL }.map { it.qty })
    }
    @Test fun staleBooksAndGapsCannotTriggerOrFillStopsAndGapsCanExceedTwoCents() {
        val limits = ScalpExecutionLimits(); val t = held("sf41")
        assertNull(limits.step(t, q().copy(bookGap = true)))
        assertNull(limits.step(t, q().copy(bookAtMs = now - 4000)))
        assertNull(limits.step(t, q(bid = .39)))
        val gap = limits.step(t, q(bid = .30))!!
        assertEquals(.30, gap.proceedsUsd / gap.soldContracts, 1e-9)
        assertTrue(gap.netUsd!! < -.20)
    }
    @Test fun defaultsAndOldSavedSettingsCannotDisableStopOrRaiseCap() {
        for (m in ScalpModels.ALL) {
            val c = ScalpControlSpec.defaults(m.code)
            assertEquals(2.0, c[K.STOP], 0.0); assertTrue(c[K.MAX_ASK] <= 50.0)
            val old = c.set(K.MAX_ASK, 99.0).set(K.STOP, 99.0)
            val restored = ScalpControlSpec.decode(old.encode())!!
            assertEquals(2.0, restored[K.STOP], 0.0); assertEquals(50.0, restored[K.MAX_ASK], 0.0)
        }
    }
}
