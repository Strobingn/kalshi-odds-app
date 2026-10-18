package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.MemoryScalpControls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 1.0.4: AI tab chat history survives a restart, clears on confirm, and logs settings changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file() = File(tmp.root, ChatHistoryStore.FILE_NAME)

    @Test fun storeRoundTripsAndRestoresAfterRestart() {
        val a = ChatHistoryStore(file())
        a.append(ChatMessage(1, ChatMessage.USER, "why is fair-gap winning?", 10))
        a.append(ChatMessage(2, ChatMessage.AI, "[model grok-4] it buys 24–42¢ with a 10¢+ edge", 11))
        val restored = ChatHistoryStore(file()).load()
        assertEquals(listOf("why is fair-gap winning?", "[model grok-4] it buys 24–42¢ with a 10¢+ edge"), restored.map { it.text })
        assertEquals(listOf(ChatMessage.USER, ChatMessage.AI), restored.map { it.role })
    }

    @Test fun updateReplacesById_andClearDeletesFile() {
        val s = ChatHistoryStore(file())
        s.append(ChatMessage(1, ChatMessage.PROPOSAL, "Change stopCents?", 1, "stopCents", "6", "8", ChatMessage.PENDING))
        s.update(s.load().single().copy(status = ChatMessage.APPLIED))
        assertEquals(ChatMessage.APPLIED, ChatHistoryStore(file()).load().single().status)
        s.clear()
        assertFalse(file().exists())
        assertTrue(ChatHistoryStore(file()).load().isEmpty())
    }

    @Test fun corruptFileLoadsEmptyInsteadOfCrashing() {
        file().writeText("{not json")
        assertTrue(ChatHistoryStore(file()).load().isEmpty())
    }

    @Test fun capKeepsNewestMessages() {
        val s = ChatHistoryStore(file(), maxMessages = 3)
        (1L..5L).forEach { s.append(ChatMessage(it, ChatMessage.USER, "m$it", it)) }
        assertEquals(listOf("m3", "m4", "m5"), s.load().map { it.text })
    }

    @Test fun controllerRestoresHistory_showsErrors_andLogsAppliedChange() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controls = MemoryScalpControls()
        val c1 = AiChatController(ChatHistoryStore(file()), controls, ask = { error("HTTP 401: bad key") }, hasKey = { true }, modelName = { "grok-4" }, scope = scope)
        scope.advanceUntilIdle()
        c1.send("hello", "ctx"); scope.advanceUntilIdle()
        assertEquals(ChatMessage.ERROR, c1.messages.value.last().role)
        assertTrue(c1.messages.value.last().text.contains("HTTP 401"))
        assertFalse(c1.busy.value)

        val p = c1.propose("stopCents", "8")
        assertEquals(ChatMessage.PENDING, p!!.status)
        assertEquals(6, controls.state.value.stopCents) // nothing changes before Apply
        c1.resolve(p.id, apply = true)
        assertEquals(8, controls.state.value.stopCents)

        // "restart": a new controller on the same file sees everything, including the change log
        val c2 = AiChatController(ChatHistoryStore(file()), controls, ask = { "grok-4" to "ok" }, hasKey = { true }, modelName = { "grok-4" }, scope = scope)
        scope.advanceUntilIdle()
        val roles = c2.messages.value.map { it.role }
        assertEquals(listOf(ChatMessage.USER, ChatMessage.ERROR, ChatMessage.PROPOSAL, ChatMessage.CHANGE), roles)
        assertEquals(ChatMessage.APPLIED, c2.messages.value[2].status)
        assertTrue(c2.messages.value[3].text.contains("stopCents 6 → 8"))
        c2.clear(); scope.advanceUntilIdle()
        assertTrue(ChatHistoryStore(file()).load().isEmpty())
    }

    @Test fun missingKeyIsShownInChat() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val c = AiChatController(ChatHistoryStore(file()), MemoryScalpControls(), ask = { error("must not be called") }, hasKey = { false }, modelName = { "grok-4" }, scope = scope)
        c.send("hi", ""); scope.advanceUntilIdle()
        assertEquals(ChatMessage.ERROR, c.messages.value.last().role)
        assertTrue(c.messages.value.last().text.contains("No LLM API key"))
    }

    @Test fun modelToolCallBecomesPendingCard_cancelLeavesSettingAlone() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controls = MemoryScalpControls()
        val c = AiChatController(ChatHistoryStore(file()), controls,
            ask = { "grok-4-0709" to "Tighter stop helps.\nSET_SETTING stopCents = 4\nSET_SETTING liveTradingArmed = true" },
            hasKey = { true }, modelName = { "grok-4" }, scope = scope)
        c.send("tighten stop", ""); scope.advanceUntilIdle()
        val card = c.messages.value.single { it.role == ChatMessage.PROPOSAL }
        assertEquals("6" to "4", card.oldValue to card.newValue)
        assertTrue(c.messages.value.any { it.role == ChatMessage.CHANGE && it.text.startsWith("BLOCKED") })
        assertFalse(c.messages.value.first { it.role == ChatMessage.AI }.text.contains("SET_SETTING"))
        c.resolve(card.id, apply = false)
        assertEquals(6, controls.state.value.stopCents)
        assertTrue(c.messages.value.last().text.startsWith("Cancelled"))
    }

    @Test fun llmHistoryAlternatesRoles() {
        val c = AiChatController(ChatHistoryStore(file()), MemoryScalpControls(), ask = { "m" to "x" }, hasKey = { true }, modelName = { "m" }, scope = TestScope())
        val h = c.llmHistory(listOf(
            ChatMessage(1, ChatMessage.USER, "q1", 1), ChatMessage(2, ChatMessage.ERROR, "HTTP 500", 2),
            ChatMessage(3, ChatMessage.USER, "q2", 3), ChatMessage(4, ChatMessage.AI, "a2", 4),
            ChatMessage(5, ChatMessage.PROPOSAL, "Change stopCents?", 5, "stopCents", "6", "8", ChatMessage.APPLIED),
            ChatMessage(6, ChatMessage.CHANGE, "Applied: stopCents 6 → 8", 6, status = ChatMessage.APPLIED)))
        assertEquals(listOf("user", "assistant", "user", "assistant"), h.map { it.first })
        h.zipWithNext().forEach { (a, b) -> assertNotEquals(a.first, b.first) }
    }

    @Test fun clearDropsAnInFlightReply() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gate = java.util.concurrent.CountDownLatch(1)
        lateinit var c: AiChatController
        c = AiChatController(ChatHistoryStore(file()), MemoryScalpControls(), ask = { c.clear(); "grok-4" to "late reply\nSET_SETTING stopCents = 8" },
            hasKey = { true }, modelName = { "grok-4" }, scope = scope)
        scope.advanceUntilIdle()
        c.send("q", ""); scope.advanceUntilIdle()
        assertTrue(c.messages.value.isEmpty())
        assertFalse(c.busy.value)
        assertTrue(ChatHistoryStore(file()).load().isEmpty())
        gate.countDown()
    }
}
