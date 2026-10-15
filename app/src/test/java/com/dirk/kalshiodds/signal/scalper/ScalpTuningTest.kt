package com.dirk.kalshiodds.signal.scalper

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your per-strategy settings: untouched strategies run as shipped; changed ones trade on yours. */
class ScalpTuningTest {

    private val t = "KXBTC15M-26OCT091215-15"
    private val noQueue: (String, Double) -> Double? = { _, _ -> null }

    @Test
    fun untouchedStrategiesRunOnTheirOwnDefaults() {
        for (st in ScalpStrategy.values()) {
            val d = Tuned(st, ScalpKnobs(), 10)
            assertTrue(st.name, d.enabled)
            assertEquals(st.name, 10, d.contracts)
            assertEquals(st.name, st.targetDollars, d.targetDollars, 0.0)
            assertEquals(st.name, st.stopDollars, d.stopDollars, 0.0)
            assertEquals(st.name, st.timeoutMs, d.timeoutMs)
            assertEquals(st.name, st.maxPerSide, d.maxPerSide)
            assertEquals(st.name, st.cadenceSeconds, d.cadenceSeconds)
            assertEquals(st.name, st.entryMin, d.entryMin, 0.0)
            assertEquals(st.name, st.entryMax, d.entryMax, 0.0)
            assertEquals(st.name, 0.123, d.triggerValue(0.123), 0.0)
            assertEquals("the rules read the same", ArenaTable.rules(st), ArenaTable.rules(st, d))
        }
        assertTrue(ScalpKnobs().isDefault)
    }

    @Test
    fun theEngineTradesOnYourSizeTargetAndBand() {
        val tuning = ScalpTuning()
        tuning.set(ScalpStrategy.BOUNCE, ScalpKnobs(contracts = 3, targetCents = 5.0, entryMinCents = 40.0, entryMaxCents = 60.0))
        val e = ScalperEngine(tuning = tuning)
        assertTrue("outside your band", e.buyNow(ScalpStrategy.BOUNCE, t, "YES", 0.65, noQueue, 0L).isEmpty())
        val order = e.buyNow(ScalpStrategy.BOUNCE, t, "YES", 0.50, noQueue, 0L).filterIsInstance<ScalpEvent.Posted>().single().order
        assertEquals(3, order.contracts)
        // Your +5¢ target: a print through 55¢ lifts the offer.
        val c = e.onTrade(t, true, 0.56, 50.0, 1_000L, noQueue).filterIsInstance<ScalpEvent.Closed>().single().scalp
        assertEquals(ExitKind.TARGET, c.kind)
        assertEquals(0.55, c.exitPrice, 1e-9)
        // Another strategy is untouched: 10 contracts.
        val other = e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.50, noQueue, 0L).filterIsInstance<ScalpEvent.Posted>().single().order
        assertEquals(10, other.contracts)
    }

    @Test
    fun yourStopAndTimeOutApplyAndAHoldCanBeGivenAStop() {
        val tuning = ScalpTuning()
        tuning.set(ScalpStrategy.DIP_HUNTER, ScalpKnobs(stopCents = 10.0, timeoutSeconds = 300))
        tuning.set(ScalpStrategy.LATE_FAVORITE, ScalpKnobs(stopCents = 10.0))
        val e = ScalperEngine(tuning = tuning)
        e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.50, noQueue, 0L)
        assertTrue("2¢ down is no longer the stop, 30 s is no longer the time-out", e.onClock(t, 60_000L, 0.48, 0.51).isEmpty())
        assertEquals(ExitKind.STOP, e.onClock(t, 61_000L, 0.40, 0.59).filterIsInstance<ScalpEvent.Closed>().single().scalp.kind)
        e.buyNow(ScalpStrategy.LATE_FAVORITE, t, "YES", 0.85, noQueue, 0L)
        assertEquals(ExitKind.STOP, e.onClock(t, 62_000L, 0.75, 0.24).filterIsInstance<ScalpEvent.Closed>().single().scalp.kind)
    }

    @Test
    fun aSwitchedOffStrategyOpensNothingAndATriggerMovesItsSignal() {
        val tuning = ScalpTuning()
        tuning.set(ScalpStrategy.KASHI_FAV_STRICT, ScalpKnobs(enabled = false))
        tuning.set(ScalpStrategy.KASHI_FAV_97, ScalpKnobs(trigger = 99.0))
        val s = PaperScalper(
            model = null, ledger = ScalperLedger(), topOfBook = { null }, bookLevels = { null }, closeMs = { 900_000L },
            engine = ScalperEngine(tuning = tuning), fairYes = { _, _ -> 0.98 }
        )
        for (sec in 0..850) {
            s.onTrade(t, "no", 0.90, 20.0, sec * 1000L + 100L)
            s.onTrade(t, "yes", 0.92, 20.0, sec * 1000L + 200L)
        }
        assertEquals("switched off", 0, s.engine.workingFor(ScalpStrategy.KASHI_FAV_STRICT, t, "YES"))
        assertEquals("fair 98% is under your 99%", 0, s.engine.workingFor(ScalpStrategy.KASHI_FAV_97, t, "YES"))
        assertEquals("untouched", 1, s.engine.workingFor(ScalpStrategy.KASHI_FAV_BASE, t, "YES"))
    }

    @Test
    fun settingsAreSavedOnlyWhereChangedAndCanAllBeReset() {
        var saved: Map<String, ScalpKnobs> = emptyMap()
        val tuning = ScalpTuning(persist = { saved = it })
        tuning.set(ScalpStrategy.BOUNCE, ScalpKnobs(contracts = 4))
        tuning.set(ScalpStrategy.ML_REST, ScalpKnobs())
        assertEquals(setOf("BOUNCE"), saved.keys)
        assertEquals(setOf(ScalpStrategy.BOUNCE), tuning.changed())
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val ser = serializer<Map<String, ScalpKnobs>>()
        val back = json.decodeFromString(ser, json.encodeToString(ser, saved))
        assertEquals(4, ScalpTuning(back).tuned(ScalpStrategy.BOUNCE).contracts)
        tuning.reset(ScalpStrategy.BOUNCE)
        assertTrue(saved.isEmpty())
        tuning.set(ScalpStrategy.LONG_SHOT, ScalpKnobs(enabled = false))
        tuning.resetAll()
        assertTrue(tuning.tuned(ScalpStrategy.LONG_SHOT).enabled)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun everyStrategyWithATriggerHasAUsableRangeAndTheRulesShowYourValues() {
        for (st in ScalpStrategy.values()) {
            val spec = ScalpTriggers.of(st) ?: continue
            assertTrue(st.name, spec.min < spec.max && spec.step > 0.0)
            if (!spec.default.isNaN()) assertTrue("${st.name} default inside its range", spec.default in spec.min..spec.max)
        }
        assertNotNull(ScalpTriggers.of(ScalpStrategy.DIP_HUNTER))
        val tuned = Tuned(ScalpStrategy.DIP_HUNTER, ScalpKnobs(contracts = 25, trigger = 4.0, enabled = false), 10)
        val r = ArenaTable.rules(ScalpStrategy.DIP_HUNTER, tuned)
        assertTrue(r[0], r[0].endsWith("Your setting: fall in 10 s 4¢."))
        assertTrue(r[4], r[4].startsWith("25 contracts an order"))
        assertEquals("Switched off in its settings: it opens nothing new.", r.last())
        assertEquals(0.04, tuned.triggerValue(0.02), 1e-12)
        assertFalse(tuned.enabled)
    }
}
