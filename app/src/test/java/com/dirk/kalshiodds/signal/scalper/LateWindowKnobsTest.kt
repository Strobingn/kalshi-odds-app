package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The late strategies' seconds-left window and Kashi's edge as settings, and late comeback's start minute. */
class LateWindowKnobsTest {

    private val t = "KXBTC15M-26OCT091215-15"
    private val base = ScalpStrategy.KASHI_FAV_BASE

    private fun runner(knobs: Map<String, ScalpKnobs>, fair: Double = 0.98) = PaperScalper(
        model = null, ledger = ScalperLedger(), topOfBook = { null }, bookLevels = { null }, closeMs = { 900_000L },
        engine = ScalperEngine(tuning = tuningFor(knobs)), fairYes = { _, _ -> fair }, strategies = listOf(base)
    )

    /**
     * [knobs] on top of the strategy's researched band. These tests are about the late-window settings, not about
     * the arena's 15–58¢, so the favourite keeps its own prices unless [knobs] names a band itself.
     */
    private fun tuningFor(knobs: Map<String, ScalpKnobs>): ScalpTuning {
        val tuning = ScalpTuning(knobs)
        val k = tuning.knobs(base)
        if (k.entryMinCents == null && k.entryMaxCents == null) tuning.useOwnBands(base)
        return tuning
    }

    private fun prints(s: PaperScalper, from: Int, to: Int, bid: Double, ask: Double) {
        for (sec in from..to) {
            s.onTrade(t, "no", bid, 20.0, sec * 1000L + 100L)
            s.onTrade(t, "yes", ask, 20.0, sec * 1000L + 200L)
        }
    }

    @Test
    fun untouchedTheyKeepTheirOwnWindowAndEdge() {
        for (st in ScalpStrategy.values()) {
            val d = Tuned(st, ScalpKnobs(), 10)
            val f = st.fav
            if (f == null) {
                assertNull(st.name, d.secondsLeft)
                assertNull(st.name, d.favGap)
            } else {
                assertEquals(st.name, f.tteMin..f.tteMax, d.secondsLeft)
                assertEquals(st.name, f.gap, d.favGap!!, 1e-12)
            }
        }
        val w = Tuned(base, ScalpKnobs(secondsLeftMax = 120, secondsLeftMin = 1, edgeCents = 4.0), 10)
        assertEquals("never under 5 s", 5..120, w.secondsLeft)
        assertEquals(0.04, w.favGap!!, 1e-12)
        assertTrue(ArenaTable.limits(w).contains("Buys only with 5–120 s left."))
        assertTrue(ArenaTable.limits(w).contains("Needs the ask 4¢ or more under the fair price."))
    }

    @Test
    fun aWiderWindowBuysEarlier() {
        val plain = runner(emptyMap())
        val wide = runner(mapOf(base.name to ScalpKnobs(secondsLeftMax = 120)))
        // About 100 s left: outside the 20–60 s default, inside 20–120 s.
        prints(plain, 0, 800, 0.90, 0.92)
        prints(wide, 0, 800, 0.90, 0.92)
        assertEquals(0, plain.engine.workingFor(base, t, "YES"))
        assertEquals(1, wide.engine.workingFor(base, t, "YES"))
    }

    @Test
    fun theEdgeSettingDecidesHowFarUnderFairTheAskMustBe() {
        // Fair 98¢, ask 92¢: 6¢ under. The 5¢ default buys, a 7¢ edge does not.
        val plain = runner(emptyMap())
        val strict = runner(mapOf(base.name to ScalpKnobs(edgeCents = 7.0)))
        prints(plain, 0, 845, 0.90, 0.92)
        prints(strict, 0, 845, 0.90, 0.92)
        assertEquals(1, plain.engine.workingFor(base, t, "YES"))
        assertEquals(0, strict.engine.workingFor(base, t, "YES"))
    }

    @Test
    fun lateComebackStartsAtMinuteTenAndTheSettingMovesIt() {
        assertEquals(10, ScalpStrategy.LATE_COMEBACK.startMinute)
        assertEquals(600, Tuned(ScalpStrategy.LATE_COMEBACK, ScalpKnobs(), 10).fromSecond)
        assertEquals(540, Tuned(ScalpStrategy.LATE_COMEBACK, ScalpKnobs(fromMinute = 9), 10).fromSecond)
        assertTrue(ScalpStrategy.values().filter { it != ScalpStrategy.LATE_COMEBACK }.all { it.startMinute == 0 })
    }
}
