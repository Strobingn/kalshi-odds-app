package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.MemoryScalpControls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 1.0.15: chat follows the live feed. Old one-shot wording is not sent back to the model. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLiveContinuityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun systemPromptHasNoPerMessageWording() {
        val system = ChatPrompt.system("settings", "leaderboard")
        assertFalse(system.contains("as of this message"))
        assertFalse(system.contains("next message"))
        assertFalse(system.contains("snapshot", ignoreCase = true))
        assertTrue(system.contains("Never tell the user to ping or message again"))
        assertTrue(system.contains("Never say you cannot watch continuously"))
        assertTrue(system.contains("tick series"))
    }

    @Test fun historyDropsSnapshotOnlyLines() {
        val scope = TestScope(StandardTestDispatcher())
        val c = AiChatController(ChatHistoryStore(tmp.newFile("h.json")), MemoryScalpControls(),
            ask = { "m" to "ok" }, hasKey = { true }, modelName = { "m" }, scope = scope)
        scope.advanceUntilIdle()
        val kept = c.llmHistory(listOf(
            ChatMessage(1, ChatMessage.USER, "where is price", 1),
            ChatMessage(2, ChatMessage.AI, "I only get a fresh snapshot when you message, so ping again.", 2),
            ChatMessage(3, ChatMessage.USER, "and now", 3),
            ChatMessage(4, ChatMessage.AI, "BTC is $81,889. I cannot watch continuously. The rest of the window is live.", 4)
        ))
        val assistant = kept.filter { it.first == "assistant" }.joinToString("\n")
        assertFalse(assistant.contains("fresh snapshot"))
        assertFalse(assistant.contains("ping again"))
        assertFalse(assistant.contains("cannot watch continuously"))
        assertTrue(assistant.contains("BTC is \$81,889."))
        assertTrue(assistant.contains("The rest of the window is live."))
    }

    @Test fun newChatIncludesTheLiveTickSeries() {
        val open = 1_000_000L
        val live = AiLiveSnapshot(
            atMs = open + 40_000L, windowKey = "W", ticker = "KXBTC15M-W", closeMs = open + 900_000L,
            strike = 81_788.17, btc = 81_889.75, yesBid = 0.98, yesAsk = 0.984, noBid = 0.016, noAsk = 0.02, modelUp = 0.984,
            points = listOf(
                AiLivePoint(open + 1_000L, 81_800.0, 81_788.17, 0.70, 0.28, 0.72, 0.30, 0.70),
                AiLivePoint(open + 20_000L, 81_860.0, 81_788.17, 0.90, 0.08, 0.92, 0.10, 0.91),
                AiLivePoint(open + 40_000L, 81_889.75, 81_788.17, 0.98, 0.016, 0.984, 0.02, 0.984)
            )
        )
        val series = ChatPrompt.tickHistory(live, listOf(AiAdjustment(open, "v2-fair", "maxEntryCents", "40", "42", "paper")), listOf("Live feed · window open"))
        assertTrue(series.contains("Live tick series"))
        assertTrue(series.contains("98¢"))
        assertTrue(series.contains("98.4%"))
        assertTrue(series.contains("window open"))
        assertTrue(series.contains("v2-fair maxEntryCents 40 → 42"))
        var sent = ""
        val scope = TestScope(StandardTestDispatcher())
        val c = AiChatController(ChatHistoryStore(tmp.newFile("new.json")), MemoryScalpControls(),
            ask = { sent = it.first().second; "grok-4.7" to "BTC is above the strike." },
            hasKey = { true }, modelName = { "grok-4.7" }, scope = scope,
            liveContext = { series })
        scope.advanceUntilIdle()
        c.clear()
        scope.advanceUntilIdle()
        c.send("what is the window", "")
        scope.advanceUntilIdle()
        assertTrue(sent.contains("Live tick series"))
        assertTrue(sent.contains("98.4%"))
        assertFalse(sent.contains("as of this message"))
        assertFalse(sent.contains("snapshot"))
    }

    @Test fun guardRegeneratesThenStrips() {
        val scope = TestScope(StandardTestDispatcher())
        val answers = ArrayDeque(listOf(
            "I only get a fresh snapshot when you message, so ping again.",
            "Still ping again for a fresh snapshot."
        ))
        var calls = 0
        val c = AiChatController(ChatHistoryStore(tmp.newFile("g.json")), MemoryScalpControls(),
            ask = { calls++; "grok-4.7" to answers.removeFirst() },
            hasKey = { true }, modelName = { "grok-4.7" }, scope = scope, liveContext = { "Live tick series: BTC" })
        scope.advanceUntilIdle()
        c.send("status", "")
        scope.advanceUntilIdle()
        assertEquals(2, calls)
        val text = c.messages.value.last { it.role == ChatMessage.AI }.text
        assertFalse(text.contains("ping again"))
        assertFalse(text.contains("fresh snapshot"))
    }

    @Test fun guardKeepsTheCleanRetry() {
        val scope = TestScope(StandardTestDispatcher())
        val answers = ArrayDeque(listOf(
            "Ping again if you want a fresh snapshot.",
            "BTC is $101 over the strike with about two minutes left."
        ))
        val c = AiChatController(ChatHistoryStore(tmp.newFile("g2.json")), MemoryScalpControls(),
            ask = { "grok-4.7" to answers.removeFirst() },
            hasKey = { true }, modelName = { "grok-4.7" }, scope = scope, liveContext = { "live" })
        scope.advanceUntilIdle()
        c.send("status", "")
        scope.advanceUntilIdle()
        assertEquals("BTC is \$101 over the strike with about two minutes left.", c.messages.value.last { it.role == ChatMessage.AI }.text.removePrefix("[model grok-4.7] "))
    }

    @Test fun cardShowsRealCentsAndDoesNotRoundUpTo100() {
        val atm = AiLiveFeed.atm(listOf(
            AiLiveSnapshot(1, "W", "KXBTC15M-W-FAR", 900_000, 80_000.0, 81_889.75, 1.0, 1.0, 0.0, 0.0, 1.0),
            AiLiveSnapshot(1, "W", "KXBTC15M-W-ATM", 900_000, 81_788.17, 81_889.75, 0.98, 0.984, 0.016, 0.02, 0.984)
        ))
        assertEquals("KXBTC15M-W-ATM", atm!!.ticker)
        assertEquals("98¢", AiLiveFeed.centsLabel(0.98))
        assertEquals("98.4¢", AiLiveFeed.centsLabel(0.984))
        assertEquals("99.6¢", AiLiveFeed.centsLabel(0.996))
        assertEquals("100¢", AiLiveFeed.centsLabel(1.0))
        assertEquals("0.4¢", AiLiveFeed.centsLabel(0.004))
        assertEquals("98.4%", AiLiveFeed.upChanceLabel(0.984))
        assertEquals("99.6%", AiLiveFeed.upChanceLabel(0.996))
        val line = AiLiveFeed.cardOddsLine(atm)
        assertTrue(line.contains("UP 98¢/98.4¢"))
        assertTrue(line.contains("DOWN 1.6¢/2¢"))
        assertTrue(line.contains("AI UP 98.4%"))
        assertFalse(line.contains("100%"))
    }

    @Test fun samplesAboutEveryFifteenSeconds() {
        val open = 1_700_000_000_000L
        val points = (0..40).map { i ->
            AiLivePoint(open + i * 1_000L, 100.0 + i, 100.0, 0.5, 0.5, modelUp = 0.5)
        }
        val rows = ChatPrompt.sampledTicks(points, open)
        assertTrue(rows.size < points.size)
        assertEquals(points.first().atMs, rows.first().atMs)
        assertEquals(points.last().atMs, rows.last().atMs)
        assertTrue(rows.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= 15_000L || b.atMs == points.last().atMs })
    }
}
