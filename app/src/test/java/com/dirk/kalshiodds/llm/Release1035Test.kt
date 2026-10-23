package com.dirk.kalshiodds.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.35: Groq is the default, automatic calls wait 60s, and a blown daily cap hands off. */
class Release1035Test {
    @Test fun groqIsTheDefaultAndOnlyASavedMistralInstallMoves() {
        assertEquals(LlmProviders.GROQ, LlmSettings.DEFAULT_PROVIDER)
        assertEquals("llama-3.1-8b-instant", LlmSettings.DEFAULT_MODEL)
        assertEquals("https://api.groq.com/openai/v1", LlmSettings.DEFAULT_BASE)
        assertEquals(LlmProviders.GROQ, LlmProviders.FREE_IDS.first())
        assertEquals("grok-4.7", LlmSettings.XAI_MODEL)
        assertEquals("grok-4.7", LlmSettings.migrateModel(""))
        assertEquals("grok-4.7", LlmSettings.migrateModel("grok-4"))
        assertEquals("grok-4.7", LlmProviders.resolve(LlmProviders.XAI, "k", "", "").model)

        val moved = LlmSettings.groqDefaultMigration("mistral", "custom-groq", already = false)
        assertEquals(LlmProviders.GROQ, moved.provider)
        assertEquals("custom-groq", moved.model)
        val blank = LlmSettings.groqDefaultMigration(LlmProviders.MISTRAL, "  ", already = false)
        assertEquals(LlmSettings.DEFAULT_MODEL, blank.model)
        assertNull(LlmSettings.groqDefaultMigration("mistral", "llama-3.1-8b-instant", already = true).provider)
        assertNull(LlmSettings.groqDefaultMigration(null, null, already = false).provider)
        assertNull(LlmSettings.groqDefaultMigration(LlmProviders.XAI, null, already = false).provider)
        assertNull(LlmSettings.groqDefaultMigration(LlmProviders.GROQ, "llama-3.1-8b-instant", already = false).provider)
    }

    @Test fun automaticCallsWaitSixtySecondsEvenWhenATickIsDue() {
        assertEquals(60_000L, AiLiveFeed.MIN_SPACING_MS)
        assertEquals(60_000L, LlmProviders.AUTO_PACE_MS)
        assertEquals(60_000L, LlmProviders.MISTRAL_PACE_MS)
        assertFalse(AiLiveFeed.llmDue(1_000L, 60_999L, LiveTrigger.WINDOW, true))
        assertTrue(AiLiveFeed.llmDue(1_000L, 61_000L, LiveTrigger.NONE, true))
        assertTrue(AiLiveFeed.llmDue(Long.MIN_VALUE, 5_000L, LiveTrigger.NONE, true))
        val quota = LlmProviders.effectiveQuota(LlmProviders.GROQ, LlmProviders.DEFAULT_GROQ_MODEL, 1_000)!!
        assertEquals(60_000L, quota.perModelPaceMs)
        val pacer = FreeCallPacer()
        val model = pacer.allow(LlmProviders.GROQ, listOf(LlmProviders.DEFAULT_GROQ_MODEL), quota, 0L, 100)
        assertEquals(LlmProviders.DEFAULT_GROQ_MODEL, model)
        pacer.record(LlmProviders.GROQ, model!!, 0L, 100)
        assertNull(pacer.allow(LlmProviders.GROQ, listOf(model), quota, 59_000L, 100))
        assertNotNull(pacer.allow(LlmProviders.GROQ, listOf(model), quota, 60_000L, 100))
    }

    @Test fun persistedMistralCapSkipsHttpAndHandsOffToGroq() {
        val mistral = LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", "")
        val groq = LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")
        val gemini = LlmProviders.resolve(LlmProviders.GEMINI, "k", "", "")
        val seen = mutableListOf<String>()
        var fallback = ""
        val hit = AiAutoCalls.callFree(
            selected = mistral,
            slots = listOf(groq, gemini),
            pacer = FreeCallPacer(),
            nowMs = 0L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 20,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { true },
            ask = { ep, _ ->
                seen += ep.provider
                if (ep.provider == LlmProviders.MISTRAL) error("capped mistral must not be called")
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> },
            onFallback = { from, to, reason -> fallback = "${from.provider}->${to.provider} $reason" },
            callsToday = { ep -> if (ep.provider == LlmProviders.MISTRAL) 6226 else 0 }
        )
        assertEquals(LlmProviders.GROQ, hit!!.first.provider)
        assertEquals(listOf(LlmProviders.GROQ), seen)
        assertTrue(fallback, fallback.contains("mistral->groq"))
        assertTrue(fallback, fallback.contains("daily cap 6226/2880"))
    }

    @Test fun aPacedSelectedProviderStillExplainsTheFallthrough() {
        val groq = LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")
        val mistral = LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", "")
        val pacer = FreeCallPacer()
        pacer.record(LlmProviders.GROQ, groq.model, 0L, 100)
        var reason = ""
        val hit = AiAutoCalls.callFree(
            selected = groq,
            slots = listOf(mistral),
            pacer = pacer,
            nowMs = 1_000L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 20,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { true },
            ask = { ep, _ ->
                if (ep.provider == LlmProviders.GROQ) error("paced groq must not be called")
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> },
            onFallback = { _, _, why -> reason = why }
        )
        assertEquals(LlmProviders.MISTRAL, hit!!.first.provider)
        assertTrue(reason, reason.contains("pace"))
    }
}
