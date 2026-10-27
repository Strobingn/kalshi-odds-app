package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSpeedTest {
    private val all = listOf("groq", "cerebras", "gemini", "mistral")

    private class FakeClock(var now: Long = 1_000L)

    private fun speedAt(c: FakeClock) = LlmSpeed(nowMs = { c.now })

    @Test
    fun beforeAnyCallTheCustomSiliconProvidersGoFirst() {
        val s = LlmSpeed()
        assertEquals(listOf("cerebras", "groq", "gemini", "mistral"), s.order(all))
        assertNull("nothing timed yet", s.millisOf("groq"))
    }

    @Test
    fun theQuickestProviderAnswersNext() {
        val s = LlmSpeed()
        s.answered("gemini", 400L)
        s.answered("cerebras", 1_500L)
        assertEquals("gemini is measured quicker than cerebras", listOf("gemini", "cerebras", "groq", "mistral"), s.order(all))
        assertEquals(400L, s.millisOf("gemini"))
        assertEquals("gemini", s.state.value.provider)
    }

    @Test
    fun anUntimedProviderStaysBehindAQuickMeasuredOneAndAheadOfASlowOne() {
        val s = LlmSpeed()
        s.answered("mistral", 300L)
        s.answered("gemini", 9_000L)
        assertEquals(listOf("mistral", "cerebras", "groq", "gemini"), s.order(all))
    }

    @Test
    fun theRunningAverageFollowsTheLastAnswers() {
        val s = LlmSpeed()
        s.answered("groq", 1_000L)
        s.answered("groq", 2_000L)
        // 1000 + 0.35 * (2000 - 1000)
        assertEquals(1_350L, s.millisOf("groq"))
    }

    @Test
    fun anEarlyLookDoesNotCallAnyoneWhoIsStillResting() {
        val c = FakeClock()
        val s = speedAt(c)
        s.failed("cerebras", "HTTP 429 rate limit")
        s.failed("groq", "HTTP 429 quota exhausted")
        val keyed = listOf("cerebras", "groq")
        assertEquals("nobody to call until a rest is over", emptyList<String>(), s.ready(keyed))

        val chat = HttpLlmChat(
            listOf(
                LlmProvider("cerebras", "http://127.0.0.1:1", listOf("m1")) { "k" },
                LlmProvider("groq", "http://127.0.0.1:1", listOf("m2")) { "k" }
            ),
            speed = s,
            http = okhttp3.OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        )
        assertNull(chat.chat(listOf("user" to "hi")))
        assertEquals("the early look did not spend another call", LlmSpeed.LIMIT_COOLDOWN_MS, s.waitMs(keyed, floorMs = 1_000L))
    }

    @Test
    fun aTimeoutCountsTowardTheAverageSoTheProviderDropsDown() {
        val c = FakeClock()
        val s = speedAt(c)
        s.answered("cerebras", 300L)
        s.answered("groq", 900L)
        s.failed("cerebras", "SocketTimeoutException", 15_000L)
        // 300 + 0.35 * (15000 - 300) = 5445
        assertEquals(5_445L, s.millisOf("cerebras"))
        assertEquals("still resting off the timeout", emptyList<String>(), s.ready(listOf("cerebras")))

        c.now += LlmSpeed.ERROR_COOLDOWN_MS + 1
        assertEquals("groq is ahead once the rest ends", "groq", s.ready(all).first())
        assertTrue(s.ready(all).indexOf("groq") < s.ready(all).indexOf("cerebras"))
    }

    @Test
    fun aHangingCallIsRecordedAsSlow() {
        val s = LlmSpeed()
        s.answered("cerebras", 300L)
        s.answered("groq", 900L)
        val http = okhttp3.OkHttpClient.Builder()
            .addInterceptor {
                Thread.sleep(40)
                throw java.io.InterruptedIOException("timeout")
            }
            .build()
        val chat = HttpLlmChat(
            listOf(
                LlmProvider("cerebras", "http://127.0.0.1:1", listOf("m1")) { "k" },
                LlmProvider("groq", "http://127.0.0.1:1", listOf("m2")) { "k" }
            ),
            speed = s,
            http = http
        )
        assertNull(chat.chat(listOf("user" to "hi")))
        assertTrue("the hang was folded into cerebras's time", s.millisOf("cerebras") != 300L)
        assertTrue(s.state.value.resting.contains("cerebras"))
    }

    @Test
    fun aRateLimitedProviderRestsAndTheNextFastestAnswers() {
        val c = FakeClock()
        val s = speedAt(c)
        s.answered("cerebras", 300L)
        s.answered("groq", 900L)
        assertEquals("cerebras", s.order(all).first())

        s.failed("cerebras", "HTTP 429 Rate limit reached for gpt-oss-120b")
        assertEquals("groq", s.order(all).first())
        assertTrue("cerebras is resting", s.state.value.resting.contains("cerebras"))

        c.now += LlmSpeed.LIMIT_COOLDOWN_MS + 1
        assertEquals("the rest is over", "cerebras", s.order(all).first())
    }

    @Test
    fun eachLimitInARowDoublesTheRestAndAnAnswerClearsIt() {
        val c = FakeClock()
        val s = speedAt(c)
        s.failed("cerebras", "HTTP 429 rate limit")
        assertEquals(LlmSpeed.LIMIT_COOLDOWN_MS, s.waitMs(listOf("cerebras"), floorMs = 1_000L))

        c.now += LlmSpeed.LIMIT_COOLDOWN_MS
        s.failed("cerebras", "HTTP 429 quota exhausted")
        assertEquals(2 * LlmSpeed.LIMIT_COOLDOWN_MS, s.waitMs(listOf("cerebras"), floorMs = 1_000L))

        c.now += 2 * LlmSpeed.LIMIT_COOLDOWN_MS
        s.answered("cerebras", 300L)
        assertEquals("an answer clears the rest", 1_000L, s.waitMs(listOf("cerebras"), floorMs = 1_000L))

        s.failed("cerebras", "HTTP 429 rate limit")
        assertEquals("and the doubling starts over", LlmSpeed.LIMIT_COOLDOWN_MS, s.waitMs(listOf("cerebras"), floorMs = 1_000L))
    }

    @Test
    fun aSpentDailyQuotaCostsAtMostOneCallEveryFiveMinutes() {
        val c = FakeClock()
        val s = speedAt(c)
        repeat(12) {
            s.failed("cerebras", "HTTP 429 quota exhausted")
            c.now += s.waitMs(listOf("cerebras"), floorMs = 1_000L)
        }
        s.failed("cerebras", "HTTP 429 quota exhausted")
        assertEquals(LlmSpeed.MAX_COOLDOWN_MS, s.waitMs(listOf("cerebras"), floorMs = 1_000L))
    }

    @Test
    fun aBrokenProviderRestsLessLongThanARateLimitedOne() {
        val c = FakeClock()
        val s = speedAt(c)
        s.failed("groq", "SocketTimeoutException")
        assertEquals(LlmSpeed.ERROR_COOLDOWN_MS, s.waitMs(listOf("groq"), floorMs = 1_000L))
    }

    @Test
    fun theLoopWaitsTheFloorWhileAnyProviderIsReady() {
        val c = FakeClock()
        val s = speedAt(c)
        s.failed("cerebras", "HTTP 429 rate limit")
        assertEquals("groq is still ready", 10_000L, s.waitMs(listOf("cerebras", "groq"), floorMs = 10_000L))
    }

    @Test
    fun theLoopWaitsForTheFirstProviderBackWhenEveryOneIsResting() {
        val c = FakeClock()
        val s = speedAt(c)
        s.failed("cerebras", "HTTP 429 rate limit")
        c.now += 5_000L
        s.failed("groq", "SocketTimeoutException")
        // groq comes back first: 5 s in, resting ERROR_COOLDOWN_MS.
        assertEquals(LlmSpeed.ERROR_COOLDOWN_MS, s.waitMs(listOf("cerebras", "groq"), floorMs = 10_000L))
    }

    @Test
    fun aProvidersOwnWordsSayWhetherItWasALimit() {
        assertTrue(LlmSpeed.isLimit("HTTP 429 Rate limit reached"))
        assertTrue(LlmSpeed.isLimit("HTTP 400 You exceeded your current quota"))
        assertTrue(LlmSpeed.isLimit("RESOURCE_EXHAUSTED"))
        assertTrue(LlmSpeed.isLimit("Too Many Requests"))
        assertEquals(false, LlmSpeed.isLimit("HTTP 401 Invalid API key"))
        assertEquals(false, LlmSpeed.isLimit("SocketTimeoutException"))
    }

    @Test
    fun theKeysScreenNamesTheProviderAnsweringNow() {
        val c = FakeClock()
        val s = speedAt(c)
        assertTrue(s.line(emptyList()).startsWith("No API key saved yet"))
        s.answered("groq", 820L)
        assertEquals("Fastest first: Groq · 820 ms a call", s.line(listOf("groq")))
        s.failed("cerebras", "HTTP 429 rate limit")
        assertEquals("Fastest first: Groq · 820 ms a call · resting: Cerebras", s.line(listOf("groq", "cerebras")))
    }

    @Test
    fun theAiProviderLoopPutsAFailingProviderToRest() {
        val s = LlmSpeed()
        val chat = HttpLlmChat(
            listOf(
                LlmProvider("groq", "http://127.0.0.1:1", listOf("m1")) { "k" },
                LlmProvider("cerebras", "http://127.0.0.1:1", listOf("m2")) { "k" }
            ),
            speed = s,
            http = okhttp3.OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        )
        assertNull(chat.chat(listOf("user" to "hi")))
        assertEquals("both are resting after the call", listOf("cerebras", "groq"), s.state.value.resting)
    }

    @Test
    fun everyProviderModelListLeadsWithItsQuickestModel() {
        assertEquals("openai/gpt-oss-20b", LlmProvider.GROQ_MODELS.first())
        assertEquals("gpt-oss-120b", LlmProvider.CEREBRAS_MODELS.first())
        assertEquals("gemini-3.1-flash-lite", LlmProvider.GEMINI_MODELS.first())
        assertEquals("ministral-8b-latest", LlmProvider.MISTRAL_MODELS.first())
    }
}
