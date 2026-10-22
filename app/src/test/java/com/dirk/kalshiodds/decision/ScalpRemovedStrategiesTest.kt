package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.insights.ScalpFreeze
import com.dirk.kalshiodds.ui.KashiArenaData
import com.dirk.kalshiodds.ui.ScalpTabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10-21 ranking: the registry is the 12 net-positive variant ids. Losers are gone from the board. */
class ScalpRemovedStrategiesTest {
    private val kept = listOf(
        "sf42",
        "v2-g10-t12-s08-d15-w300-mk",
        "g14-t12-s08-d15-w300",
        "sf16f",
        "sf15",
        "sf17f",
        "v2-dip-g05-t08-s06-d10-w180",
        "sf07",
        "sf04",
        "sf08",
        "fcfade-h600",
        "sf41"
    )

    private fun old(id: String) = ScalpTrade(
        id = id, ticker = "KXBTC15M-26OCT181200-00", side = "YES", state = ScalpState.CLOSED,
        signalAtMs = 0L, signalAsk = 0.40, fairAtSignal = 0.50, ruleVersion = "old|$id|P"
    )

    @Test fun registryIsExactlyTheTwelveKeptIds() {
        assertEquals(kept, ScalpModels.WINNERS.map { it.code })
        assertEquals(kept, KeptScalpers.ALL.map { it.id })
        assertEquals(kept, ScalpModels.ALL.take(kept.size).map { it.code })
        assertTrue(ScalpModels.ALL.size <= KeptScalpers.MAX_SLOTS)
        assertTrue(ScalpModels.REMOVED_CODES.containsAll(listOf(
            "sf32", "sf30", "r-kelly", "r-late", "r-late-t1", "r-late-t2", "r-hold", "r-maker",
            "dip", "momo", "cfr", "xrev", "v2-llm", "sf51", "sf60", "fair", "v2-fair", "v2-dip"
        )))
        kept.forEach { id ->
            assertFalse(id, ScalpModels.isRemoved(id))
            assertEquals(id, ScalpModels.modelByCode(id)?.code)
        }
    }

    @Test fun settingsArenaAndLeaderboardListOnlyTheKeptIds() {
        val settings = ScalpModels.ALL.map { it.code }
        val arena = (KashiArenaData.rows(ScalpTabState(), v2 = true) + KashiArenaData.rows(ScalpTabState(), v2 = false))
            .map { it.model.code }
        val board = ScalpModels.leaderboardFromStats(listOf(
            ScalpStat("sf42", n = 9, wins = 5, netUsd = 5.47),
            ScalpStat("sf32", n = 544, wins = 100, netUsd = -209.15),
            ScalpStat("dip", n = 10, netUsd = -3.0)
        )).map { it.model.code }
        assertEquals(ScalpModels.ALL.map { it.code }.toSet(), settings.toSet())
        assertEquals(ScalpModels.ALL.map { it.code }.toSet(), arena.toSet())
        assertEquals(ScalpModels.ALL.map { it.code }.toSet(), board.toSet())
        assertEquals("sf42", board.first())
        assertFalse(board.contains("sf32"))
        assertFalse(board.contains("dip"))
        assertEquals(ScalpModels.ALL.size, board.size)
    }

    @Test fun eachKeptScalperStillFiresItsEntryOnAFixture() {
        kept.forEach { id -> assertTrue(id, KeptScalpers.entryFires(id)) }
        assertEquals(KeptScalpers.FAIR_ID, ScalpParams.seedFor("BTC").id)
        assertEquals(KeptScalpers.V2_FAIR_BASE, ScalpParams.seedFor("ETH").copy(maker = true).id)
        assertEquals(KeptScalpers.V2_DIP_BASE, ScalpParams.seedFor("BTC", ScalpStrategy.DIP_HUNTER).id)
        assertEquals("sf16f", ShockFav.v("sf16").favId)
        assertEquals("sf17f", ShockFav.v("sf17").favId)
        assertEquals(KeptScalpers.FADE_ID, ForecastScalp.variantId(false, 600))
    }

    @Test fun removedTradesStayInHistoryWithoutACard() {
        listOf("sf32", "sf30", "r-kelly", "r-late", "dip-g05-t08-s06-d10-w180", "v2-llm", "sf16", "g10-t12-s08-d15-w300-mk").forEach { id ->
            val t = old(id)
            assertNull(id, ScalpModels.modelOf(t))
            assertEquals(id, KeptScalpers.REMOVED_LABEL, t.algoLabel)
        }
        assertEquals(ScalpModels.Model.G14, ScalpModels.modelOf(old(KeptScalpers.FAIR_ID)))
        assertEquals(ScalpModels.Model.SF16F, ScalpModels.modelOf(old("sf16f")))
        assertEquals("sf16f", ScalpModels.modelOf(old("sf16f"))?.code)
        val board = ScalpModels.leaderboardFromStats(listOf(ScalpStat("sf16", n = 6, wins = 3, netUsd = 1.72)))
        assertEquals(6, board.first { it.model.code == "sf16f" }.roundTrips)
        assertTrue(ScalpFreeze.DEFAULT_FROZEN.isEmpty())
        assertNull(ScalpRetune.cardNote("sf32"))
        assertNull(ScalpRetune.cardNote("sf42"))
    }
}
