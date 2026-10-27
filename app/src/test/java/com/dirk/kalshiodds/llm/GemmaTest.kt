package com.dirk.kalshiodds.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GemmaTest {

    @Test
    fun modelsPointAtTheLiteRtFilesOnHuggingFace() {
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            GemmaModel.E2B.url
        )
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            GemmaModel.E4B.url
        )
        GemmaModel.entries.forEach { assertEquals(64, it.sha256.length) }
    }

    @Test
    fun toolArgumentsComeOutAsTheFirstJsonObject() {
        assertEquals("""{"a":1}""", GemmaLlmChat.extractJsonObject("""{"a":1}"""))
        assertEquals(
            """{"changes":[{"why":"brace } inside"}]}""",
            GemmaLlmChat.extractJsonObject("Sure:\n```json\n{\"changes\":[{\"why\":\"brace } inside\"}]}\n```")
        )
        assertNull(GemmaLlmChat.extractJsonObject("no json here"))
        assertNull(GemmaLlmChat.extractJsonObject("{not json}"))
    }

    @Test
    fun historyKeepsTheNewestTurnsAndStartsWithTheUser() {
        val turns = (0 until 60).flatMap { i -> listOf("user" to "q$i " + "x".repeat(500), "assistant" to "a$i " + "y".repeat(500)) }
        val kept = Gemma.fitHistory(turns, usedChars = 4_000)
        assertTrue(kept.isNotEmpty())
        assertEquals("user", kept.first().first)
        assertTrue(kept.last().second.startsWith("a59"))
        assertTrue(kept.sumOf { it.second.length } <= Gemma.MAX_PROMPT_CHARS - 4_000)
    }

    @Test
    fun cloudKeysAnswerUntilGemmaIsDownloaded() {
        val dir = Files.createTempDirectory("gemma").toFile()
        val gemma = Gemma(
            dir = dir,
            cacheDir = dir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            prefs = object : Gemma.Prefs { override var chosen: String? = null }
        )
        assertFalse(gemma.isReady)
        assertNull(gemma.installed())
        val chat = GemmaLlmChat(gemma)
        assertFalse(chat.isReady)
        // No model on the phone: generate fails with a clear message instead of crashing.
        val r = kotlinx.coroutines.runBlocking { gemma.generate("s", "u") }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("isn't downloaded"))
    }
}
