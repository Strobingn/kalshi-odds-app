package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.insights.ScalpFreeze
import com.dirk.kalshiodds.decision.insights.ScalpInsights
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.21 paper retune: own rules, settle floor, bands, freeze, and manual sliders. */
class ScalpRetune1021Test {
    private val ownFav = listOf("sf16", "sf17", "sf18", "sf19", "sf20", "sf21", "sf22", "sf23", "sf24", "sf29", "sf31")

    @Test fun lateFavouritesAndFadeSkipTheFairGapEntryGate() {
        assertEquals((ownFav + listOf("fcfade", KeptScalpers.FADE_ID)).toSet(), FairGapTune.OWN_RULES)
        ownFav.forEach {
            assertFalse(it, FairGapTune.favUsesFairGapGate(it))
            assertFalse(it, FairGapTune.entryGated(it))
            assertTrue(it, FairGapTune.isTweaked(it))
            assertNull(it, ScalpRetune.cardNote(it))
        }
        assertTrue(FairGapTune.cardNoteMissing("sf41"))
        assertFalse(FairGapTune.favUsesFairGapGate("sf41"))
        assertFalse(FairGapTune.entryGated("sf41"))
        assertFalse(FairGapTune.entryGated("sf42"))
        assertTrue(FairGapTune.entryGated("sf01"))
        assertFalse(FairGapTune.entryGated("fcfade"))
        assertFalse(FairGapTune.entryGated("r-late"))
        assertTrue(Research.LATE in FairGapTune.KEEP)
        assertEquals(180.0, FairGapTune.SETTLE_MIN_S, 0.0)
    }

    @Test fun sf31OwnRuleStillRejectsUsHours() {
        val p = ShockFav.v("sf31")
        val usOpen = Instant.parse("2026-10-21T15:00:00Z").toEpochMilli()
        val usClose = usOpen + 900_000L
        assertTrue(ShockFav.usHours(usClose))
        assertFalse(ShockFav.favEntryOk(p, 0.96, 0.90, 40.0, "BTC", usClose))
        val offOpen = Instant.parse("2026-10-21T02:00:00Z").toEpochMilli()
        val offClose = offOpen + 900_000L
        assertFalse(ShockFav.usHours(offClose))
        assertTrue(ShockFav.favEntryOk(p, 0.96, 0.90, 40.0, "BTC", offClose))
        assertFalse(ShockFav.favEntryOk(p, 0.96, 0.90, 10.0, "BTC", offClose))
        assertTrue(FairGapTune.askAllowed("sf31", 0.90, favLeg = true))
        assertFalse(FairGapTune.askAllowed("sf31", 0.90, favLeg = false))
        assertFalse(FairGapTune.askAllowed("sf41", 0.90, favLeg = false))
    }

    @Test fun settleHoldFloorAndLateBandsKeepManualSliders() {
        assertEquals(180, Research.Params().lateMinS)
        assertEquals(10, Research.Params().clip)
        assertEquals(80, Research.Params().lateLoCents)
        assertEquals(95, Research.Params().lateHiCents)
        assertEquals(FairGapTune.Band(0.80, 0.95), Research.lateBand(Research.LATE, null))
        assertEquals(FairGapTune.Band(0.80, 0.95), Research.lateBand(Research.LATE, ScalpStrategySettings()))
        assertEquals(FairGapTune.Band(0.85, 0.95), Research.lateBand(Research.LATE, ScalpStrategySettings(minEntryCents = 85)))
        assertEquals(FairGapTune.Band(0.80, 0.90), Research.lateBand(Research.LATE, ScalpStrategySettings(maxEntryCents = 90)))
        assertEquals(FairGapTune.Band(0.70, 0.88), Research.lateBand(Research.LATE, ScalpStrategySettings(minEntryCents = 70, maxEntryCents = 88)))
        val t2 = Research.LATE_SPECS.getValue(Research.LATE_T2)
        assertEquals(180, t2.minS); assertEquals(42, t2.hiCents); assertEquals(10, t2.edgeCents)
        assertFalse(Research.lateWindowOk(179.0, Research.LATE, 180, 300))
        assertTrue(Research.lateWindowOk(180.0, Research.LATE_T1, 180, 300))
        assertFalse(Research.lateWindowOk(179.0, Research.LATE_T2, 180, 300))
    }

    @Test fun fairDefaultsApplyOnlyWhenTheSliderIsUnset() {
        assertTrue(FairGapTune.priceBlocked("fair", 0.41, null))
        assertFalse(FairGapTune.priceBlocked("fair", 0.40, null))
        assertFalse(FairGapTune.priceBlocked("fair", 0.50, ScalpStrategySettings(maxEntryCents = 55)))
        assertTrue(FairGapTune.priceBlocked("fair-t1", 0.45, ScalpStrategySettings()))
        assertFalse(FairGapTune.priceBlocked("fair-t2", 0.33, ScalpStrategySettings(maxEntryCents = 33)))
        assertTrue(FairGapTune.priceBlocked("fairh60", 0.39, null))
        assertFalse(FairGapTune.priceBlocked("fairh60", 0.40, null))
        assertFalse(FairGapTune.priceBlocked("fairh60", 0.25, ScalpStrategySettings(minEntryCents = 25)))
        assertTrue(FairGapTune.priceBlocked("fairh60", 0.24, ScalpStrategySettings(minEntryCents = 25)))
        assertFalse(FairGapTune.priceBlocked("sf41", 0.50, null))
        var learned = ScalpStrategySettings(aiLearns = true)
        repeat(15) { learned = Research.learn("fair", learned.copy(aiN = 2), -1.0, FairGapTune.FAIR_MAX_CENTS) }
        assertTrue(learned.maxEntryCents!! >= FairGapTune.FAIR_MAX_CENTS - 10)
        assertTrue(learned.maxEntryCents!! <= FairGapTune.FAIR_MAX_CENTS)
        val manual = Research.learn("fair", ScalpStrategySettings(maxEntryCents = 52, aiLearns = true, aiN = 2), -1.0, FairGapTune.FAIR_MAX_CENTS)
        assertEquals(51, manual.maxEntryCents)
        val edge = Research.learn("r-late", ScalpStrategySettings(aiLearns = true, aiN = 2), -1.0, 99)
        assertEquals(11, edge.edgeCents)
        assertTrue(edge.edgeCents!! <= Research.defaultEdge("r-late") + 4)
    }

    @Test fun fadeBandIs40To49AndASliderReplacesIt() {
        assertEquals(0.40 to 0.49, ForecastScalp.entryBand("fcfade", null))
        assertEquals(0.40 to 0.49, ForecastScalp.entryBand("fcfade-h600", ScalpStrategySettings()))
        assertEquals(0.40 to 0.60, ForecastScalp.entryBand("fcride", null))
        assertEquals(0.35 to 0.55, ForecastScalp.entryBand("fcfade", ScalpStrategySettings(minEntryCents = 35, maxEntryCents = 55)))
        assertTrue(ForecastScalp.wantsEntry(0.45, 0.01, 0.45, 0.30, 300.0, bandHi = 0.49))
        assertFalse(ForecastScalp.wantsEntry(0.55, 0.01, 0.55, 0.30, 300.0, bandHi = 0.49))
        assertTrue(ForecastScalp.wantsEntry(0.55, 0.01, 0.55, 0.30, 300.0))
    }

    @Test fun removedScalpersHaveNoFrozenNoteAndAreNotOnTheBoard() {
        listOf("sf30", "sf32", "mkdip", "diph60", "dipnotp", "r-kelly", "xrev", "sf51", "v2-sf51", "dip", "v2-llm").forEach { code ->
            assertNull(code, ScalpModels.modelByCode(code))
            assertFalse(code, code in ScalpFreeze.DEFAULT_FROZEN)
            assertNull(code, ScalpRetune.cardNote(code))
            assertNull(code, ScalpFreeze.reason(code, emptyList()))
        }
        assertNotNull(ScalpModels.modelByCode("sf41"))
        assertNotNull(ScalpModels.modelByCode("sf42"))
        assertNull(ScalpRetune.cardNote("sf41"))
        val book = ScalpBook()
        ScalpInsights(book, MemoryScalpControls(), null) {}.start()
        listOf("mkdip", "xrev", "sf51", "v2-sf51", "r-kelly", "sf41").forEach { assertFalse(it, it in book.benched) }
        assertEquals(-2.5, ScalpRetune.netPerBetUsd(-10.0, 4)!!, 1e-9)
        assertNull(ScalpRetune.netPerBetUsd(-10.0, 0))
    }

    @Test fun makerDipAndBookImbalanceDoNotOpenUnlessForced() {
        val maker = ScalpModels.MAKER_DIP_PARAMS
        val ticker = "KXBTC15M-26OCT091015-15"
        val close = ScalpTicker.closeMs(ticker)!!
        fun q(now: Long, bid: Double, ask: Double) =
            ScalpRule.Quote(ticker, now, close, now, bid, 500.0, ask, 500.0, 100_000.0, 100_000.0, 3e-5)
        val tune = InMemoryScalpTuneStore(ScalpTuneState(paramsByCoin = mapOf(ScalpParams.key("BTC", ScalpStrategy.DIP_HUNTER) to maker.id)))
        val ids = AtomicInteger()
        val blocked = ScalpBook(InMemoryScalpPersistence(), emptyList(), tune, bankrollUsd = { 100_000.0 }) { "b${ids.incrementAndGet()}" }
        val t0 = close - 600_000L
        blocked.onQuote(q(t0, 0.44, 0.45), true)
        blocked.onQuote(q(t0 + 30_000L, 0.34, 0.35), true)
        assertTrue(blocked.allTrades().none { it.variantId == maker.id })

        val controls = MemoryScalpControls()
        controls.save(controls.state.value.copy(benchOverrides = mapOf("mkdip" to "ACTIVE")))
        val forced = ScalpBook(InMemoryScalpPersistence(), emptyList(), tune, bankrollUsd = { 100_000.0 }, controls = controls) { "f${ids.incrementAndGet()}" }
        forced.onQuote(q(t0, 0.44, 0.45), true)
        forced.onQuote(q(t0 + 30_000L, 0.34, 0.35), true)
        assertTrue(forced.allTrades().none { it.variantId == maker.id })

        val sf = shockBook()
        val at = close - 800_000L
        warm(sf, at)
        sf.onShockFavQuote(imbalance(at), true)
        assertTrue(sf.allTrades().none { it.variantId == "sf30" || it.variantId == "sf32" })
        val forceSf = MemoryScalpControls()
        forceSf.save(forceSf.state.value.copy(benchOverrides = mapOf("sf30" to "ACTIVE")))
        val open = shockBook(forceSf)
        warm(open, at)
        open.onShockFavQuote(imbalance(at), true)
        assertTrue(open.allTrades().none { it.variantId == "sf30" || it.variantId == "sf32" })
    }

    private fun shockBook(controls: ScalpControlsRepository = MemoryScalpControls()): ScalpBook {
        val ids = AtomicInteger()
        return ScalpBook(InMemoryScalpPersistence(), emptyList(), bankrollUsd = { 100_000.0 }, controls = controls) { "s${ids.incrementAndGet()}" }
    }

    private fun warm(book: ScalpBook, at: Long) {
        val rnd = java.util.Random(3)
        var spot = 100_000.0
        for (k in 400 downTo 1) {
            spot += (rnd.nextDouble() - 0.5) * 8
            book.tickSpot("BTC", spot, at - k * 1000L)
        }
    }

    private fun imbalance(at: Long): ScalpRule.Quote {
        val ticker = "KXBTC15M-26OCT181300-00"
        val close = at + 800_000L
        return ScalpRule.Quote(ticker, at, close, at, 0.40, 900.0, 0.41, 20.0, 100_000.0, 100_000.0, 3e-5)
    }
}

private fun FairGapTune.cardNoteMissing(code: String) = ScalpRetune.cardNote(code) == null
