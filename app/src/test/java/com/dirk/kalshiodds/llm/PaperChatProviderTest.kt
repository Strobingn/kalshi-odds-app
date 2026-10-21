package com.dirk.kalshiodds.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

class PaperChatProviderTest {
    @Test
    fun `default provider is groq and keys only go to fixed https hosts`() {
        assertEquals(PaperChatProvider.GROQ, PaperChatProvider.values().first())
        assertEquals("https://api.groq.com/openai/v1/chat/completions", PaperChatProvider.GROQ.endpoint)
        assertEquals("https://api.mistral.ai/v1/chat/completions", PaperChatProvider.MISTRAL.endpoint)
        assertTrue(PaperChatProvider.values().all { it.endpoint.startsWith("https://") && it.model.isNotBlank() })
    }

    @Test fun unreadableSelectedKeyFallsBackWithoutBlockingOtherKey() = runBlocking {
        val answer = runProviderFallback(PaperChatProvider.GROQ,
            load = { if (it == PaperChatProvider.GROQ) error("secret") else "mistral-key" },
            request = { provider, _ -> "answer from ${provider.label}" })
        assertEquals(PaperChatProvider.MISTRAL, answer.provider)
    }

    @Test fun rateLimitUsesSecondProvider() = runBlocking {
        val answer = runProviderFallback(PaperChatProvider.GROQ, load = { "key" }, request = { provider, _ ->
            if (provider == PaperChatProvider.GROQ) throw PaperChatException(httpFailure(provider, 429, "30"), 429)
            "ok"
        })
        assertEquals(PaperChatProvider.MISTRAL, answer.provider)
    }

    @Test fun failureReportsProviderAndSafeReasonWithoutSecrets() = runBlocking {
        try {
            runProviderFallback(PaperChatProvider.GROQ, load = { if (it == PaperChatProvider.GROQ) "secret-key" else null }, request = { provider, _ ->
                throw PaperChatException(httpFailure(provider, 429, "15"), 429)
            })
            fail("expected failure")
        } catch (e: PaperChatException) {
            assertTrue(e.message!!.contains("Groq: HTTP 429"))
            assertTrue(e.message!!.contains("retry after 15 seconds"))
            assertTrue(e.message!!.contains("Mistral: no saved key"))
            assertTrue(!e.message!!.contains("secret-key"))
        }
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        try {
            runProviderFallback(PaperChatProvider.GROQ, load = { throw CancellationException("stop") }, request = { _, _ -> "" })
            fail("expected cancellation")
        } catch (_: CancellationException) { }
    }
}
