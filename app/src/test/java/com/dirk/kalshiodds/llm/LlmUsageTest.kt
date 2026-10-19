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
        assertEquals("mistral-large-latest", mistral.model)
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
