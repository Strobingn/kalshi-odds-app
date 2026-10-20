package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strategies that lost on the recorded days (ArenaReplayTest) stay in the enum but stop running and showing. */
class RetiredStrategiesTest {
    private val kept = setOf(
        ScalpStrategy.LATE_COMEBACK, ScalpStrategy.LATE_EDGE, ScalpStrategy.KASHI_FAV_BASE,
        ScalpStrategy.KASHI_FAV_NIGHT, ScalpStrategy.KASHI_FAV_STRICT, ScalpStrategy.KASHI_FAV_97,
        ScalpStrategy.KASHI_FAV_LAST30
    )

    @Test
    fun onlyTheMoneyMakersAreActive() {
        assertEquals(kept, ScalpStrategy.ACTIVE.toSet())
        assertEquals(ScalpStrategy.values().size, ScalpStrategy.ACTIVE.size + ScalpStrategy.RETIRED.size)
        assertTrue(ScalpStrategy.ACTIVE.none { it in ScalpStrategy.RETIRED })
    }

    @Test
    fun retiredStrategiesStayOffTheBoard() {
        assertEquals(kept, ScalperResults.leaderboard(ScalperState.fresh()).map { it.strategy }.toSet())
        assertEquals(kept.size, ArenaTable.rows(ScalperState.fresh(), emptyList()).size)
    }

    @Test
    fun theScreenDropsRetiredRowsButKeepsTheTotals() {
        val st = ScalperState(
            groups = mapOf(
                ScalpStrategy.DIP_HUNTER.name to ScalpStats(closed = 3, pnlUsd = -2.0),
                ScalpStrategy.LATE_EDGE.name to ScalpStats(closed = 1, pnlUsd = 0.5),
                ScalperState.ALL to ScalpStats(closed = 4, pnlUsd = -1.5)
            ),
            version = ScalperState.VERSION
        ).withoutRetired()
        assertEquals(setOf(ScalpStrategy.LATE_EDGE.name, ScalperState.ALL), st.groups.keys)
        assertEquals(-1.5, st.stats(ScalperState.ALL).pnlUsd, 1e-12)
        assertTrue(ScalpStrategy.isRetired("ML_REST"))
        assertTrue(ScalpStrategy.isRetired("FLOW_FADE"))
        assertTrue(!ScalpStrategy.isRetired("LATE_EDGE"))
    }

    @Test
    fun theRunnerPostsNothingForRetiredStrategies() {
        val active = window(ScalpStrategy.ACTIVE)
        for (st in ScalpStrategy.RETIRED) assertEquals(st.name, 0, active.stats(st.name).posted)
        // The same window with every strategy on does post for retired ones, so the check above means something.
        val all = window(ScalpStrategy.values().toList())
        assertTrue(ScalpStrategy.RETIRED.any { all.stats(it.name).posted > 0 })
    }

    private fun window(strategies: List<ScalpStrategy>): ScalperState {
        val t = "KXBTC15M-26OCT201215-15"
        val s = PaperScalper(
            model = null, ledger = ScalperLedger(), topOfBook = { null }, bookLevels = { null },
            closeMs = { 900_000L }, strategies = strategies
        )
        for (sec in 0..880) {
            val bid = if (sec % 120 < 60) 0.40 + sec % 60 * 0.005 else 0.70 - sec % 60 * 0.005
            s.onTrade(t, "no", bid, 25.0, sec * 1000L + 100L)
            s.onTrade(t, "yes", bid + 0.01, 25.0, sec * 1000L + 200L)
        }
        return s.ledger.snapshot()
    }
}
