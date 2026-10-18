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

    @Test fun grok47DefaultMigrationAndAliases() {
        assertEquals("grok-4.7", LlmSettings.DEFAULT_MODEL)
        assertEquals("grok-4.7", LlmSettings.migrateModel("grok-4"))
        assertEquals("grok-4.7", LlmSettings.migrateModel(""))
        assertEquals("grok-3-mini", LlmSettings.migrateModel("grok-3-mini"))
        for (u in listOf("grok-4.7", "grok-4.7-20260921", "GROK-4.7")) assertTrue(u, LlmClient.sameModel("grok-4.7", u))
        for (u in listOf("grok-4.7-fast", "grok-4.70", "grok-4", "grok-4.3")) assertFalse(u, LlmClient.sameModel("grok-4.7", u))
        for (u in listOf("grok-4.7-fast", "grok-4.7-fast-20260921")) assertTrue(u, LlmClient.sameModel("grok-4.7-fast", u))
        for (u in listOf("grok-4.7", "grok-4.7-fast-mini")) assertFalse(u, LlmClient.sameModel("grok-4.7-fast", u))
    }
}
