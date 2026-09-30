package com.dirk.kalshiarb.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperLogTest {

    private fun opp(ticker: String, sets: Long, profit: Long): Opportunity {
        val s = Structure(
            type = ArbType.BOX,
            eventTicker = "EV",
            seriesTicker = "KXEV",
            eventTitle = "Event",
            legs = listOf(LegSpec(ticker, ticker, Side.YES), LegSpec(ticker, ticker, Side.NO)),
            payoutPerSetCents = 100,
            verified = true
        )
        return Opportunity(s, sets, emptyList(), 0, 0, sets * 100, profit, -1.0, 9_800)
    }

    @Test
    fun opensExtendsAndCloses() {
        val a1 = PaperLog.merge(emptyList(), listOf(opp("A", 10, 20)), nowMs = 1_000)
        assertEquals(1, a1.newlyOpened.size)
        assertTrue(a1.entries.single().open)

        val a2 = PaperLog.merge(a1.entries, listOf(opp("A", 30, 50)), nowMs = 61_000)
        assertTrue(a2.newlyOpened.isEmpty())
        val e = a2.entries.single()
        assertEquals(1_000L, e.firstSeenMs)
        assertEquals(61_000L, e.lastSeenMs)
        assertEquals(60_000L, e.durationMs)
        assertEquals(30L, e.peakSets)
        assertEquals(50L, e.peakProfitCents)
        assertEquals(2, e.sightings)

        val a3 = PaperLog.merge(a2.entries, emptyList(), nowMs = 121_000)
        assertFalse(a3.entries.single().open)
        assertEquals(61_000L, a3.entries.single().lastSeenMs)

        // Reappearing later starts a new run and counts as new.
        val a4 = PaperLog.merge(a3.entries, listOf(opp("A", 5, 5)), nowMs = 200_000)
        assertEquals(1, a4.newlyOpened.size)
        assertEquals(2, a4.entries.size)
        assertTrue(a4.entries.first().open)
        assertEquals(200_000L, a4.entries.first().firstSeenMs)
    }
}
