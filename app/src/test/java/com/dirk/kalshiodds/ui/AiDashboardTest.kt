package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.scalper.AiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AiDashboardTest {
    private val now = AiScreenFixtures.NOW

    @Test
    fun callsAreBucketedByHourAndSplitByAiAndFailure() {
        val ev = listOf(
            AiEvent(now - 10_000, AiEvent.SCALPING, AiEvent.CALL, "groq", true, 1000, "m | "),
            AiEvent(now - 20_000, AiEvent.PAPER, AiEvent.CALL, "gemini", true, 3000, "g | "),
            AiEvent(now - 30_000, AiEvent.CHAT, AiEvent.CALL, "groq", false, 100, "m | HTTP 404 model not found"),
            AiEvent(now - 25 * 3_600_000L, AiEvent.SCALPING, AiEvent.CALL, "groq", true, 1, "old | ")
        )
        val s = AiDashboard.build(AiDashboardState(keys = listOf("Groq" to true)), now, ev, emptyList(), emptyList(), TimeZone.getTimeZone("UTC"))
        assertEquals(3, s.calls24h)
        assertEquals(2, s.callsOk24h)
        assertEquals(2000L, s.avgMs)
        assertEquals(listOf(1f, 1f, 0f, 1f), s.callsByHour.last())
        assertEquals(24, s.callsByHour.size)
        val groq = s.providers.first { it.name == "groq" }
        assertEquals(1, groq.ok); assertEquals(1, groq.failed)
        assertTrue(groq.lastError.contains("model not found"))
        assertTrue(s.lastError.contains("model not found"))
    }

    @Test
    fun winnersAndLosersAreSplitAndSorted() {
        val st = listOf(StrategyResult("A", 10, 8, 50.0), StrategyResult("B", 10, 2, -80.0), StrategyResult("C", 10, 6, 5.0), StrategyResult("D", 0, 0, 0.0))
        val s = AiDashboard.build(AiDashboardState(), now, emptyList(), st, emptyList())
        assertEquals(listOf("A", "C"), s.winners.map { it.label })
        assertEquals(listOf("B"), s.losers.map { it.label })
        assertEquals(30, s.arenaTrades)
        assertEquals(-25.0, s.arenaNet, 1e-9)
    }
}
