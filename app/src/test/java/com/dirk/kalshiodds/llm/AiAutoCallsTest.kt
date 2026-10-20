package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.MemoryScalpControls
import com.dirk.kalshiodds.decision.ScalpRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketTimeoutException

/** 1.0.17: automatic provider calls are gated, free providers are paced, Gemini uses the native API. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiAutoCallsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun autoOffMakesZeroCallsOnTicksRollsAndFillsAndOneManualMessage() {
        val store = AiWatchStore()
        assertFalse(store.config.value.autoCalls)
        var calls = 0
        val reply = LlmClient.Reply("{}", LlmProviders.GEMINI_DEFAULT, LlmProviders.GEMINI_DEFAULT, 8, 4)
        var now = 1_700_000_000_000L
        val watcher = AiWindowWatcher(
            controls = MemoryScalpControls(),
            store = store,
            snapshot = { WindowSnapshot("W", now + 600_000L, "live window", "fp-$now") },
            ask = { _, _ -> calls++; reply },
            endpoint = { LlmProviders.resolve(LlmProviders.GEMINI, "k", "", "") },
            now = { now },
            alternates = {
                listOf(
                    LlmProviders.resolve(LlmProviders.GROQ, "k", "", ""),
                    LlmProviders.resolve(LlmProviders.OPENAI, "sk", "", "")
                )
            }
        )
        watcher.onWake = { watcher.tick() }
        fun live(key: String, ask: Double, fill: String) = AiLiveSnapshot(
            atMs = now, windowKey = key, ticker = "KXBTC15M-$key", closeMs = now + 600_000L,
            strike = 80_000.0, btc = 80_010.0, yesBid = ask - 0.01, yesAsk = ask,
            noBid = 0.50, noAsk = 0.52, modelUp = 0.55, fillNote = fill
        )
        watcher.noteKalshi(live("W1", 0.40, ""))
        now += 1_000
        watcher.noteKalshi(live("W1", 0.62, ""))
        now += 1_000
        watcher.noteKalshi(live("W2", 0.41, ""))
        now += 1_000
        watcher.noteKalshi(live("W2", 0.41, "fill-1"))
        watcher.tick()
        val quote = ScalpRule.Quote(
            "KXBTC15M-W2", now, now + 900_000L, now, 0.40, 10.0, 0.42, 10.0, 80_000.0, 80_000.0, 0.0001
        )
        val loop = LlmPaperLoop(
            autoCalls = { store.autoAllowed(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT) },
            hasKey = { true },
            quote = { quote },
            call = { calls++; LlmProviders.resolve(LlmProviders.GEMINI, "k", "", "") to reply },
            place = { _, _, _ -> null },
            onUsage = { _, _ -> },
            onCall = { _, _, _ -> }
        )
        assertEquals(AiAutoCalls.PAUSED, loop.tick())
        assertEquals(0, calls)
        assertTrue(store.live.value!!.points.isNotEmpty())
        assertEquals("fill-1", store.live.value!!.fillNote)

        val scope = TestScope(StandardTestDispatcher())
        val chat = AiChatController(
            ChatHistoryStore(tmp.newFile("hand.json")), MemoryScalpControls(),
            ask = { calls++; "gemini-3.8-flash" to "BTC is near the strike." },
            hasKey = { true }, modelName = { LlmProviders.GEMINI_DEFAULT }, scope = scope
        )
        scope.advanceUntilIdle()
        chat.send("what is BTC doing", "leaderboard")
        scope.advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(chat.messages.value.any { it.role == ChatMessage.AI && it.text.contains("BTC is near the strike.") })
    }

    @Test fun freeDefaultsOnPaidDefaultsOffAndUpgradeForcesOff() {
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.GROQ, LlmProviders.GROQ_120))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.OPENROUTER_FREE, LlmProviders.OPENROUTER_FREE_MODEL))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.OPENROUTER, "meta/llama:free"))
        assertFalse(AiAutoCalls.defaultAuto(LlmProviders.OPENAI, "gpt-4.1"))
        assertFalse(AiAutoCalls.defaultAuto(LlmProviders.XAI, "grok-4.7"))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT))
        assertFalse(AiAutoCalls.defaultAuto(LlmProviders.MISTRAL, "mistral-large-latest"))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.MISTRAL, ""))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.MISTRAL, LlmProviders.MISTRAL_NEMO))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.MISTRAL, LlmProviders.MISTRAL_3B))
        assertFalse(AiAutoCalls.defaultAuto(LlmProviders.OPENROUTER, "openai/gpt-4o-mini"))
        assertFalse(AiAutoCalls.defaultAuto(LlmProviders.CUSTOM, "local"))
        assertFalse(AiAutoCalls.upgraded(null))
        assertFalse(AiWatchStore().config.value.autoCalls)
        val file = tmp.newFile("old.json")
        file.writeText("""{"followsWindow":true,"liveFeed":true,"intervalMs":60000}""")
        val upgraded = AiWatchStore(file)
        assertFalse(upgraded.config.value.autoCalls)
        assertTrue(upgraded.config.value.followsWindow)
        assertTrue(upgraded.freeTierOn(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT))
        assertFalse(upgraded.freeTierOn(LlmProviders.OPENAI, "gpt-4.1"))
        assertEquals(500, upgraded.dailyCap(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT))
        assertEquals(1_000, upgraded.dailyCap(LlmProviders.GROQ, LlmProviders.GROQ_120))
        assertEquals(50, upgraded.dailyCap(LlmProviders.OPENROUTER_FREE, LlmProviders.OPENROUTER_FREE_MODEL))
        assertEquals(2_880, upgraded.dailyCap(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT))
        assertTrue(upgraded.freeTierOn(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT))

        val store = AiWatchStore()
        store.setAutoCalls(true)
        assertTrue(store.autoAllowed(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT))
        assertTrue(store.autoAllowed(LlmProviders.OPENAI, "gpt-4.1"))
        store.setFreeTier(LlmProviders.GEMINI, false)
        assertFalse(store.autoAllowed(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT))
        assertTrue(store.blockNotice(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT).contains("manual-only"))
        assertTrue(store.autoAllowed(LlmProviders.OPENAI, "gpt-4.1"))
        store.markBilled(LlmProviders.GROQ, "enable billing on this project")
        assertFalse(store.freeTierOn(LlmProviders.GROQ, LlmProviders.GROQ_120))
        assertFalse(store.autoAllowed(LlmProviders.GROQ, LlmProviders.GROQ_120))
        assertTrue(store.blockNotice(LlmProviders.GROQ, LlmProviders.GROQ_120).contains("billing"))
        store.setFreeTier(LlmProviders.GROQ, true)
        assertTrue(store.autoAllowed(LlmProviders.GROQ, LlmProviders.GROQ_120))
        assertEquals("", store.blockNotice(LlmProviders.GROQ, LlmProviders.GROQ_120))
    }

    @Test fun pacingNeverExceedsMinuteOrDayQuotasOver24Hours() {
        val tokens = AiCompact.GROQ_CALL_TOKENS
        val groq = LlmProviders.effectiveQuota(LlmProviders.GROQ, LlmProviders.GROQ_120, 1_000)!!
        val one = simulate(LlmProviders.GROQ, listOf(LlmProviders.GROQ_120), groq, tokens)
        assertWithin(LlmProviders.GROQ, one, groq, tokens)
        assertTrue(one.size in 900..1_000)
        assertTrue(one.zipWithNext { a, b -> b.first - a.first }.all { it >= 90_000L })

        val rotated = simulate(LlmProviders.GROQ, listOf(LlmProviders.GROQ_120, LlmProviders.GROQ_20), groq, tokens)
        assertWithin(LlmProviders.GROQ, rotated, groq, tokens)
        val byModel = rotated.groupBy { it.second }
        assertEquals(setOf(LlmProviders.GROQ_120, LlmProviders.GROQ_20), byModel.keys)
        byModel.values.forEach { rows ->
            assertTrue(rows.size in 900..1_000)
            assertTrue(rows.zipWithNext { a, b -> b.first - a.first }.all { it >= 90_000L })
        }
        assertTrue(rotated.zipWithNext { a, b -> b.first - a.first }.all { it >= 45_000L })

        val gemini = LlmProviders.effectiveQuota(LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT, 500)!!
        val geminiHits = simulate(LlmProviders.GEMINI, listOf(LlmProviders.GEMINI_DEFAULT), gemini, tokens)
        assertWithin(LlmProviders.GEMINI, geminiHits, gemini, tokens)
        assertTrue(geminiHits.size in 400..500)

        val router = LlmProviders.effectiveQuota(LlmProviders.OPENROUTER_FREE, LlmProviders.OPENROUTER_FREE_MODEL, 50)!!
        val routerHits = simulate(LlmProviders.OPENROUTER_FREE, listOf(LlmProviders.OPENROUTER_FREE_MODEL), router, tokens)
        assertWithin(LlmProviders.OPENROUTER_FREE, routerHits, router, tokens)
        assertTrue(routerHits.size < 50)

        val burst = FreeQuota(2, 1_000, 100, 100_000, 0L)
        val burstHits = simulate("burst", listOf("m"), burst, 40, stepMs = 1_000L, horizonMs = 180_000L)
        assertWithin("burst", burstHits, burst, 40)
        assertTrue(burstHits.size <= 6)
    }

    @Test fun geminiNativeSwitchTimeoutAndNoPaidFallback() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            GeminiApi.url("https://generativelanguage.googleapis.com/v1beta", LlmProviders.GEMINI_DEFAULT)
        )
        assertEquals(45_000L, GeminiApi.TIMEOUT_MS)
        assertTrue(GeminiApi.retired(404, "This model is no longer available to new users"))
        assertTrue(GeminiApi.retired(404, "not found"))
        assertTrue(GeminiApi.retired(404, ""))
        assertTrue(GeminiApi.retired(400, """{"error":{"status":"NOT_FOUND"}}"""))
        assertFalse(GeminiApi.retired(400, "bad request"))
        assertFalse(GeminiApi.retired(429, "rate limit"))
        assertFalse(GeminiApi.retired(200, """{"candidates":[{"content":{"parts":[{"text":"strike not found"}]}}]}"""))
        assertFalse(GeminiApi.billing(429, "rate limit"))
        assertTrue(GeminiApi.billing(402, ""))
        assertTrue(GeminiApi.billing(403, "Please enable billing"))
        assertEquals(2_000L, LlmClient.retryAfterMs("2"))
        assertEquals(60_000L, LlmClient.retryAfterMs("soon"))
        var posts = 0
        var switched: String? = null
        val reply = GeminiApi.exchange(
            "https://generativelanguage.googleapis.com/v1beta",
            LlmProviders.GEMINI_DEFAULT,
            listOf("system" to "be brief", "user" to "hi"),
            32,
            post = { url, _ ->
                posts++
                if (posts == 1) {
                    assertTrue(url.endsWith("/models/${LlmProviders.GEMINI_DEFAULT}:generateContent"))
                    GeminiApi.Raw(404, "gemini model is no longer available to new users")
                } else {
                    assertTrue(url.endsWith("/models/${LlmProviders.GEMINI_FALLBACK}:generateContent"))
                    GeminiApi.Raw(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":1}}""")
                }
            },
            onSwitch = { switched = it }
        )
        assertEquals(2, posts)
        assertEquals(LlmProviders.GEMINI_FALLBACK, switched)
        assertEquals("ok", reply.text)
        assertEquals(4L, reply.promptTokens)
        posts = 0
        try {
            GeminiApi.exchange("https://generativelanguage.googleapis.com/v1beta", LlmProviders.GEMINI_DEFAULT, listOf("user" to "hi"), 8,
                post = { _, _ -> posts++; throw SocketTimeoutException("timed out") })
            error("timeout must skip")
        } catch (e: LlmSkipped) {
            assertEquals("timeout", e.message)
        }
        assertEquals(1, posts)
        assertNull(LlmProviders.geminiFallback(LlmProviders.GEMINI_FALLBACK))

        val flight = LlmFlight()
        assertTrue(flight.enter(true))
        assertFalse(flight.enter(true))
        flight.exit()
        assertTrue(flight.enter(false))
        flight.exit()

        val seen = mutableListOf<String>()
        val groq = LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")
        val gemini = LlmProviders.resolve(LlmProviders.GEMINI, "k", "", "")
        val paid = LlmProviders.resolve(LlmProviders.OPENAI, "sk", "", "")
        val hit = AiAutoCalls.callFree(
            selected = groq,
            slots = listOf(gemini, paid),
            pacer = FreeCallPacer(),
            nowMs = 0L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 20,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { LlmProviders.freeFamily(it.provider, it.model) },
            ask = { ep, _ ->
                seen += ep.provider
                if (ep.provider == LlmProviders.GROQ) throw LlmRateLimited(60_000L, "429")
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1, remainingRequests = 3, remainingTokens = 100)
            },
            onBilling = { _, _ -> error("not billing") }
        )
        assertEquals(LlmProviders.GEMINI, hit!!.first.provider)
        assertEquals(listOf(LlmProviders.GROQ, LlmProviders.GEMINI), seen)
        val msgs = AiCompact.watch("word ".repeat(2_000))
        assertTrue(AiCompact.tokens(msgs, AiCompact.RESERVE_COMPLETION) <= AiCompact.GROQ_CALL_TOKENS)

        val store = AiWatchStore()
        val now = 1_700_000_000_000L
        store.setPrices(listOf(ModelPrice(LlmProviders.GROQ_120, 9.0, 9.0)))
        store.record(LlmSources.WATCHER, LlmProviders.GROQ, LlmProviders.GROQ_120, 1_000_000, 1_000_000, now)
        assertEquals(0.0, store.summaryAt(now).today.costUsd, 0.0)
        assertEquals(1, store.callsFor(LlmProviders.GROQ, LlmProviders.GROQ_120, now))

        val src = listOf("LlmProviders.kt", "GeminiApi.kt", "LlmClient.kt", "AiAutoCalls.kt", "AiUsageCard.kt").joinToString("\n") { name ->
            listOf(
                java.io.File("app/src/main/java/com/dirk/kalshiodds/llm/$name"),
                java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/$name"),
                java.io.File("src/main/java/com/dirk/kalshiodds/llm/$name"),
                java.io.File("src/main/java/com/dirk/kalshiodds/ui/$name")
            ).first { it.isFile }.readText()
        }
        assertFalse(src.contains("gemini-2.5"))
        assertFalse(src.contains("2.5-flash"))
        assertTrue(src.contains(LlmProviders.GEMINI_DEFAULT))
        assertTrue(src.contains(LlmProviders.GEMINI_FALLBACK))
        assertTrue(src.contains(":generateContent"))
        assertTrue(src.contains("x-goog-api-key"))
        assertTrue(src.contains(AiAutoCalls.SWITCH))
        assertTrue(src.contains(AiAutoCalls.FREE_TIER))
        assertTrue(src.contains(AiAutoCalls.PAUSED))
        assertTrue(src.contains(LlmProviders.FREE_KEY_HINT))
        val groqEp = LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")
        assertEquals("https://api.groq.com/openai/v1", groqEp.baseUrl)
        assertEquals(LlmProviders.GROQ_120, groqEp.model)
        assertTrue(LlmProviders.resolve(LlmProviders.OPENROUTER_FREE, "k", "", "").model.endsWith(":free"))
    }

    private fun simulate(
        provider: String,
        models: List<String>,
        quota: FreeQuota,
        tokens: Int,
        stepMs: Long = 1_000L,
        horizonMs: Long = 86_400_000L
    ): List<Pair<Long, String>> {
        val pacer = FreeCallPacer()
        val hits = ArrayList<Pair<Long, String>>()
        var t = 0L
        while (t < horizonMs) {
            val model = pacer.allow(provider, models, quota, t, tokens)
            if (model != null) {
                pacer.record(provider, model, t, tokens)
                hits += t to model
            }
            t += stepMs
        }
        return hits
    }

    private fun assertWithin(provider: String, hits: List<Pair<Long, String>>, quota: FreeQuota, tokens: Int) {
        val grouped = hits.groupBy { it.second }
        for ((model, rows) in grouped) {
            val times = rows.map { it.first }
            for (t in times) {
                val minute = times.count { t - it in 0 until 60_000L }
                assertTrue("$provider $model rpm $minute at $t", minute <= quota.requestsPerMinute)
                val day = times.count { t - it in 0 until 86_400_000L }
                assertTrue("$provider $model rpd $day at $t", day <= quota.requestsPerDay)
                val minuteTokens = minute.toLong() * tokens
                assertTrue("$provider $model tpm $minuteTokens", minuteTokens <= quota.tokensPerMinute)
                val dayTokens = day.toLong() * tokens
                assertTrue("$provider $model tpd $dayTokens", dayTokens <= quota.tokensPerDay)
            }
        }
    }
}
