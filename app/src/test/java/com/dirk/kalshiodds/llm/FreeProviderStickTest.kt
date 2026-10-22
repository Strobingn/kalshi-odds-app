package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.domain.EtTime
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A free-provider error must not flip the saved provider, model, free-tier switch, or automatic calls. */
class FreeProviderStickTest {
    private val quotaBody = "You exceeded your current quota, please check your plan and billing details."

    @Test fun quotaAndRateLimitAreNotBilling() {
        assertFalse(GeminiApi.billing(429, quotaBody))
        assertFalse(GeminiApi.billing(429, "rate limit"))
        assertFalse(GeminiApi.billing(429, "Resource exhausted"))
        assertTrue(GeminiApi.billing(402, ""))
        assertTrue(GeminiApi.billing(403, "Please enable billing"))
        assertFalse(GeminiApi.billing(403, quotaBody))

        try {
            GeminiApi.exchange(
                "https://generativelanguage.googleapis.com/v1beta",
                LlmProviders.GEMINI_DEFAULT,
                listOf("user" to "hi"),
                8,
                post = { _, _ -> GeminiApi.Raw(429, quotaBody) }
            )
            error("429 must not be treated as billing")
        } catch (e: LlmRateLimited) {
            assertTrue(e.message!!.contains("rate limited"))
        }

        try {
            MistralApi.exchange(
                LlmProviders.MISTRAL_DEFAULT,
                listOf("user" to "hi"),
                8,
                post = { _, _ -> MistralApi.Raw(429, quotaBody, mapOf("retry-after" to "2")) }
            )
            error("mistral 429 must not be treated as billing")
        } catch (e: LlmRateLimited) {
            assertTrue(e.message!!.contains("rate limited"))
        }
    }

    @Test fun rateLimitFallsThroughWithoutChangingSavedSwitches() {
        val file = File.createTempFile("ai-watch", ".json")
        file.writeText("""{"autoCalls":true,"followsWindow":true}""")
        val store = AiWatchStore(file)
        val providerBefore = "mistral"
        val modelBefore = LlmProviders.MISTRAL_DEFAULT
        assertTrue(store.freeTierOn(providerBefore, modelBefore))
        assertTrue(store.config.value.autoCalls)
        val seen = mutableListOf<String>()
        val hit = AiAutoCalls.callFree(
            selected = LlmProviders.resolve(LlmProviders.MISTRAL, "k", modelBefore, ""),
            slots = listOf(
                LlmProviders.resolve(LlmProviders.GROQ, "k", "", ""),
                LlmProviders.resolve(LlmProviders.OPENAI, "sk", "gpt-4.1", "")
            ),
            pacer = FreeCallPacer(),
            nowMs = 5_000L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 8,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { store.autoAllowed(it.provider, it.model, 5_000L) },
            ask = { ep, _ ->
                seen += ep.provider
                if (ep.provider == LlmProviders.MISTRAL) throw LlmRateLimited(1_000L, "429")
                if (ep.provider == LlmProviders.OPENAI) error("paid provider was called")
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> error("quota must not mark billing") },
            onFallback = { from, to, reason -> store.noteFallback(from.provider, to.provider, reason, 5_000L) }
        )
        assertEquals(LlmProviders.GROQ, hit!!.first.provider)
        assertEquals(listOf(LlmProviders.MISTRAL, LlmProviders.GROQ), seen)
        assertTrue(store.freeTierOn(providerBefore, modelBefore))
        assertTrue(store.config.value.autoCalls)
        assertTrue(store.lastSwitchLine()!!.contains("Fell through from Mistral to Groq"))
        assertTrue(store.lastSwitchLine()!!.contains("rate limit"))
        assertTrue(store.lastSwitchLine()!!.contains(EtTime.clock(5_000L)))
        val saved = file.readText()
        assertFalse(saved.contains("\"mistral\":false"))
        assertTrue(saved.contains("autoCalls\":true") || saved.contains("\"autoCalls\": true") || saved.contains("\"autoCalls\":true"))
        val again = AiWatchStore(file)
        assertTrue(again.config.value.autoCalls)
        assertTrue(again.freeTierOn(providerBefore, modelBefore))
        assertTrue(again.autoAllowed(providerBefore, modelBefore))
    }

    @Test fun billingPausesOneProviderAndDoesNotPersistThePaidSwitch() {
        val file = File.createTempFile("ai-bill", ".json")
        file.writeText(
            """{"autoCalls":true,"freeTier":{"groq":false},"billingHold":{"groq":"Groq is manual-only: the provider reported billing or a paid tier. quota"}}"""
        )
        val repaired = AiWatchStore(file)
        assertTrue(repaired.config.value.autoCalls)
        assertTrue(repaired.freeTierOn(LlmProviders.GROQ, LlmProviders.GROQ_120))
        assertTrue(repaired.autoAllowed(LlmProviders.GROQ, LlmProviders.GROQ_120))

        val explicit = File.createTempFile("ai-off", ".json")
        explicit.writeText("""{"autoCalls":true,"freeTier":{"groq":false}}""")
        val keptOff = AiWatchStore(explicit)
        assertFalse(keptOff.freeTierOn(LlmProviders.GROQ, LlmProviders.GROQ_120))

        val store = AiWatchStore()
        store.setAutoCalls(true)
        val at = 10_000L
        store.markBilled(LlmProviders.MISTRAL, "Please enable billing", at)
        assertTrue(store.freeTierOn(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT))
        assertTrue(store.config.value.autoCalls)
        assertFalse(store.autoAllowed(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, at + 1_000L))
        assertTrue(store.autoAllowed(LlmProviders.GROQ, LlmProviders.GROQ_120, at + 1_000L))
        assertTrue(store.blockNotice(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, at + 1_000L).contains("billing required"))
        assertTrue(store.lastSwitchLine()!!.contains("Paused Mistral"))
        assertTrue(store.lastSwitchLine()!!.contains(EtTime.clock(at)))
        assertTrue(store.autoAllowed(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, at + 16 * 60_000L))
        assertNull(store.blockNotice(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, at + 16 * 60_000L).ifBlank { null })

        val seen = mutableListOf<String>()
        val hit = AiAutoCalls.callFree(
            selected = LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", ""),
            slots = listOf(LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")),
            pacer = FreeCallPacer(),
            nowMs = at + 1_000L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 8,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { it.provider != LlmProviders.MISTRAL },
            ask = { ep, _ ->
                seen += ep.provider
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> },
            onFallback = { _, _, _ -> }
        )
        assertEquals(listOf(LlmProviders.GROQ), seen)
        assertEquals(LlmProviders.GROQ, hit!!.first.provider)
    }
}
