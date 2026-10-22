package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learning mode: every strategy bets on paper, so the arena places as many bets as it can and the AI has data on all of them. */
class LearningModeTest {

    /** Runs [block] with learning mode on and always puts the global switch back (other tests expect it off). */
    private fun <T> learning(block: () -> T): T {
        ScalpStrategy.learningMode = true
        try {
            return block()
        } finally {
            ScalpStrategy.learningMode = false
        }
    }

    /** Read when the test object is built, while learning mode is still off. */
    private val retired: List<ScalpStrategy> = ScalpStrategy.values().filter { ScalpStrategy.isRetired(it.name) }

    @Test
    fun offByDefaultOnlyTheMoneyMakersRun() {
        assertFalse(ScalpStrategy.learningMode)
        assertTrue(retired.isNotEmpty())
        assertTrue(ScalpStrategy.ACTIVE.none { it in retired })
    }

    @Test
    fun learningModeRunsAndShowsEveryStrategy() = learning {
        assertEquals(ScalpStrategy.values().toList(), ScalpStrategy.ACTIVE)
        assertTrue(ScalpStrategy.RETIRED.isEmpty())
        for (st in retired) assertFalse(st.name, ScalpStrategy.isRetired(st.name))
        // The screens read the same list, so the retired strategies get rows and settings.
        assertEquals(ScalpStrategy.values().size, ArenaTable.rows(ScalperState.fresh(), emptyList()).size)
        assertEquals(ScalpStrategy.values().size, ScalperResults.leaderboard(ScalperState.fresh()).size)
    }

    @Test
    fun theDefaultRunnerBetsOnTheRetiredStrategiesOnlyInLearningMode() {
        val off = window()
        for (st in retired) assertEquals(st.name, 0, off.stats(st.name).posted)

        val on = learning { window() }
        assertTrue("the retired scalpers place bets", retired.any { on.stats(it.name).posted > 0 })
        assertTrue(
            "far more bets than the late strategies alone",
            on.stats(ScalperState.ALL).posted > off.stats(ScalperState.ALL).posted
        )
    }

    @Test
    fun retiredStrategiesBetSmallInLearningModeAndTheMoneyMakersKeepTheirSize() {
        val t = ScalpTuning()
        val loser = ScalpStrategy.DIP_HUNTER
        val winner = ScalpStrategy.LATE_COMEBACK
        // Off: everything that runs bets the arena's size.
        assertEquals(t.defaultContracts, t.tuned(winner).contracts)
        assertEquals(t.defaultContracts, t.tuned(loser).contracts)
        learning {
            assertEquals(ScalpStrategy.LEARNING_CONTRACTS, t.tuned(loser).contracts)
            assertEquals(t.defaultContracts, t.tuned(winner).contracts)
            // A size you (or the AI) set always wins.
            t.set(loser, ScalpKnobs(contracts = 7))
            assertEquals(7, t.tuned(loser).contracts)
        }
    }

    @Test
    fun theSupervisorCanTuneARetiredStrategyOnlyInLearningMode() {
        val st = ScalpStrategy.DIP_HUNTER
        assertTrue(SupervisorBounds.apply(ScalpTuning(), st.name, "targetCents", 12.0) is SupervisorBounds.Change.Rejected)
        learning {
            val t = ScalpTuning()
            val r = SupervisorBounds.apply(t, st.name, "targetCents", 12.0)
            assertTrue(r is SupervisorBounds.Change.Applied)
            assertTrue(t.tuned(st).targetDollars * 100.0 > 2.0)
        }
    }

    /** One window of prints through a runner on its default strategy list (what the app uses). */
    private fun window(): ScalperState {
        val t = "KXBTC15M-26OCT201215-15"
        val s = PaperScalper(
            model = null, ledger = ScalperLedger(), topOfBook = { null }, bookLevels = { null },
            closeMs = { 900_000L }
        )
        for (sec in 0..880) {
            val bid = if (sec % 120 < 60) 0.40 + sec % 60 * 0.005 else 0.70 - sec % 60 * 0.005
            s.onTrade(t, "no", bid, 25.0, sec * 1000L + 100L)
            s.onTrade(t, "yes", bid + 0.01, 25.0, sec * 1000L + 200L)
        }
        return s.ledger.snapshot()
    }
}
