package com.dirk.kalshiodds.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** On-device LiteRT-LM is keyless and is not a hop in the cloud fallthrough chain. */
class BuiltinKeylessTest {
    @Test fun builtinNeedsNoKeyAndCloudProvidersStillDo() {
        assertFalse(LlmProviders.requiresApiKey(LlmProviders.BUILTIN))
        assertTrue(LlmProviders.builtinEndpoint().usable)
        assertTrue(LlmProviders.builtinEndpoint().apiKey.isEmpty())
        for (id in listOf(
            LlmProviders.GROQ, LlmProviders.MISTRAL, LlmProviders.GEMINI, LlmProviders.OPENROUTER_FREE,
            LlmProviders.OPENAI, LlmProviders.XAI, LlmProviders.CUSTOM
        )) {
            assertTrue(id, LlmProviders.requiresApiKey(id))
        }
        assertFalse(LlmProviders.resolve(LlmProviders.GROQ, "", "", "").usable)
        assertFalse(LlmProviders.resolve(LlmProviders.MISTRAL, "", "", "").usable)
        assertFalse(LlmProviders.resolve(LlmProviders.GEMINI, "", "", "").usable)
        assertEquals("Enter your Mistral API key", LlmProviders.keyPrompt(LlmProviders.MISTRAL))
        assertEquals("Enter your Groq API key", LlmProviders.keyPrompt(LlmProviders.GROQ))
        assertEquals("Enter your Gemini API key", LlmProviders.keyPrompt(LlmProviders.GEMINI))
        assertFalse(LlmProviders.FREE_IDS.contains(LlmProviders.BUILTIN))
        assertEquals(
            "Auto pace: unlimited on-device",
            AiAutoCalls.paceLabel(true, LlmProviders.builtinEndpoint(), null)
        )
    }

    @Test fun builtinDoesNotFallThroughToGroqMistralOrGemini() {
        val cloud = listOf(
            LlmProviders.resolve(LlmProviders.GROQ, "k", "", ""),
            LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", ""),
            LlmProviders.resolve(LlmProviders.GEMINI, "k", "", "")
        )
        val seen = ArrayList<String>()
        val hit = AiAutoCalls.callFree(
            selected = LlmProviders.builtinEndpoint(),
            slots = cloud,
            pacer = FreeCallPacer(),
            nowMs = 10_000L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 8,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { true },
            ask = { ep, _ ->
                seen += ep.provider
                LlmClient.Reply("on device", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> },
            onFallback = { _, _, _ -> error("built-in must not fall through") }
        )
        assertEquals(listOf(LlmProviders.BUILTIN), seen)
        assertEquals(LlmProviders.BUILTIN, hit!!.first.provider)

        seen.clear()
        val missed = AiAutoCalls.callFree(
            selected = LlmProviders.builtinEndpoint(),
            slots = cloud,
            pacer = FreeCallPacer(),
            nowMs = 20_000L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 8,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { true },
            ask = { ep, _ ->
                seen += ep.provider
                throw IllegalStateException("device busy")
            },
            onBilling = { _, _ -> },
            onFallback = { _, _, _ -> error("built-in must not fall through") }
        )
        assertNull(missed)
        assertEquals(listOf(LlmProviders.BUILTIN), seen)
        assertNull(
            AiAutoCalls.explainWait(
                listOf(LlmProviders.builtinEndpoint()) + cloud,
                FreeCallPacer(),
                20_000L,
                40,
                { LlmProviders.baseQuota(it.provider, it.model) },
                { true }
            )
        )
        val noKey = AiAutoCalls.explainWait(
            listOf(LlmProviders.resolve(LlmProviders.GROQ, "", "", "")),
            FreeCallPacer(),
            20_000L,
            40,
            { LlmProviders.baseQuota(it.provider, it.model) },
            { true }
        )
        assertEquals("no free provider with a key", noKey!!.reason)
    }

    @Test fun groqStillFallsThroughToMistralThenGemini() {
        val seen = ArrayList<String>()
        val hit = AiAutoCalls.callFree(
            selected = LlmProviders.resolve(LlmProviders.GROQ, "k", "", ""),
            slots = listOf(
                LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", ""),
                LlmProviders.resolve(LlmProviders.GEMINI, "k", "", ""),
                LlmProviders.builtinEndpoint()
            ),
            pacer = FreeCallPacer(),
            nowMs = 30_000L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 8,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { true },
            ask = { ep, _ ->
                seen += ep.provider
                if (ep.provider == LlmProviders.GROQ) throw LlmRateLimited(1_000L, "429")
                if (ep.provider == LlmProviders.MISTRAL) throw IllegalStateException("mistral down")
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> },
            onFallback = { _, _, _ -> }
        )
        assertEquals(listOf(LlmProviders.GROQ, LlmProviders.MISTRAL, LlmProviders.GEMINI), seen)
        assertEquals(LlmProviders.GEMINI, hit!!.first.provider)
    }
}
