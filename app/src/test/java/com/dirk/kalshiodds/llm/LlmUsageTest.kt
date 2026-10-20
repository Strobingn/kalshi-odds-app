package com.dirk.kalshiodds.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 1.0.12: token cost, ET day buckets, price edits, and provider resolution. */
class LlmUsageTest {
    @get:Rule val tmp = TemporaryFolder()
    private val et = ZoneId.of("America/New_York")
    private fun at(day: LocalDate, hour: Int) = day.atTime(LocalTime.of(hour, 0)).atZone(et).toInstant().toEpochMilli()

    @Test fun costIsPromptPlusCompletionAtTheEditableRate() {
        val grok = ModelPrice("grok-4.7", 2.0, 6.0)
        assertEquals(5.0, LlmPrices.costUsd(1_000_000, 500_000, grok), 1e-9)
        assertEquals(0.0, LlmPrices.costUsd(1_000_000, 1_000_000, null), 0.0)
        val table = LlmPrices.DEFAULTS + ModelPrice("grok-4", 3.0, 15.0)
        assertEquals(2.0, LlmPrices.priceFor("grok-4.7", table)!!.inputPerMillion, 0.0)
        assertEquals(2.0, LlmPrices.priceFor("grok-4.7-20260921", table)!!.inputPerMillion, 0.0)
        assertEquals(3.0, LlmPrices.priceFor("grok-4-0709", table)!!.inputPerMillion, 0.0)
        assertNull(LlmPrices.priceFor("some-unknown-model", table))
        assertEquals(3L to 4L, LlmClient.usageTokens(JSONObject("""{"usage":{"prompt_tokens":3,"completion_tokens":4}}""")))
        assertEquals(8L to 1L, LlmClient.usageTokens(JSONObject("""{"usage":{"input_tokens":8,"output_tokens":1}}""")))
        assertEquals(0L to 0L, LlmClient.usageTokens(JSONObject("{}")))
    }

    @Test fun todayIsEasternAndSourcesStaySplit() {
        val store = AiWatchStore()
        val today = LocalDate.of(2026, 10, 19)
        val noon = at(today, 12)
        val prevEvening = at(today.minusDays(1), 23)
        val eightDaysAgo = at(today.minusDays(8), 12)
        store.record(LlmSources.WATCHER, LlmProviders.XAI, "grok-4.7", 1_000_000, 0, prevEvening)
        store.record(LlmSources.CHAT, LlmProviders.XAI, "grok-4.7", 500_000, 0, noon)
        store.record(LlmSources.STRATEGY, LlmProviders.XAI, "grok-4.7", 0, 1_000_000, noon)
        store.record(LlmSources.WATCHER, LlmProviders.XAI, "grok-4.7", 1_000_000, 0, eightDaysAgo)
        val s = store.summaryAt(noon)
        assertEquals(1.0, s.today.bySource.getValue(LlmSources.CHAT).costUsd, 1e-9)
        assertEquals(6.0, s.today.bySource.getValue(LlmSources.STRATEGY).costUsd, 1e-9)
        assertEquals(0, s.today.bySource.getValue(LlmSources.WATCHER).calls)
        assertEquals(7.0, s.today.costUsd, 1e-9)
        assertEquals(9.0, s.last7.costUsd, 1e-9) // yesterday's $2 plus today, not the 8-day-old $2
        assertEquals(11.0, s.allTime.costUsd, 1e-9)
        assertTrue(LlmUsageText.todayLine(s).contains("calls"))
        assertTrue(LlmUsageText.sources(s.today).contains("chat 1 calls"))
        assertTrue(LlmUsageText.budgetNotice(1.2, 1.0).contains("Chat still works"))
    }

    @Test fun editingThePriceTableRecomputesStoredTokens() {
        val store = AiWatchStore()
        val now = at(LocalDate.of(2026, 10, 19), 15)
        store.record(LlmSources.WATCHER, LlmProviders.XAI, "grok-4.7", 1_000_000, 0, now)
        assertEquals(2.0, store.summaryAt(now).today.costUsd, 1e-9)
        store.setPrices(store.prices().map { if (it.model == "grok-4.7") it.copy(inputPerMillion = 4.0) else it })
        assertEquals(4.0, store.summaryAt(now).today.costUsd, 1e-9)
        assertEquals(4.0, store.todaySpend(now), 1e-9)
    }

    @Test fun usageAndTheFollowTogglePersist() {
        val file = tmp.newFile("ai-watch.json")
        val now = at(LocalDate.of(2026, 10, 19), 9)
        val first = AiWatchStore(file)
        assertTrue(first.config.value.followsWindow)
        assertEquals(AiWatchConfig.DEFAULT_INTERVAL_MS, first.config.value.intervalMs)
        assertNull(first.config.value.budgetOrNull())
        first.setFollows(false)
        first.setBudgetUsd(1.5)
        first.record(LlmSources.CHAT, LlmProviders.OPENAI, "gpt-4.1", 100, 50, now)
        val second = AiWatchStore(file)
        assertFalse(second.config.value.followsWindow)
        assertEquals(1.5, second.config.value.budgetOrNull()!!, 0.0)
        assertEquals(1, second.summaryAt(now).allTime.calls)
        assertEquals(100, second.summaryAt(now).allTime.promptTokens)
        assertEquals(LlmSources.CHAT, second.summaryAt(now).allTime.bySource.filterValues { it.calls > 0 }.keys.single())
    }

    @Test fun openAiCallsArePricedEvenWhenTheSavedTableOnlyHasGrok() {
        val file = tmp.newFile("grok-only.json")
        file.writeText("""{"prices":[{"model":"grok-4.7","input":2.0,"output":6.0}],"followsWindow":true,"intervalMs":60000}""")
        val store = AiWatchStore(file)
        val now = at(LocalDate.of(2026, 10, 19), 15)
        assertEquals(2.0, LlmPrices.priceFor("gpt-4.1", store.prices())!!.inputPerMillion, 0.0)
        assertEquals(8.0, LlmPrices.priceFor("gpt-4.1", store.prices())!!.outputPerMillion, 0.0)
        assertEquals(1.25, LlmPrices.priceFor("gpt-5", store.prices())!!.inputPerMillion, 0.0)
        assertEquals(5.0, LlmPrices.priceFor("gpt-4o-2024-05-13", store.prices())!!.inputPerMillion, 0.0)
        store.record(LlmSources.CHAT, LlmProviders.OPENAI, "gpt-4.1", 1_000_000, 500_000, now)
        store.record(LlmSources.LIVE_TICK, LlmProviders.OPENAI, "gpt-4.1-2025-04-14", 0, 1_000_000, now)
        val today = store.summaryAt(now).today
        assertEquals(2, today.calls)
        // Chat: 1M in * $2 + 0.5M out * $8. Dated gpt-4.1 snapshot: 1M out * $8.
        assertEquals(2.0 + 4.0 + 8.0, today.costUsd, 1e-9)
        assertEquals(6.0, today.bySource.getValue(LlmSources.CHAT).costUsd, 1e-6)
        val custom = LlmPrices.withDefaults(listOf(ModelPrice("gpt-4.1", 3.0, 9.0)))
        assertEquals(3.0, LlmPrices.priceFor("gpt-4.1", custom)!!.inputPerMillion, 0.0)
        assertEquals("gpt-4.1", LlmSettings.migrateModel("gpt-4.1"))
        assertEquals("gpt-5.4", LlmSettings.migrateModel("gpt-5.4"))
        assertEquals("grok-4.7", LlmSettings.migrateModel("grok-4"))
        val settings = listOf(
            java.io.File("app/src/main/java/com/dirk/kalshiodds/llm/LlmSettings.kt"),
            java.io.File("src/main/java/com/dirk/kalshiodds/llm/LlmSettings.kt")
        ).first { it.isFile }.readText()
        assertTrue(settings.contains("must not clear"))
        val paper = listOf(
            java.io.File("app/src/main/java/com/dirk/kalshiodds/llm/LlmPaperStrategy.kt"),
            java.io.File("src/main/java/com/dirk/kalshiodds/llm/LlmPaperStrategy.kt")
        ).first { it.isFile }.readText()
        assertTrue(paper.contains("delay(15_000)"))
        assertFalse(paper.contains("MIN_SPACING_MS"))
    }

    @Test fun providerResolveFillsDefaultsAndRejectsCleartext() {
        val xai = LlmProviders.resolve(LlmProviders.XAI, "k", "", "")
        assertEquals("https://api.x.ai/v1", xai.baseUrl)
        assertEquals("grok-4.7", xai.model)
        assertTrue(xai.usable)
        val openai = LlmProviders.resolve(LlmProviders.OPENAI, "sk", " ", "")
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
        assertEquals("gpt-4.1", openai.model)
        val mistral = LlmProviders.resolve(LlmProviders.MISTRAL, "sk", "", "")
        assertEquals("https://api.mistral.ai/v1", mistral.baseUrl)
        assertEquals(LlmProviders.MISTRAL_DEFAULT, mistral.model)
        assertEquals("sk", mistral.apiKey)
        assertEquals("mistral-large-latest", LlmProviders.resolve(LlmProviders.MISTRAL, "sk", "mistral-large-latest", "").model)
        val router = LlmProviders.resolve(LlmProviders.OPENROUTER, "sk", "", "")
        assertEquals("https://openrouter.ai/api/v1", router.baseUrl)
        val custom = LlmProviders.resolve(LlmProviders.CUSTOM, "sk", "local", "http://127.0.0.1:11434/v1")
        assertFalse(custom.usable)
        val https = LlmProviders.resolve(LlmProviders.CUSTOM, "sk", "local", "https://llm.example/v1/")
        assertTrue(https.usable)
        assertEquals("https://llm.example/v1", https.baseUrl)
        assertEquals("local", https.model)
    }
}
