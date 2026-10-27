package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The self-learning gate, the live tuner, the early-call watch and the final-window exit. */
class LiveLearningTest {

    private fun x(edge: Double) = OnlineGate.features(0.5, 0.01, 0.0, 0.0, 300, edge, 0.0)

    @Test
    fun gateLetsEverythingThroughWhileWarmingUpThenSkipsLookalikeLosers() {
        val g = OnlineGate()
        var id = 1L
        // Entries with a big fair edge win, entries with a negative one lose.
        repeat(OnlineGate.WARMUP + 40) { i ->
            val good = i % 2 == 0
            val xi = x(if (good) 0.2 else -0.2)
            assertTrue("warming up or learned", i >= OnlineGate.WARMUP || g.allows("S", xi))
            g.remember(id, "S", xi)
            assertNotNull(g.learn(id, if (good) 1.0 else -1.0, 10, i.toLong()))
            id++
        }
        assertTrue(g.predict("S", x(0.2)) > g.predict("S", x(-0.2)))
        assertTrue(g.allows("S", x(0.2)))
        assertFalse(g.allows("S", x(-0.25)))
        assertEquals(1, g.rows().single().skipped)
        // Off: everything goes ahead again.
        g.enabled = false
        assertTrue(g.allows("S", x(-0.25)))
        // Unknown order: nothing learned.
        assertNull(g.learn(999_999L, 1.0, 1, 0L))
        // Saved and read back.
        val back = OnlineGate(OnlineGate.decode(OnlineGate.encode(g.snapshot())))
        assertEquals(g.predict("S", x(0.2)), back.predict("S", x(0.2)), 1e-12)
        assertTrue(g.points().size >= OnlineGate.WARMUP)
    }

    private fun closed(st: ScalpStrategy, pnl: Double, id: Long) = ClosedScalp(
        ScalpOrder(id, st, "T", "YES", 0.5, 10, queueAhead = 0.0, queueKnown = true, postedAtMs = 0L, predictionCents = 0.0, expectedCents = 0.0),
        exitPrice = 0.5, kind = ExitKind.TARGET, feeUsd = 0.0, pnlUsd = pnl, filledAtMs = 0L, closedAtMs = 0L
    )

    @Test
    fun liveTunerGrowsWinnersAndShrinksLosers() {
        val tuning = ScalpTuning()
        val changes = ArrayList<LiveTuner.Change>()
        val t = LiveTuner(tuning) { changes += it }
        val win = ScalpStrategy.MICRO_OBI_MAKER
        val lose = ScalpStrategy.LATE_COMEBACK
        tuning.set(lose, tuning.knobs(lose).copy(contracts = 50))
        repeat(3) { t.onClosed(closed(win, 1.0, it.toLong())) }
        assertTrue("nothing before the 4th trade", t.step().isEmpty())
        t.onClosed(closed(win, 1.0, 3L))
        repeat(4) { t.onClosed(closed(lose, -2.0, 10L + it)) }
        t.step()
        val w = tuning.tuned(win)
        assertTrue(w.contracts > tuning.defaultContracts)
        assertEquals(win.maxPerSide + 1, w.maxPerSide)
        assertTrue("trigger looser (lower)", tuning.knobs(win).trigger!! < 35.0)
        val l = tuning.tuned(lose)
        assertEquals(35, l.contracts)
        assertTrue("trigger stricter (higher)", tuning.knobs(lose).trigger!! > 4.0)
        assertTrue(changes.any { it.strategy == win.name && it.param == "contracts" })
        // Off: it still reads trades but changes nothing.
        t.enabled = false
        repeat(8) { t.onClosed(closed(win, 1.0, 100L + it)) }
        assertTrue(t.step().isEmpty())
    }

    @Test
    fun watchAsksEarlyOnANewWindowAFastMoveOrARunOfTrades() {
        val w = LiveWindowWatch(minSpacingMs = 20_000L)
        assertNull("never ran yet", w.check(1_000L, "A", 0.0, 0, 0L))
        assertNull("too soon", w.check(10_000L, "A", 6.0, 0, 1_000L))
        assertNotNull(w.check(30_000L, "A", 5.0, 0, 1_000L))
        assertNotNull(w.check(30_000L, "A", 0.0, LiveWindowWatch.CLOSES, 1_000L))
        assertEquals("new window B", w.check(60_000L, "B", 0.0, 0, 30_000L))
        assertNull(w.check(90_000L, "B", 1.0, 1, 60_000L))
    }

    @Test
    fun theWatchFollowsTheWindowInSecondsNotTensOfSeconds() {
        val w = LiveWindowWatch()
        assertNull("never ran yet", w.check(1_000L, "A", 0.0, 0, 0L))
        assertNull("four seconds after the last look is still too soon", w.check(5_000L, "A", 9.0, 0, 1_000L))
        assertNotNull("five seconds on, a 2¢ move is worth a look", w.check(6_000L, "A", LiveWindowWatch.FAST_MOVE_CENTS, 0, 1_000L))
        assertNotNull("so is a short run of closed trades", w.check(6_000L, "A", 0.0, LiveWindowWatch.CLOSES, 1_000L))
        assertNull("a 1¢ drift is not", w.check(6_000L, "A", 1.0, 0, 1_000L))
    }

    @Test
    fun aMicroScalpIsSoldBeforeTheClose() {
        val e = ScalperEngine()
        val st = ScalpStrategy.MICRO_BRTI_PULSE
        e.buyNow(st, "T", "YES", 0.50, { _, _ -> null }, 0L, targetPrice = 0.60)
        assertTrue("not yet", e.onClock("T", 1_000L, 0.51, 0.48, secondsLeft = st.flattenSeconds + 5).isEmpty())
        val out = e.onClock("T", 2_000L, 0.51, 0.48, secondsLeft = st.flattenSeconds)
        val c = (out.single() as ScalpEvent.Closed).scalp
        assertEquals(ExitKind.TIMEOUT, c.kind)
        assertEquals(0.51, c.exitPrice, 1e-12)
        // A hold strategy rides on.
        e.buyNow(ScalpStrategy.LATE_COMEBACK, "T", "YES", 0.30, { _, _ -> null }, 0L)
        assertTrue(e.onClock("T", 3_000L, 0.31, 0.68, secondsLeft = 1).isEmpty())
    }
}
