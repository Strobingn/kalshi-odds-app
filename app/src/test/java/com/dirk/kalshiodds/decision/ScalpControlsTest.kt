package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.ScalpControlSpec.Key as K
import org.junit.Assert.*
import org.junit.Test

class ScalpControlsTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val now = close - 400_000L
    private fun config() = ScalpControlSpec.defaults("sf41").set(K.MIN_ASK, 1.0).set(K.MAX_ASK, 60.0)
        .set(K.TARGET, 0.0).set(K.TURN, 0.0).set(K.TRAIL, 0.0).set(K.STOP, 10.0)
        .set(K.ARM, 4.0).set(K.CLIP, 5.0).set(K.DELAY, 2000.0).set(K.HOLD, 0.0)
    private fun q(at: Long, bid: Double = .40, size: Double = 10.0) = ScalpRule.Quote(ticker, at, close, at,
        bid, size, bid + .01, size, 100_000.0, 100_000.0, 3e-5)
    private fun pending(c: ScalpControlSpec.Config = config()) = ScalpTrade("manual", ticker, "YES",
        ScalpState.PENDING_ENTRY, now, .41, .50, note = "ctl1=${c.encode()} · ctlrev=${c.revision}",
        ruleVersion = "${ScalpRule.VERSION}|sf41|P")
    private fun held(c: ScalpControlSpec.Config = config()) = pending(c).copy(state = ScalpState.OPEN,
        contracts = 10, entryPrice = .40, entryFeeUsd = .17, entryAtMs = now - 5000)

    @Test fun newSignalsSnapshotSavedSettingsAndPauseSuppressesNewCandidates() {
        val controls = ScalpControls()
        val saved = ScalpControlSpec.defaults("fair").set(K.CLIP, 5.0).set(K.DELAY, 2000.0)
            .set(K.TARGET, 0.0).set(K.TURN, 0.0)
        controls.apply("fair", saved)
        val book = ScalpBook(variants = emptyList(), controls = controls)
        val signal = q(now, .20)
        val changes = book.onQuote(signal, true)
        val pending = changes.first { ScalpControlSpec.code(it) == "fair" }
        assertEquals(saved, ScalpControlSpec.frozen(pending))
        controls.apply("fair", saved.set(K.CLIP, 9.0))
        book.onQuote(signal.copy(nowMs = now + 2000, bookAtMs = now + 2000), false)
        assertEquals(5, book.snapshot().first { it.id == pending.id }.contracts)
        controls.apply("fair", saved.copy(enabled = false))
        val paused = ScalpBook(variants = emptyList(), controls = controls)
        assertFalse(paused.onQuote(signal, true).any { ScalpControlSpec.code(it) == "fair" })
        assertFalse(paused.snapshot().any { ScalpControlSpec.code(it) == "fair" })
    }
    @Test fun everyModelHasACompleteValidConfigurationAndOnlyApplicableSignalControls() {
        for (m in ScalpModels.ALL) {
            val c = ScalpControlSpec.defaults(m.code)
            assertNull("${m.code}: ${c.error()}", c.error())
            assertEquals(c, ScalpControlSpec.decode(c.encode()))
            assertEquals(ScalpControlSpec.Key.values().size, c.values.size)
        }
        assertFalse(ScalpControlSpec.keys("v2-sf41").any { it.group == "Signal" })
        assertTrue(K.IMBALANCE in ScalpControlSpec.keys("sf30"))
        assertTrue(K.FORECAST in ScalpControlSpec.keys("fcride"))
        assertFalse(K.SHOCK in ScalpControlSpec.keys("sf19"))
        assertFalse(K.CONVERGENCE in ScalpControlSpec.keys("sf27"))
    }
    @Test fun invalidRangesAreRejectedAndPersistenceIsPerModel() {
        val store = MemoryScalpControlStore(); val controls = ScalpControls(store)
        controls.apply("sf41", config())
        assertEquals(config(), ScalpControls(store).get("sf41"))
        assertNull(controls.get("sf42"))
        try { controls.apply("sf41", config().set(K.MIN_ASK, 80.0)); fail("bad range accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(config(), controls.get("sf41"))
        assertNull(ScalpControlSpec.decode("1:NaN,Infinity"))
        controls.reset("sf41"); assertNull(ScalpControls(store).get("sf41"))
    }
    @Test fun presetsAreIndependentFiltersAndProfitIfWinDoesNotClaimEV() {
        val c = ScalpControlSpec.cheapEarly("fair")
        assertEquals(40.0, c[K.MAX_ASK], 0.0)
        assertEquals(540.0, c[K.MIN_LEFT], 0.0)
        assertEquals(660.0, c[K.MAX_LEFT], 0.0)
        assertEquals(0.0, c[K.WIN_PROFIT], 0.0)
        assertNull(c.error())
    }
    @Test fun sourceSignalSlidersChangeTheActualRuleInputs() {
        val controls = ScalpControls()
        controls.apply("dip", ScalpControlSpec.defaults("dip").set(K.TRIGGER, 20.0).set(K.SPREAD, 5.0))
        val p = controls.params(ScalpParams.seedFor("BTC", ScalpStrategy.DIP_HUNTER))
        assertEquals(.20, p.minGap, 1e-9); assertEquals(.05, p.maxSpread, 1e-9)
        controls.apply("sf19", ScalpControlSpec.defaults("sf19").set(K.FAIR, 98.0).set(K.TRIGGER, 9.0).set(K.LOOKBACK, 15.0))
        val sf = controls.shock(ShockFav.v("sf19"))
        assertEquals(.98, sf.fmin, 1e-9); assertEquals(.09, sf.gap, 1e-9); assertEquals(15, sf.yS)
    }
    @Test fun entryNeedsDelayFreshUngappedBookAndPriceDiscipline() {
        val t = pending()
        assertNull(ScalpManual.step(t, q(now + 1000)))
        assertNull(ScalpManual.step(t, q(now + 2000).copy(bookAtMs = now)))
        assertNull(ScalpManual.step(t, q(now + 2000).copy(bookGap = true)))
        assertNull(ScalpManual.step(t, q(now + 6000).copy(bookAtMs = now)))
        assertNull(ScalpManual.step(t, q(now + 2000, .44))) // ask beyond saved 1c slippage
        assertEquals(ScalpState.NO_FILL, ScalpManual.step(t, q(now + 7000))!!.state)
    }
    @Test fun displayedDepthIsWalkedAndBothLegsReconcileThroughPartialExits() {
        val t = pending()
        val entry = ScalpManual.step(t, q(now + 2000).copy(noBidsDesc = listOf(.59 to 3.0, .58 to 2.0)))!!
        assertEquals(5, entry.contracts); assertEquals(.414, entry.entryPrice!!, 1e-9)
        assertEquals(.10, entry.entryFeeUsd, 1e-9)
        val trigger = ScalpManual.step(entry, q(now + 3000, .27))!!
        assertEquals(ScalpState.PENDING_EXIT, trigger.state); assertEquals(0, trigger.soldContracts)
        val partial = ScalpManual.step(trigger, q(now + 4000, .26, 2.0))!!
        assertEquals(2, partial.soldContracts)
        assertNull(ScalpManual.step(partial, q(now + 4100, .26, 2.0).copy(bookAtMs = now + 4000)))
        val done = ScalpManual.step(partial, q(now + 5000, .25))!!
        assertEquals(ScalpState.CLOSED, done.state)
        assertEquals(-.97, done.netUsd!!, 1e-9)
        assertEquals(done.proceedsUsd - done.exitFeeUsd - done.entryCostUsd, done.netUsd!!, 1e-9)
    }
    @Test fun impossibleWinProfitNeverCreatesAFill() {
        assertNull(ScalpManual.step(pending(config().set(K.WIN_PROFIT, 10.0)), q(now + 2000)))
    }
    @Test fun frozenRulesAndTrailingStateSurviveRestartAndSettingsReset() {
        val t = held(config().set(K.TRAIL, 2.0))
        val peak = ScalpManual.step(t, q(now, .50))!!
        val store = InMemoryScalpPersistence().also { it.seed(listOf(peak)) }
        val controls = ScalpControls(); controls.apply("sf41", config().copy(enabled = false)); controls.reset("sf41")
        val book = ScalpBook(store = store, variants = emptyList(), controls = controls)
        book.onQuote(q(now + 1000, .47), false)
        val out = book.snapshot().single()
        assertEquals(ScalpState.PENDING_EXIT, out.state)
        assertEquals("manual profit trail", out.exitReason)
        book.flushWrites()
        val restarted = ScalpBook(store = store, variants = emptyList())
        restarted.onQuote(q(now + 2000, .49), false)
        assertEquals(ScalpState.CLOSED, restarted.snapshot().single().state)
    }
    @Test fun clockExitsLatchThroughMissingLiquidityAndDisabledEntries() {
        val t = held()
        val pending = ScalpManual.step(t, q(close - 50_000).copy(yesBid = null, bookGap = true))!!
        assertEquals(ScalpState.PENDING_EXIT, pending.state)
        val done = ScalpManual.step(pending, q(close - 49_000, .38))!!
        assertEquals(ScalpState.CLOSED, done.state)
    }
    @Test fun minimumHoldAndEntryFiltersAreEnforcedAndNoSideIsMirroredCorrectly() {
        assertFalse(ScalpManual.entryAllowed(pending(), q(now, .60), config()))
        val t = pending().copy(side = "NO", signalAsk = .40)
        val out = ScalpManual.step(t, q(now + 2000, .60))!!
        assertEquals(.40, out.entryPrice!!, 1e-9)
        assertEquals(5, out.contracts)
    }
    @Test fun fairGapQuotePathCannotApplyItsStopsToAShockFavouriteTrade() {
        val trade = held().copy(note = "original sf19", ruleVersion = "${ScalpRule.VERSION}|sf19f|P")
        val store = InMemoryScalpPersistence().also { it.seed(listOf(trade)) }
        val book = ScalpBook(store = store, variants = emptyList())
        book.onQuote(q(close - 50_000, .40), false)
        assertEquals(ScalpState.OPEN, book.snapshot().single().state)
    }
    @Test fun uncompletedWindowsCannotTriggerAdaptiveLossTrial() {
        val windows = (1..3).map { ScalpWindowAgg("w$it", "v2-sf41", 10, -1.0, now - it * 900_000) }
        assertTrue(ScalpV2.plan("v2-sf41", windows, now).adjusted)
        assertFalse(ScalpV2.plan("v2-sf41", windows, now, unfinishedWindows = setOf("w1")).adjusted)
    }
}
