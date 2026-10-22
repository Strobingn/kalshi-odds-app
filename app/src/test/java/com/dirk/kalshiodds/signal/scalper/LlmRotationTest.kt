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
}
