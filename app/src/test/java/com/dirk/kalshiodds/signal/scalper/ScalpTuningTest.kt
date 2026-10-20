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
            engine = ScalperEngine(tuning = tuning), fairYes = { _, _ -> 0.98 }, strategies = ScalpStrategy.values().toList()
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

    // ---- the extra limits (1.12) ------------------------------------------------------

    private fun lateFavWindow(tuning: ScalpTuning, closeOf: (String) -> Long? = { 900_000L }, fair: Double = 0.98) = PaperScalper(
        model = null, ledger = ScalperLedger(), topOfBook = { null }, bookLevels = { null }, closeMs = closeOf,
        engine = ScalperEngine(tuning = tuning), fairYes = { _, _ -> fair }, strategies = ScalpStrategy.values().toList()
    )

    private fun runWindow(s: PaperScalper, ticker: String, openMs: Long) {
        for (sec in 0..850) {
            s.onTrade(ticker, "no", 0.90, 20.0, openMs + sec * 1000L + 100L)
            s.onTrade(ticker, "yes", 0.92, 20.0, openMs + sec * 1000L + 200L)
        }
    }

    @Test
    fun untouchedLimitsChangeNothing() {
        for (st in ScalpStrategy.values()) {
            val d = Tuned(st, ScalpKnobs(), 10)
            assertEquals("late comeback starts at minute 10, the rest at the open", st.startMinute * 60, d.fromSecond)
            assertEquals(900, d.untilSecond)
            assertTrue(d.sideAllowed("YES") && d.sideAllowed("NO"))
            assertEquals(null, d.maxSpread)
            assertEquals(null, d.maxOrdersPerWindow)
            assertEquals(0L, d.cooldownMs)
            assertEquals(null, d.dailyLossUsd)
            assertEquals(st.trail, d.trail)
            assertEquals(20_000L, d.entryWaitMs(20_000L))
            assertTrue(ArenaTable.limits(d).isEmpty())
        }
    }

    @Test
    fun oneSideOnlyAWindowAndASpreadLimitStopTheBuy() {
        val st = ScalpStrategy.KASHI_FAV_BASE
        fun run(knobs: ScalpKnobs): Int {
            val tuning = ScalpTuning()
            tuning.set(st, knobs)
            val s = lateFavWindow(tuning)
            runWindow(s, t, 0L)
            return s.engine.workingFor(st, t, "YES")
        }
        assertEquals("untouched buys YES", 1, run(ScalpKnobs()))
        assertEquals("NO only", 0, run(ScalpKnobs(side = "NO")))
        assertEquals("YES only still buys", 1, run(ScalpKnobs(side = "YES")))
        assertEquals("stops at minute 14: the late window never comes", 0, run(ScalpKnobs(untilMinute = 14)))
        assertEquals("a 1¢ spread limit skips the 2¢ spread", 0, run(ScalpKnobs(maxSpreadCents = 1.0)))
        assertEquals("a 2¢ spread limit lets it through", 1, run(ScalpKnobs(maxSpreadCents = 2.0)))
    }

    @Test
    fun theDailyLossLimitPausesItUntilTheNextDay() {
        val st = ScalpStrategy.KASHI_FAV_BASE
        val t2 = "KXBTC15M-26OCT091230-30"
        val t3 = "KXBTC15M-26OCT101215-15"
        val day = 86_400_000L
        val closes = mapOf(t to 900_000L, t2 to 1_800_000L, t3 to day + 900_000L)
        val tuning = ScalpTuning()
        tuning.set(st, ScalpKnobs(dailyLossUsd = 5.0))
        val s = lateFavWindow(tuning, { closes[it] })
        runWindow(s, t, 0L)
        assertEquals(1, s.engine.workingFor(st, t, "YES"))
        // YES at 92¢ loses: about −$9.2 on 10 contracts, past the $5 limit.
        s.settle(t, "no", 900_500L)
        assertTrue(s.todayPnl(st, 900_500L) < -5.0)
        runWindow(s, t2, 900_000L)
        assertEquals("paused for the rest of the day", 0, s.engine.workingFor(st, t2, "YES"))
        runWindow(s, t3, day)
        assertEquals("a new UTC day trades again", 1, s.engine.workingFor(st, t3, "YES"))
    }

    @Test
    fun aTrailingStopAndALongerBidWaitCanBeSwitchedOn() {
        val tuning = ScalpTuning()
        tuning.set(ScalpStrategy.DIP_HUNTER, ScalpKnobs(trail = true, stopCents = 2.0, targetCents = 20.0))
        tuning.set(ScalpStrategy.DIP_HUNTER_REST, ScalpKnobs(bidWaitSeconds = 60))
        val e = ScalperEngine(tuning = tuning)
        e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.50, noQueue, 0L)
        assertTrue(e.onClock(t, 1_000L, 0.53, 0.46).isEmpty())
        // 51¢ is 2¢ under the 53¢ best bid: a trailing stop, though still above the entry.
        val c = e.onClock(t, 2_000L, 0.51, 0.48).filterIsInstance<ScalpEvent.Closed>().single().scalp
        assertEquals(ExitKind.STOP, c.kind)
        assertNotNull(e.post(ScalpStrategy.DIP_HUNTER_REST, t, "YES", 0.40, 100.0, 0.0, 0.0, 10_000L))
        assertTrue("still resting at 30 s with your 60 s wait", e.onClock(t, 40_000L, 0.40, 0.59).none { it is ScalpEvent.Unfilled })
        assertTrue(e.onClock(t, 71_000L, 0.40, 0.59).any { it is ScalpEvent.Unfilled })
    }

    @Test
    fun theRulesListYourLimitsAndOldSavedSettingsStillLoad() {
        val tuned = Tuned(
            ScalpStrategy.BOUNCE,
            ScalpKnobs(fromMinute = 2, untilMinute = 12, side = "YES", maxSpreadCents = 3.0, maxOrdersPerWindow = 2,
                cooldownSeconds = 30, dailyLossUsd = 25.0),
            10
        )
        assertEquals(
            listOf(
                "Opens orders only from minute 2 until minute 12.",
                "Buys YES only.",
                "Skips a spread wider than 3¢.",
                "At most 2 orders a window.",
                "Waits 30 s after an order before the next.",
                "Pauses for the rest of the day (UTC) once it is down \$25 today."
            ),
            ArenaTable.limits(tuned)
        )
        assertTrue(Tuned(ScalpStrategy.BOUNCE, ScalpKnobs(fromMinute = 10, untilMinute = 5), 10).noMinutes)
        // Settings saved before these fields existed.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString(serializer<Map<String, ScalpKnobs>>(), """{"BOUNCE":{"enabled":true,"contracts":4}}""")
        assertEquals(4, old["BOUNCE"]!!.contracts)
        assertEquals(null, old["BOUNCE"]!!.side)
    }
}
