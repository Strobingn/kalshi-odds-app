package com.dirk.kalshiodds.llm

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 1.0.18: Mistral free plan, Gemini 404 fallback, and the usage-chart aggregations. */
class MistralFreeTierTest {
    private val et = ZoneId.of("America/New_York")
    private fun at(day: LocalDate, hour: Int) = day.atTime(LocalTime.of(hour, 0)).atZone(et).toInstant().toEpochMilli()

    @Before fun resetBuses() {
        MonthlyTokenCaps.reset()
        LlmEvents.drain()
    }

    @After fun clearMonthlyCaps() {
        MonthlyTokenCaps.reset()
        LlmEvents.drain()
    }

    @Test fun mistralIsFreeWithTheLivePlanChainAndKeepsASavedKey() {
        val ep = LlmProviders.resolve(LlmProviders.MISTRAL, "sk-free", "", "")
        assertEquals("https://api.mistral.ai/v1", ep.baseUrl)
        assertEquals(LlmProviders.MISTRAL_DEFAULT, ep.model)
        assertEquals("sk-free", ep.apiKey)
        assertTrue(ep.usable)
        assertTrue(LlmProviders.freeFamily(LlmProviders.MISTRAL, ""))
        assertTrue(LlmProviders.freeFamily(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT))
        assertFalse(LlmProviders.freeFamily(LlmProviders.MISTRAL, "mistral-large-latest"))
        assertTrue(LlmProviders.spec(LlmProviders.MISTRAL).free)
        assertEquals(
            listOf(LlmProviders.MISTRAL_DEFAULT, LlmProviders.MISTRAL_NEMO, LlmProviders.MISTRAL_3B),
            LlmProviders.MISTRAL_CHAIN
        )
        assertEquals(LlmProviders.MISTRAL_DEFAULT, LlmProviders.mistralFallback("mistral-large-latest"))
        assertEquals(LlmProviders.MISTRAL_NEMO, LlmProviders.mistralFallback(LlmProviders.MISTRAL_DEFAULT))
        assertEquals(LlmProviders.MISTRAL_3B, LlmProviders.mistralFallback(LlmProviders.MISTRAL_NEMO))
        assertNull(LlmProviders.mistralFallback(LlmProviders.MISTRAL_3B))
        val kept = LlmProviders.resolve(LlmProviders.MISTRAL, "sk-free", "mistral-large-latest", "")
        assertEquals("mistral-large-latest", kept.model)
        assertEquals("sk-free", kept.apiKey)
        assertEquals(listOf(LlmProviders.GROQ, LlmProviders.MISTRAL, LlmProviders.GEMINI, LlmProviders.OPENROUTER_FREE), LlmProviders.FREE_IDS)
        assertEquals("Enter your Mistral API key", LlmProviders.keyPrompt(LlmProviders.MISTRAL))
        assertTrue(AiAutoCalls.defaultAuto(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT))
        val quota = LlmProviders.effectiveQuota(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 2_880)!!
        assertEquals(120_000L, LlmProviders.MISTRAL_PACE_MS)
        assertEquals(LlmProviders.MISTRAL_PACE_MS, quota.perModelPaceMs)
        assertEquals(188, quota.requestsPerMinute)
        assertEquals(625_000L, quota.tokensPerMinute)
        assertEquals(2_880, quota.requestsPerDay)
        assertEquals("Auto pace: every 120s", AiAutoCalls.paceLabel(true, ep, quota))
        val settings = source("LlmSettings.kt")
        val saveModel = settings.substring(settings.indexOf("fun saveModelOnly"), settings.indexOf("companion object"))
        assertFalse(saveModel.contains("key."))
    }

    @Test fun planRejectionSwitchesModelsAndAReal429DoesNot() {
        val switched = ArrayList<String>()
        var posts = 0
        val reply = MistralApi.exchange(
            "mistral-large-latest",
            listOf("user" to "hi"),
            16,
            post = { model, _ ->
                posts++
                when (model) {
                    "mistral-large-latest" -> MistralApi.Raw(403, "forbidden")
                    LlmProviders.MISTRAL_DEFAULT -> MistralApi.Raw(
                        429, "not on plan", mapOf("x-ratelimit-limit-req-minute" to "0")
                    )
                    else -> MistralApi.Raw(
                        200,
                        """{"model":"$model","choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":4,"completion_tokens":1}}""",
                        mapOf("x-ratelimit-remaining-req-minute" to "180", "x-ratelimit-remaining-tokens-minute" to "600000")
                    )
                }
            },
            onSwitch = { from, to -> switched += "$from->$to" }
        )
        assertEquals(3, posts)
        assertEquals(listOf("mistral-large-latest->${LlmProviders.MISTRAL_DEFAULT}", "${LlmProviders.MISTRAL_DEFAULT}->${LlmProviders.MISTRAL_NEMO}"), switched)
        assertEquals("ok", reply.text)
        assertEquals(LlmProviders.MISTRAL_NEMO, reply.model)
        assertEquals(180 to 600_000, LlmClient.remaining(mapOf(
            "X-RateLimit-Remaining-Req-Minute" to "180",
            "x-ratelimit-remaining-tokens-minute" to "600000"
        )))
        assertTrue(MistralApi.notOnPlan(403, emptyMap()))
        assertTrue(MistralApi.notOnPlan(429, mapOf("x-ratelimit-limit-req-minute" to "0")))
        assertFalse(MistralApi.notOnPlan(429, mapOf("x-ratelimit-limit-req-minute" to "188")))
        assertFalse(MistralApi.notOnPlan(429, emptyMap()))
        assertFalse(MistralApi.notOnPlan(401, emptyMap()))
        assertEquals(45_000L, MistralApi.TIMEOUT_MS)

        posts = 0
        try {
            MistralApi.exchange(LlmProviders.MISTRAL_DEFAULT, listOf("user" to "hi"), 8, post = { _, _ ->
                posts++
                MistralApi.Raw(429, "busy", mapOf("x-ratelimit-limit-req-minute" to "188", "retry-after" to "2"))
            })
            error("real 429 must not switch models")
        } catch (e: LlmRateLimited) {
            assertEquals(2_000L, e.retryAfterMs)
        }
        assertEquals(1, posts)

        posts = 0
        var depth = 0
        var maxDepth = 0
        try {
            MistralApi.exchange(LlmProviders.MISTRAL_DEFAULT, listOf("user" to "hi"), 8, post = { _, _ ->
                depth++
                if (depth > maxDepth) maxDepth = depth
                posts++
                throw SocketTimeoutException("timed out")
            })
            error("timeout must skip")
        } catch (e: LlmSkipped) {
            assertEquals("timeout", e.message)
        } finally {
            depth--
        }
        assertEquals(1, posts)
        assertEquals(1, maxDepth)
    }

    @Test fun real429BacksOffExponentiallyAndRemainingHeadersStretchThePace() {
        val quota = LlmProviders.effectiveQuota(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 2_880)!!
        val pacer = FreeCallPacer()
        val model = LlmProviders.MISTRAL_DEFAULT
        pacer.note429(LlmProviders.MISTRAL, model, 30_000L, 0L)
        assertNull(pacer.allow(LlmProviders.MISTRAL, listOf(model), quota, 119_000L, 100))
        assertNotNull(pacer.allow(LlmProviders.MISTRAL, listOf(model), quota, 120_000L, 100))
        pacer.note429(LlmProviders.MISTRAL, model, 30_000L, 120_000L)
        assertNull(pacer.allow(LlmProviders.MISTRAL, listOf(model), quota, 359_000L, 100))
        assertNotNull(pacer.allow(LlmProviders.MISTRAL, listOf(model), quota, 360_000L, 100))
        pacer.record(LlmProviders.MISTRAL, model, 360_000L, 100)
        pacer.note429(LlmProviders.MISTRAL, model, 30_000L, 360_000L)
        assertNull(pacer.allow(LlmProviders.MISTRAL, listOf(model), quota, 479_000L, 100))
        assertNotNull(pacer.allow(LlmProviders.MISTRAL, listOf(model), quota, 480_000L, 100))

        val paced = FreeCallPacer()
        paced.record(LlmProviders.MISTRAL, model, 0L, 100)
        paced.noteRemaining(LlmProviders.MISTRAL, model, 180, 500_000, 0L)
        assertNull(paced.allow(LlmProviders.MISTRAL, listOf(model), quota, 0L, 100))
        assertNotNull(paced.allow(LlmProviders.MISTRAL, listOf(model), quota, LlmProviders.MISTRAL_PACE_MS, 100))
        paced.record(LlmProviders.MISTRAL, model, 30_000L, 100)
        paced.noteRemaining(LlmProviders.MISTRAL, model, 4, 400_000, 30_000L)
        assertNull(paced.allow(LlmProviders.MISTRAL, listOf(model), quota, 149_000L, 100))
        assertNotNull(paced.allow(LlmProviders.MISTRAL, listOf(model), quota, 150_000L, 100))
    }

    @Test fun freeFallbackIsMistralThenGroqThenGeminiAndNeverPaid() {
        val seen = mutableListOf<String>()
        val pacer = FreeCallPacer()
        val quota = LlmProviders.effectiveQuota(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 2_880)!!
        val mistral = LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", "")
        val groq = LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")
        val gemini = LlmProviders.resolve(LlmProviders.GEMINI, "k", "", "")
        val paid = LlmProviders.resolve(LlmProviders.OPENAI, "sk", "", "")
        val hit = AiAutoCalls.callFree(
            selected = groq,
            slots = listOf(paid, gemini, mistral),
            pacer = pacer,
            nowMs = 0L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 20,
            quotaOf = { LlmProviders.effectiveQuota(it.provider, it.model, LlmProviders.defaultDailyCap(it.provider, it.model)) },
            allowed = { LlmProviders.freeFamily(it.provider, it.model) },
            ask = { ep, _ ->
                seen += ep.provider
                if (ep.provider != LlmProviders.MISTRAL) throw LlmRateLimited(30_000L, "429")
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> error("not billing") }
        )
        assertEquals(LlmProviders.MISTRAL, hit!!.first.provider)
        assertEquals(listOf(LlmProviders.GROQ, LlmProviders.MISTRAL), seen)
        assertFalse(seen.contains(LlmProviders.OPENAI))

        seen.clear()
        val fresh = FreeCallPacer()
        AiAutoCalls.callFree(
            selected = mistral,
            slots = listOf(groq),
            pacer = fresh,
            nowMs = 0L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 20,
            quotaOf = { quota },
            allowed = { true },
            ask = { ep, _ ->
                seen += ep.provider
                if (ep.provider == LlmProviders.MISTRAL) throw LlmModelUnavailable(LlmProviders.MISTRAL, "model not on plan")
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> }
        )
        assertEquals(listOf(LlmProviders.MISTRAL, LlmProviders.GROQ), seen)
        assertNotNull(fresh.allow(LlmProviders.MISTRAL, listOf(LlmProviders.MISTRAL_DEFAULT), quota, 0L, 100))
    }

    @Test fun sixtySecondPaceStaysInsideTheFreeMinuteAndDayCeilings() {
        val quota = LlmProviders.effectiveQuota(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 2_880)!!
        val pacer = FreeCallPacer()
        val hits = ArrayList<Long>()
        var t = 0L
        while (t < 180_000L) {
            val model = pacer.allow(LlmProviders.MISTRAL, listOf(LlmProviders.MISTRAL_DEFAULT), quota, t, 180)
            if (model != null) {
                pacer.record(LlmProviders.MISTRAL, model, t, 180)
                hits += t
            }
            t += 1_000L
        }
        assertEquals(2, hits.size)
        assertEquals(0L, hits.first())
        assertEquals(120_000L, hits.last())
        assertTrue(hits.zipWithNext { a, b -> b - a }.all { it >= LlmProviders.MISTRAL_PACE_MS })
        val day = FreeCallPacer()
        var n = 0
        var prev = Long.MIN_VALUE
        t = 0L
        while (t < 86_400_000L) {
            val model = day.allow(LlmProviders.MISTRAL, listOf(LlmProviders.MISTRAL_DEFAULT), quota, t, 180)
            if (model != null) {
                day.record(LlmProviders.MISTRAL, model, t, 180)
                if (prev != Long.MIN_VALUE) assertTrue(t - prev >= 120_000L)
                prev = t
                n++
            }
            t += 120_000L
        }
        assertEquals(720, n)
        assertTrue(n < quota.requestsPerDay)
    }

    @Test fun monthlyTokenCapStopsAutomaticCallsAndCostsStayZero() {
        val store = AiWatchStore()
        val now = at(LocalDate.of(2026, 10, 19), 15)
        assertTrue(store.freeTierOn(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT))
        assertTrue(store.monthlyRoom(LlmProviders.MISTRAL, now))
        store.setMonthlyTokenCap(LlmProviders.MISTRAL, 1_000)
        store.record(LlmSources.WATCHER, LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 800, 100, now, latencyMs = 400, automatic = true)
        assertEquals(900L, store.monthTokens(LlmProviders.MISTRAL, now))
        assertTrue(store.monthlyRoom(LlmProviders.MISTRAL, now))
        store.record(LlmSources.CHAT, LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 200, 50, now + 1_000)
        assertFalse(store.monthlyRoom(LlmProviders.MISTRAL, now))
        assertFalse(MonthlyTokenCaps.room(LlmProviders.MISTRAL, LlmPrices.monthKey(now)))
        store.setPrices(store.prices().map {
            if (it.model == LlmProviders.MISTRAL_DEFAULT) it.copy(inputPerMillion = 9.0, outputPerMillion = 9.0) else it
        })
        assertEquals(0.0, store.summaryAt(now).today.costUsd, 0.0)
        val groq = LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")
        val mistral = LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", "")
        val seen = mutableListOf<String>()
        val hit = AiAutoCalls.callFree(
            selected = mistral,
            slots = listOf(groq),
            pacer = FreeCallPacer(),
            nowMs = now,
            messages = listOf("user" to "hi"),
            reserveCompletion = 20,
            quotaOf = { ep ->
                if (!store.monthlyRoom(ep.provider, now)) null
                else LlmProviders.effectiveQuota(ep.provider, ep.model, store.dailyCap(ep.provider, ep.model))
            },
            allowed = { LlmProviders.freeFamily(it.provider, it.model) },
            ask = { ep, _ ->
                seen += ep.provider
                LlmClient.Reply("ok", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> }
        )
        assertEquals(LlmProviders.GROQ, hit!!.first.provider)
        assertEquals(listOf(LlmProviders.GROQ), seen)
        val manual = store.samples().single { it.source == LlmSources.CHAT }
        assertFalse(manual.automatic)
        assertEquals(400L, store.samples().first().latencyMs)
    }

    @Test fun usageChartsUseRecordedCallsOnly() {
        val day = LocalDate.of(2026, 10, 19)
        val now = at(day, 15)
        val store = AiWatchStore()
        store.record(LlmSources.WATCHER, LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 120, 40, now, latencyMs = 420, automatic = true)
        store.record(LlmSources.CHAT, LlmProviders.GROQ, LlmProviders.GROQ_120, 10, 5, at(day, 14), latencyMs = 80, automatic = false)
        store.record(LlmSources.WINDOW, LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT, 3_000, 0, at(day, 9), latencyMs = -1, automatic = true)
        val stacks = AiUsageCharts.callsPerHourByProvider(store.hourBuckets(), now)
        assertEquals(24, stacks.size)
        assertEquals("3p", stacks.last().label)
        assertEquals(1, stacks.last().byProvider[LlmProviders.MISTRAL])
        assertEquals(1, stacks[stacks.indexOfLast { it.label == "2p" }].byProvider[LlmProviders.GROQ])
        assertEquals(0, stacks.first().byProvider.values.sum())
        val old = UsageHour(day.toString(), 9, LlmSources.WINDOW, LlmProviders.GEMINI_DEFAULT, 2, 0, 0)
        assertEquals(LlmProviders.GEMINI, LlmProviders.chartProvider(old.provider, old.model))
        val limits = LlmProviders.FREE_IDS.associateWith { store.dailyCap(it, "") }
        val versus = AiUsageCharts.callsVsDailyLimit(store.buckets(), limits, now)
        assertEquals(LlmProviders.GROQ, versus.first().provider)
        assertEquals(1, versus.first { it.provider == LlmProviders.MISTRAL }.calls)
        assertEquals(2_880, versus.first { it.provider == LlmProviders.MISTRAL }.limit)
        assertEquals(1, versus.first { it.provider == LlmProviders.GROQ }.calls)
        val bins = AiUsageCharts.tokenHistogram(store.samples())
        assertEquals(1, bins.first { it.label == "100–250" }.count)
        assertEquals(1, bins.first { it.label == "<100" }.count)
        assertEquals(1, bins.first { it.label == "2k–4k" }.count)
        val split = AiUsageCharts.autoVsManual(store.samples())
        assertEquals(2, split.auto)
        assertEquals(1, split.manual)
        val latency = AiUsageCharts.latencyByProvider(store.samples())
        assertEquals(setOf(LlmProviders.MISTRAL, LlmProviders.GROQ), latency.map { it.provider }.toSet())
        assertEquals(420.0, latency.first { it.provider == LlmProviders.MISTRAL }.points.single().second, 0.0)
        assertTrue(AiUsageCharts.tokenHistogram(emptyList()).isEmpty())
        LlmEvents.push(ModelSwitchEvent(now, LlmProviders.MISTRAL, "mistral-large-latest", LlmProviders.MISTRAL_DEFAULT, "model not on plan"))
        val switches = store.modelSwitches()
        assertEquals("mistral-large-latest", switches.single().fromModel)
        assertEquals(LlmProviders.MISTRAL_DEFAULT, switches.single().toModel)
        val card = source("AiUsageCard.kt")
        val status = card.indexOf("Active provider:")
        val prompt = card.indexOf("keyPrompt(")
        val lower = card.indexOf("Provider for chat and the window watcher")
        assertTrue(status in 0 until prompt && prompt < lower)
        assertTrue(card.contains("Calls per hour by provider"))
        assertTrue(card.contains("Calls today vs free daily limit"))
        assertTrue(card.contains("HistogramChart"))
        assertTrue(card.contains("ShareDonut"))
        assertTrue(card.contains("MultiLineChart"))
        assertTrue(card.contains("TweakTimeline"))
        assertTrue(card.contains("Monthly token cap"))
        val client = source("LlmClient.kt")
        val enter = client.indexOf("flight.enter")
        val branch = client.indexOf("ep.provider == LlmProviders.MISTRAL")
        val exit = client.indexOf("flight.exit()")
        assertTrue(enter in 0 until branch && branch < exit)
        assertTrue(client.contains("MistralApi.TIMEOUT_MS"))
    }

    @Test fun gemini404WithAnyBodySwitchesOnce() {
        var posts = 0
        val reply = GeminiApi.exchange(
            "https://generativelanguage.googleapis.com/v1beta",
            LlmProviders.GEMINI_DEFAULT,
            listOf("user" to "hi"),
            8,
            post = { url, _ ->
                posts++
                if (posts == 1) {
                    assertTrue(url.contains(LlmProviders.GEMINI_DEFAULT))
                    GeminiApi.Raw(404, "not found")
                } else {
                    assertTrue(url.contains(LlmProviders.GEMINI_FALLBACK))
                    GeminiApi.Raw(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1}}""")
                }
            }
        )
        assertEquals(2, posts)
        assertEquals("ok", reply.text)
        posts = 0
        try {
            GeminiApi.exchange("https://generativelanguage.googleapis.com/v1beta", LlmProviders.GEMINI_FALLBACK, listOf("user" to "hi"), 8,
                post = { _, _ -> posts++; GeminiApi.Raw(404, "") })
            error("fallback has nowhere further to go")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertEquals(1, posts)
        assertFalse(GeminiApi.retired(200, "the order was not found"))
    }

    private fun source(name: String): String = listOf(
        java.io.File("app/src/main/java/com/dirk/kalshiodds/llm/$name"),
        java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/$name"),
        java.io.File("src/main/java/com/dirk/kalshiodds/llm/$name"),
        java.io.File("src/main/java/com/dirk/kalshiodds/ui/$name")
    ).first { it.isFile }.readText()
}
