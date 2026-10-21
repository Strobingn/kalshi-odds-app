package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.ScalpRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Built-in LLM paper strategy may decide again through the window. Paid models never call themselves. */
class LlmPaperRepeatTest {
    private val now = 1_700_000_000_000L

    @Test
    fun repeatedDecisionsThroughTheWindowExceptTheLastMinute() {
        var calls = 0
        val loop = loop(calls = { calls++ }, quote = { quote(600_000L) })
        val first = loop.tick()
        val second = loop.tick()
        assertEquals(2, calls)
        assertTrue(first!!.contains("YES"))
        assertTrue(second!!.contains("YES"))
        assertEquals("KXBTC15M-W", loop.lastWindow)

        calls = 0
        val late = loop(calls = { calls++ }, quote = { quote(30_000L) })
        assertEquals(LlmPaperStrategy.PAST_OPEN_STATUS, late.tick())
        assertEquals(0, calls)
        assertTrue(LlmPaperStrategy.PAST_OPEN_STATUS.contains("60 seconds"))
    }

    @Test
    fun autoOffAndPaidProvidersMakeNoCall() {
        var calls = 0
        val paused = loop(auto = { false }, calls = { calls++ }, quote = { quote(600_000L) })
        assertEquals(AiAutoCalls.PAUSED, paused.tick())
        val paid = loop(
            calls = { calls++ },
            quote = { quote(600_000L) },
            freeProvider = { false }
        )
        assertEquals(LlmPaperStrategy.FREE_ONLY_STATUS, paid.tick())
        assertEquals(0, calls)
    }

    @Test
    fun paceMissDoesNotConsumeTheWindow() {
        var allow = false
        var calls = 0
        val loop = loop(
            calls = { calls++ },
            quote = { quote(500_000L) },
            hit = { if (allow) reply() else null }
        )
        assertEquals("waiting for the free-tier pace", loop.tick())
        assertEquals(0, calls)
        allow = true
        assertTrue(loop.tick()!!.contains("YES"))
        assertEquals(1, calls)
    }

    private fun reply() = LlmProviders.resolve(LlmProviders.GEMINI, "k", "", "") to
        LlmClient.Reply("YES 0.70", LlmProviders.GEMINI_DEFAULT, LlmProviders.GEMINI_DEFAULT, 8, 4)

    private fun loop(
        auto: () -> Boolean = { true },
        calls: () -> Unit,
        quote: () -> ScalpRule.Quote,
        freeProvider: () -> Boolean = { true },
        hit: () -> Pair<LlmEndpoint, LlmClient.Reply>? = { reply() }
    ) = LlmPaperLoop(
        autoCalls = auto,
        hasKey = { true },
        quote = quote,
        call = {
            val row = hit()
            if (row != null) calls()
            row
        },
        place = { _, _, _ -> "paper" },
        onUsage = { _, _ -> },
        onCall = { _, _, _ -> },
        freeProvider = freeProvider
    )

    private fun quote(leftMs: Long) = ScalpRule.Quote(
        "KXBTC15M-W", now, now + leftMs, now,
        0.40, 10.0, 0.42, 10.0, 80_000.0, 80_000.0, 0.0001
    )
}
