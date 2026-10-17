package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

class ScalpV2AdaptiveTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val now = close - 120_000L
    private fun q(at: Long, bid: Double? = .40, size: Double = 10.0) = ScalpRule.Quote(
        ticker, at, close, at, bid, size, .41, 10.0, 100_000.0, 100_000.0, 3e-5)
    private fun open(id: String = "held", variant: String = "v2-sf41", note: String = "v2stop=0.15") = ScalpTrade(
        id, ticker, "YES", ScalpState.OPEN, now - 5_000, .40, .50,
        contracts = 10, entryPrice = .40, entryFeeUsd = ScalpRule.orderFee(10, .40), entryAtMs = now - 3_000,
        note = note, ruleVersion = "${ScalpRule.VERSION}|$variant|P")
    private fun book(t: ScalpTrade): Pair<ScalpBook, InMemoryScalpPersistence> {
        val store = InMemoryScalpPersistence().also { it.seed(listOf(t)) }
        return ScalpBook(store = store, variants = emptyList(), liveExtras = true) to store
    }
    private fun windows(net: Double, n: Int = 10, count: Int = 3) = (1..count).map {
        ScalpWindowAgg("w$it", "v2-sf41", n, net, now - it * 900_000L)
    }

    @Test fun winnersAndInconclusiveSettingsStayIntact() {
        for (rows in listOf(windows(1.0), windows(-1.0, n = 1), windows(-1.0, count = 2),
            windows(-1.0).take(2) + windows(5.0).take(1))) {
            val plan = ScalpV2.plan("v2-sf41", rows, now)
            assertFalse(plan.adjusted)
            assertEquals(ScalpV2.spec("v2-sf41"), plan.spec)
            assertEquals(.15, plan.stop, 1e-9)
        }
    }

    @Test fun onlyCompletedWindowsOfThisModelCanTriggerBoundedTrial() {
        val rows = windows(-1.0)
        val plan = ScalpV2.plan("v2-sf41", rows, now)
        assertTrue(plan.adjusted)
        assertEquals(.48, plan.spec.maxAsk, 1e-9)
        assertEquals(.12, plan.stop, 1e-9)
        assertEquals(.04, plan.arm, 1e-9)
        assertFalse(ScalpV2.plan("v2-sf42", rows, now).adjusted)
        assertFalse(ScalpV2.plan("v2-sf41", rows.map { it.copy(closeMs = now + 1) }, now).adjusted)
        assertFalse(ScalpV2.plan("v2-sf41", rows.map { it.copy(netUsd = Double.NaN) }, now).adjusted)
        assertTrue(ScalpV2.plan("v2-any-variant", rows, now, "v2-sf41").adjusted)
        assertFalse(ScalpV2.plan("v2-sf41", rows, now, unfinishedWindows = setOf("w1")).adjusted)
    }

    @Test fun fallbackUsesOriginalAndCheapStopsRemainReachable() {
        assertEquals(.15, ScalpV2.savedStop(open(note = "")), 1e-9)
        assertEquals(.10, ScalpV2.savedStop(open(variant = "v2-sf41t1", note = "")), 1e-9)
        assertEquals(.01, ScalpV2.stopPrice(.03, .25), 1e-9)
        assertNotNull(ScalpV2.exit(.03, .25, .03, .01, false))
    }

    @Test fun closeHoldNeedsFreshCFPositiveNetAndAnAdvantageOverSelling() {
        val near = q(close - 50_000L, .92).copy(yesAsk = .93, cfSpot = 100_100.0, cfAgeMs = 0L)
        assertNull(ScalpV2.closeExit(near, "YES", .40, 10))
        assertNotNull(ScalpV2.closeExit(near.copy(cfAgeMs = 2_001), "YES", .40, 10))
        assertNotNull(ScalpV2.closeExit(near.copy(cfSpot = null), "YES", .40, 10))
        assertNotNull(ScalpV2.closeExit(near.copy(bookGap = true), "YES", .40, 10))
        assertNotNull(ScalpV2.closeExit(near.copy(yesBid = .80), "YES", .40, 10))
        assertNotNull(ScalpV2.closeExit(near, "YES", .95, 10))
        assertNotNull(ScalpV2.closeExit(near.copy(cfSpot = 100_000.0), "YES", .40, 10))
        assertNotNull(ScalpV2.closeExit(near.copy(cfSpot = 99_900.0), "NO", .40, 10)) // NO bid is only 7¢
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.3: pins pre-1.0.3 entries/exits of a strategy now retuned toward the fair-gap rule (24–42¢, BRTI gap 10–21¢ after fee, no stop/trail, ≈4.5 min hold); covered by ReleaseGate103FairGapTest") @Test fun stopExitIsDelayedPartialAndCannotReuseTheSameBook() {
        val (book, _) = book(open())
        book.onQuote(q(now, .24, 2.0), false)
        assertEquals(ScalpState.PENDING_EXIT, book.snapshot().first { it.id == "held" }.state)
        assertEquals(0, book.snapshot().first { it.id == "held" }.soldContracts)
        book.onQuote(q(now + 1_000, .23, 2.0), false)
        assertEquals(2, book.snapshot().first { it.id == "held" }.soldContracts)
        book.onQuote(q(now + 1_100, .23, 2.0).copy(bookAtMs = now + 1_000), false)
        assertEquals(2, book.snapshot().first { it.id == "held" }.soldContracts)
        book.onQuote(q(now + 2_000, .22), false)
        assertEquals(ScalpState.CLOSED, book.snapshot().first { it.id == "held" }.state)
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.3: pins pre-1.0.3 entries/exits of a strategy now retuned toward the fair-gap rule (24–42¢, BRTI gap 10–21¢ after fee, no stop/trail, ≈4.5 min hold); covered by ReleaseGate103FairGapTest") @Test fun profitTrailAndPendingExitSurviveProcessRestart() {
        val (book, store) = book(open())
        book.onQuote(q(now, .60), false)
        book.flushWrites()
        val restarted = ScalpBook(store = store, variants = emptyList(), liveExtras = true)
        restarted.onQuote(q(now + 1_000, .56), false)
        val pending = restarted.snapshot().first { it.id == "held" }
        assertEquals(ScalpState.PENDING_EXIT, pending.state)
        assertTrue(pending.exitReason!!.contains("trail"))
        restarted.flushWrites()
        val again = ScalpBook(store = store, variants = emptyList(), liveExtras = true)
        again.onQuote(q(now + 2_000, .58), false) // price recovered; triggered exit stays latched
        assertEquals(ScalpState.CLOSED, again.snapshot().first { it.id == "held" }.state)
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.3: pins pre-1.0.3 entries/exits of a strategy now retuned toward the fair-gap rule (24–42¢, BRTI gap 10–21¢ after fee, no stop/trail, ≈4.5 min hold); covered by ReleaseGate103FairGapTest") @Test fun closeExitLatchesWithoutLiquidityAndEvenWhenEntriesAreDisabled() {
        val (book, _) = book(open())
        book.onQuote(q(close - 50_000, null, 0.0), false)
        val pending = book.snapshot().first { it.id == "held" }
        assertEquals(ScalpState.PENDING_EXIT, pending.state)
        assertTrue(pending.exitReason!!.contains("before close"))
        book.onQuote(q(close - 49_000, .38), false)
        assertEquals(ScalpState.CLOSED, book.snapshot().first { it.id == "held" }.state)
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.3: pins pre-1.0.3 entries/exits of a strategy now retuned toward the fair-gap rule (24–42¢, BRTI gap 10–21¢ after fee, no stop/trail, ≈4.5 min hold); covered by ReleaseGate103FairGapTest") @Test fun staleCloseBookTriggersRiskDecisionButCannotFill() {
        val (book, _) = book(open())
        book.onQuote(q(close - 50_000, .40).copy(bookGap = true), false)
        val pending = book.snapshot().first { it.id == "held" }
        assertEquals(ScalpState.PENDING_EXIT, pending.state)
        assertEquals(0, pending.soldContracts)
        book.onQuote(q(close - 49_000, .40), false)
        assertEquals(ScalpState.CLOSED, book.snapshot().first { it.id == "held" }.state)
    }

    @Test fun noFreshEntryInFinalMinuteOrFromStaleGappedOrSignalBook() {
        val pending = open().copy(state = ScalpState.PENDING_ENTRY, contracts = 0, entryPrice = null, entryAtMs = null,
            signalAtMs = now - 2_000)
        for (quote in listOf(q(now).copy(bookAtMs = now - 4_000), q(now).copy(bookGap = true),
            q(now).copy(bookAtMs = pending.signalAtMs))) {
            val (book, _) = book(pending)
            book.onQuote(quote, false)
            assertEquals(ScalpState.PENDING_ENTRY, book.snapshot().first { it.id == "held" }.state)
        }
        val (book, _) = book(pending.copy(signalAtMs = close - 52_000))
        book.onQuote(q(close - 50_000), false)
        assertEquals(ScalpState.NO_FILL, book.snapshot().first { it.id == "held" }.state)
    }
}
