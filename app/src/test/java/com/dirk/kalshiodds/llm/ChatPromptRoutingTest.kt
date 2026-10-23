package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.MemoryScalpControls
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId

/** 1.0.8: a chat message builds the conversational chat prompt, never the paper strategy's YES/NO/SKIP prompt. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPromptRoutingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun chatMessageBuildsChatPromptNotStrategyPrompt() {
        val scope = TestScope(StandardTestDispatcher())
        var sent: List<Pair<String, String>> = emptyList()
        val c = AiChatController(ChatHistoryStore(tmp.newFile("h.json").also { it.delete() }), MemoryScalpControls(),
            ask = { sent = it; "grok-4.7" to "Probably yes — BTC is at 80,900 with 12 min left (estimate)." },
            hasKey = { true }, modelName = { "grok-4.7" }, scope = scope)
        scope.advanceUntilIdle()
        c.send("will the price of Bitcoin be over 80,750 at 5pm", "BTC price: $80900.00\nLLM paper strategy status: model grok-4.7 · skipped X: SKIP")
        scope.advanceUntilIdle()
        val sys = sent.first()
        assertEquals("system", sys.first)
        assertTrue(sys.second.startsWith(ChatPrompt.MARKER))
        assertTrue(sys.second.contains("BTC price: $80900.00"))
        assertFalse(sys.second.contains("Reply ONE line"))
        assertFalse(sent.any { it.second.contains(LlmPaperStrategy.STRATEGY_PROMPT.take(40)) })
        assertEquals("user" to "will the price of Bitcoin be over 80,750 at 5pm", sent.last())
        assertEquals("[model grok-4.7] Probably yes — BTC is at 80,900 with 12 min left (estimate).", c.messages.value.last().text)
    }

    @Test fun everyMessageCarriesTheLiveWindowAndRolloverReplacesIt() {
        val et = ZoneId.of("America/New_York")
        val closeA = LocalDateTime.of(2026, 10, 19, 17, 0).atZone(et).toInstant().toEpochMilli()
        val openA = closeA - 900_000L
        val nowA = openA + 180_000L
        val closeB = LocalDateTime.of(2026, 10, 19, 17, 15).atZone(et).toInstant().toEpochMilli()
        val nowB = closeB - 900_000L + 60_000L
        val book = ScalpBook()
        book.tickSpot("BTC", 80_750.0, openA)
        book.tickSpot("BTC", 80_900.0, nowA)
        assertEquals(80_750.0, book.spotAt("BTC", openA)!!, 0.001)
        assertNull(book.spotAt("ETH", openA))
        var snap = window(nowA, closeA, "26OCT191700", "45", 80_900.0, 80_750.0, 80_750.0, 0.61, "KXBTC15M-26OCT191700-45")
        var reads = 0
        val scope = TestScope(StandardTestDispatcher())
        val sent = ArrayList<List<Pair<String, String>>>()
        val c = AiChatController(ChatHistoryStore(tmp.newFile("live.json").also { it.delete() }), MemoryScalpControls(),
            ask = { sent += it; "grok-4.7" to "The capital of France is Paris." },
            hasKey = { true }, modelName = { "grok-4.7" }, scope = scope, liveContext = { reads++; ChatPrompt.liveWindow(snap) })
        scope.advanceUntilIdle()
        assertEquals(0, reads)
        c.send("what is the capital of France", "leaderboard: fair net $1.00")
        scope.advanceUntilIdle()
        val first = sent.single().first()
        assertEquals("system", first.first)
        assertTrue(first.second.startsWith(ChatPrompt.MARKER))
        assertTrue(first.second.contains("can never touch real money"))
        assertTrue(first.second.contains("leaderboard: fair net $1.00"))
        assertWindow(first.second, "KXBTC15M-26OCT191700", "Oct 19, 4:45:00 PM ET", "Oct 19, 5:00:00 PM ET", "12:00 (720s)",
            "$80,900.00", "+$150.00 (+0.19%) from $80,750.00 at the open", "0.61",
            "KXBTC15M-26OCT191700-45 (at the money) strike $80,750.00 YES bid/ask 0.46/0.48 NO bid/ask 0.52/0.54",
            "OPEN YES 10 @ 0.40 KXBTC15M-26OCT191700-45")
        assertFalse(first.second.contains("KXBTC15M-26OCT191715"))
        assertFalse(first.second.contains("KXBTC15M-26OCT191730"))
        assertFalse(first.second.contains("KXETH15M"))
        assertEquals("user" to "what is the capital of France", sent.single().last())
        assertEquals("[model grok-4.7] The capital of France is Paris.", c.messages.value.last { it.role == "ai" }.text)

        snap = window(nowB, closeB, "26OCT191715", "50", 81_020.0, 81_250.0, 80_980.0, 0.44, held = "")
        c.send("and now?", "")
        scope.advanceUntilIdle()
        assertEquals(2, reads)
        val second = sent.last().first().second
        assertWindow(second, "KXBTC15M-26OCT191715", "Oct 19, 5:00:00 PM ET", "Oct 19, 5:15:00 PM ET", "14:00 (840s)",
            "$81,020.00", "+$40.00 (+0.05%) from $80,980.00 at the open", "0.44",
            "KXBTC15M-26OCT191715-50 (at the money) strike $81,250.00 YES bid/ask 0.31/0.33 NO bid/ask 0.67/0.69",
            "(none)")
        assertFalse(second.contains("KXBTC15M-26OCT191700"))
        assertFalse(second.contains("KXBTC15M-26OCT191730"))
        assertTrue(second.startsWith(ChatPrompt.MARKER))
        assertEquals("user" to "and now?", sent.last().last())
    }

    private fun assertWindow(prompt: String, vararg parts: String) {
        assertTrue(prompt.contains(ChatPrompt.LIVE_HEADER))
        for (p in parts) assertTrue("missing [$p] in\n$prompt", prompt.contains(p))
    }

    private fun window(
        now: Long, close: Long, key: String, atmSuffix: String, spot: Double, atmStrike: Double, openPx: Double, up: Double, held: String
    ): ChatPrompt.LiveWindow {
        val bid = if (atmSuffix == "45") 0.46 else 0.31
        val quotes = listOf(
            q("KXBTC15M-$key-$atmSuffix", now, close, bid, bid + 0.02, spot, atmStrike),
            q("KXBTC15M-$key-90", now, close, 0.20, 0.22, spot, atmStrike + 5_000.0),
            q("KXBTC15M-26OCT191730-00", now, close + 900_000L, 0.50, 0.52, spot, 82_000.0),
            q("KXETH15M-$key-$atmSuffix", now, close, 0.40, 0.42, 3_000.0, 3_000.0)
        )
        val pos = if (held.isEmpty()) emptyList() else listOf(
            ScalpTrade(id = "p1", ticker = held, side = "YES", state = ScalpState.OPEN, signalAtMs = now, signalAsk = 0.40, fairAtSignal = 0.55, contracts = 10, entryPrice = 0.40),
            ScalpTrade(id = "eth", ticker = "KXETH15M-$key-$atmSuffix", side = "NO", state = ScalpState.OPEN, signalAtMs = now, signalAsk = 0.60, fairAtSignal = 0.40, contracts = 4, entryPrice = 0.60)
        )
        return ChatPrompt.LiveWindow(now, quotes, pos, openPx, up)
    }

    private fun q(ticker: String, now: Long, close: Long, bid: Double, ask: Double, spot: Double, strike: Double) =
        ScalpRule.Quote(ticker, now, close, now, bid, 100.0, ask, 100.0, spot, strike, 1e-4)

    @Test fun grok47DefaultMigrationAndAliases() {
        assertEquals(LlmProviders.DEFAULT_GROQ_MODEL, LlmSettings.DEFAULT_MODEL)
        assertEquals("grok-4.7", LlmSettings.XAI_MODEL)
        assertEquals("grok-4.7", LlmSettings.migrateModel("grok-4"))
        assertEquals("grok-4.7", LlmSettings.migrateModel(""))
        assertEquals("grok-3-mini", LlmSettings.migrateModel("grok-3-mini"))
        for (u in listOf("grok-4.7", "grok-4.7-20260921", "GROK-4.7")) assertTrue(u, LlmClient.sameModel("grok-4.7", u))
        for (u in listOf("grok-4.7-fast", "grok-4.70", "grok-4", "grok-4.3")) assertFalse(u, LlmClient.sameModel("grok-4.7", u))
        for (u in listOf("grok-4.7-fast", "grok-4.7-fast-20260921")) assertTrue(u, LlmClient.sameModel("grok-4.7-fast", u))
        for (u in listOf("grok-4.7", "grok-4.7-fast-mini")) assertFalse(u, LlmClient.sameModel("grok-4.7-fast", u))
    }
}
