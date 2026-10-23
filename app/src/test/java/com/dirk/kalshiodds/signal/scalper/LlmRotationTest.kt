package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmRotationTest {
    private val all = listOf("groq", "gemini", "mistral")

    @Test
    fun switchesAfterEveryNAnsweredCallsAndWrapsAround() {
        val r = LlmRotation(all, every = 3, hasKey = { true })
        assertEquals(all, r.order(all))
        repeat(3) { r.answered("groq") }
        assertEquals(listOf("gemini", "mistral", "groq"), r.order(all))
        repeat(3) { r.answered("gemini") }
        repeat(3) { r.answered("mistral") }
        assertEquals("groq", r.state.value.provider)
    }

    @Test
    fun skipsProvidersWithoutAKeyAndIgnoresFallbackCalls() {
        val r = LlmRotation(all, every = 2, hasKey = { it != "gemini" })
        r.answered("mistral") // a fallback answer does not count
        assertEquals(0, r.state.value.calls)
        repeat(2) { r.answered("groq") }
        assertEquals("mistral", r.state.value.provider)
        assertEquals(listOf("mistral", "groq"), r.order(listOf("groq", "mistral")))
    }

    @Test
    fun aSavedPositionIsRestored() {
        val r = LlmRotation(all, every = 500, hasKey = { true }, load = { "mistral" to 420 })
        assertEquals(LlmRotation.State("mistral", 420, 500), r.state.value)
    }

    @Test
    fun aProviderOverItsLimitIsSkippedAfterThreeFailuresInARow() {
        val four = LlmProvider.NAMES.keys.toList()
        assertEquals(listOf("groq", "cerebras", "gemini", "mistral"), four)
        val r = LlmRotation(four, every = 500, hasKey = { true }, load = { "mistral" to 10 })
        r.failed("mistral")
        r.failed("mistral")
        assertEquals("two failures: still trying it first", "mistral", r.state.value.provider)
        r.answered("mistral")
        r.failed("mistral")
        r.failed("mistral")
        assertEquals("an answer in between resets the count", "mistral", r.state.value.provider)
        r.failed("mistral")
        assertEquals("third in a row: on to the next", "groq", r.state.value.provider)
        assertEquals(0, r.state.value.calls)
        assertEquals(listOf("groq", "cerebras", "gemini", "mistral"), r.order(four))
        r.failed("gemini")
        r.failed("gemini")
        r.failed("gemini")
        assertEquals("failures of a fallback provider do not move the rotation", "groq", r.state.value.provider)
    }

    @Test
    fun theAiProviderLoopReportsAFailingProviderToTheRotation() {
        val r = LlmRotation(listOf("a", "b"), every = 500, hasKey = { true })
        val chat = HttpLlmChat(
            listOf(LlmProvider("a", "http://127.0.0.1:1", listOf("m1")) { "k" }, LlmProvider("b", "http://127.0.0.1:1", listOf("m2")) { "k" }),
            rotation = r,
            http = okhttp3.OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        )
        repeat(3) { assertEquals(null, chat.chat(listOf("user" to "hi"))) }
        assertEquals("three calls that a could not answer moved the rotation to b", "b", r.state.value.provider)
    }
}
