package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.decision.ScalpModels
import org.junit.Assert.*
import org.junit.Test

class ScalpLabUiTest {
    private val state = ScalpTabFixtures.state(10)

    @Test fun everyModelRemainsReachableAndClosedResultsRankAheadOfUntestedModels() {
        val ranked = ScalpLabUi.ranked(state.algoAllTime, state.algoAllTime)
        assertEquals(ScalpModels.ALL.toSet(), ranked.toSet())
        assertEquals(ScalpModels.ALL.size, ranked.size)
        assertEquals(ScalpModels.Model.EXTREME_REVERSION, ranked.first())
        assertTrue(ranked.indexOf(ScalpModels.Model.DIP_HUNTER) < ranked.indexOf(ScalpModels.Model.MAKER_DIP))
    }

    @Test fun openFilterUsesLivePositionsEvenWhenSelectedPeriodHasNoClosedTrades() {
        assertEquals(listOf(ScalpModels.Model.FAIR_GAP), ScalpLabUi.ranked(emptyMap(), state.algoAllTime, ScalpLabUi.Filter.OPEN))
    }

    @Test fun profitFiltersExcludeUntradedAndBreakevenModelsAndUseSelectedPeriod() {
        val m = ScalpModels.Model.FAIR_GAP
        val today = state.algoAllTime.toMutableMap().apply { this[m] = getValue(m).copy(netUsd = 2.0) }
        assertEquals(listOf(m), ScalpLabUi.ranked(today, state.algoAllTime, ScalpLabUi.Filter.AHEAD))
        assertTrue(ScalpLabUi.ranked(state.algoAllTime, state.algoAllTime, ScalpLabUi.Filter.AHEAD).isEmpty())
        val losing = ScalpLabUi.ranked(today, state.algoAllTime, ScalpLabUi.Filter.BEHIND)
        assertFalse(losing.contains(m))
        assertFalse(losing.contains(ScalpModels.Model.MAKER_DIP))
    }

    @Test fun v2AndSearchCanReachModelsOutsideTheFirstScreen() {
        assertEquals(ScalpModels.ALL.filter { ScalpModels.isV2(it) }.toSet(),
            ScalpLabUi.ranked(emptyMap(), emptyMap(), ScalpLabUi.Filter.V2).toSet())
        val found = ScalpLabUi.ranked(emptyMap(), emptyMap(), query = "  FAIR-GAP  ")
        assertTrue(found.contains(ScalpModels.Model.FAIR_GAP))
        assertTrue(found.contains(ScalpModels.Model.V2_FAIR_GAP))
        assertFalse(found.contains(ScalpModels.Model.MOMENTUM))
        assertTrue(ScalpLabUi.ranked(emptyMap(), emptyMap(), query = "no such model").isEmpty())
    }
}
