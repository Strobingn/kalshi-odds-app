package com.dirk.kalshiodds.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperChatProviderTest {
    @Test
    fun `default provider is groq and keys only go to fixed https hosts`() {
        assertEquals(PaperChatProvider.GROQ, PaperChatProvider.values().first())
        assertEquals("https://api.groq.com/openai/v1/chat/completions", PaperChatProvider.GROQ.endpoint)
        assertEquals("https://api.mistral.ai/v1/chat/completions", PaperChatProvider.MISTRAL.endpoint)
        assertTrue(PaperChatProvider.values().all { it.endpoint.startsWith("https://") && it.model.isNotBlank() })
    }
}
