package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.llm.FreeCallPacer
import com.dirk.kalshiodds.llm.LlmClient
import com.dirk.kalshiodds.llm.LlmEndpoint
import com.dirk.kalshiodds.llm.LlmProviders
import com.dirk.kalshiodds.llm.ModelResearchAsk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelResearchTest {
    @Before fun reset() = ModelFilter.resetForTest()
    @After fun clear() = ModelFilter.resetForTest()

    @Test fun driversShowWhatRaisedTheScore() {
        val x = DoubleArray(ModelFilter.FEATURES)
        x[0] = 1.0
        val w = DoubleArray(ModelFilter.FEATURES)
        w[0] = 2.0
        val row = log("a", score = 0.88, features = x, outcome = null)
        val explain = ResearchCompanion.explain(row, w, 0.0, "Logistic")
        assertEquals("timeLeft", explain.drivers.first().name)
        assertTrue(explain.drivers.first().push > 0.2)
        assertTrue(explain.prompt.contains("timeLeft"))
        assertTrue(explain.prompt.contains("No orders"))
        assertEquals(5, explain.drivers.size)
    }

    @Test fun closeWritesTheOutcomeOntoTheLog() {
        ModelFilter.onClosed("sf41-logistic", 0.20, 600.0, 1L, DoubleArray(ModelFilter.FEATURES))
        ModelFilter.onClosed("sf41-logistic", -0.05, 600.0, 2L, DoubleArray(ModelFilter.FEATURES))
        val d = ModelFilter.decide(ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LOGISTIC }, quote(), "YES", 0.20, 0.55)
        assertTrue(d.reason, d.enter)
        ModelFilter.onClosed(d.twinId, 0.42, 600.0, d.atMs + 1_000L, d.features)
        val row = ModelFilter.researchLog().last { it.entered }
        assertEquals(0.42, row.outcomeUsd!!, 1e-9)
        assertEquals(ModelFilter.POLICY_VERSION, row.policyVersion)
        val page = ResearchCompanion.journal()
        assertEquals(1, page.closed)
    }

    @Test fun journalSuggestsAHigherThresholdWhenHighScoresLose() {
        val rows = List(5) { i ->
            log("h$i", score = 0.80, features = DoubleArray(ModelFilter.FEATURES), outcome = -0.30)
        }
        val page = ResearchCompanion.journalOf(rows, 0.55)
        assertTrue(page.suggestions.joinToString(), page.suggestions.any { it.contains("Raise the threshold") && it.contains("0.57") })
    }

    @Test fun journalSuggestsShrinkingTheFeatureLosersCarry() {
        val winners = List(3) { i ->
            val x = DoubleArray(ModelFilter.FEATURES)
            log("w$i", score = 0.50, features = x, outcome = 0.20)
        }
        val losers = List(3) { i ->
            val x = DoubleArray(ModelFilter.FEATURES)
            x[0] = 1.0
            log("l$i", score = 0.50, features = x, outcome = -0.20)
        }
        val page = ResearchCompanion.journalOf(winners + losers, 0.55)
        assertTrue(page.suggestions.joinToString(), page.suggestions.any { it.contains("timeLeft") && it.contains("Shrink") })
    }

    @Test fun retrainStaysOffline() {
        val note = ResearchCompanion.retrain(1_700_000_000_000L)
        assertTrue(note, note.contains("Need 40"))
    }

    @Test fun researchAskUsesLocalAndNeverAPaidProvider() {
        val seen = ArrayList<String>()
        val local = LlmEndpoint("custom", "http://127.0.0.1:11434/v1", "on-device", "")
        val paid = LlmEndpoint(LlmProviders.XAI, "https://api.x.ai/v1", "grok-4.7", "paid-key")
        val free = LlmEndpoint(LlmProviders.MISTRAL, "https://api.mistral.ai/v1", LlmProviders.MISTRAL_DEFAULT, "free-key")
        val text = ModelResearchAsk.ask(
            "why this score",
            local,
            listOf(paid, free),
            0L,
            FreeCallPacer(),
            { LlmProviders.baseQuota(it.provider, it.model) },
            { true },
            { ep, _ ->
                seen += ep.provider
                if (!ModelResearchAsk.isLoopback(ep.baseUrl)) error("left the device")
                LlmClient.Reply("local note", ep.model, ep.model)
            },
            { _, _ -> error("billing") }
        )
        assertEquals("local note", text)
        assertEquals(listOf("custom"), seen)
    }

    @Test fun researchAskFallsThroughToFreeWhenLocalFails() {
        val seen = ArrayList<String>()
        val local = LlmEndpoint("custom", "http://localhost:8080/v1", "on-device", "")
        val paid = LlmEndpoint(LlmProviders.OPENAI, "https://api.openai.com/v1", "gpt-4.1", "paid-key")
        val free = LlmEndpoint(LlmProviders.MISTRAL, "https://api.mistral.ai/v1", LlmProviders.MISTRAL_DEFAULT, "free-key")
        val text = ModelResearchAsk.ask(
            "journal",
            local,
            listOf(paid, free),
            10_000L,
            FreeCallPacer(),
            { LlmProviders.baseQuota(it.provider, it.model) },
            { true },
            { ep, _ ->
                seen += ep.provider
                if (ModelResearchAsk.isLoopback(ep.baseUrl)) error("local down")
                LlmClient.Reply("free note", ep.model, ep.model)
            },
            { _, _ -> }
        )
        assertEquals("free note", text)
        assertEquals(listOf("custom", LlmProviders.MISTRAL), seen)
        assertFalse(seen.contains(LlmProviders.OPENAI))
    }

    @Test fun researchAskWithOnlyAPaidKeyDoesNotCall() {
        val seen = ArrayList<String>()
        val paid = LlmEndpoint(LlmProviders.XAI, "https://api.x.ai/v1", "grok-4.7", "paid-key")
        val text = ModelResearchAsk.ask("x", null, listOf(paid), 0L, FreeCallPacer(), { null }, { true }, { ep, _ ->
            seen += ep.provider
            LlmClient.Reply("nope", ep.model, ep.model)
        }, { _, _ -> })
        assertTrue(seen.isEmpty())
        assertTrue(text.contains("feature drivers"))
    }

    @Test fun liveScoreStaysUnderTwoMillisecondsAndOffTheResearchPath() {
        val w = DoubleArray(ModelFilter.FEATURES)
        val x = DoubleArray(ModelFilter.FEATURES) { 0.01 }
        val start = System.nanoTime()
        repeat(1_000) { QuantSession.score(w, 0.0, x) }
        val avgUs = (System.nanoTime() - start) / 1_000.0 / 1_000.0
        assertTrue("avg ${avgUs}µs", avgUs < 2_000.0)
        val scalp = source("Scalp.kt")
        val filter = source("ModelFilter.kt")
        val session = source("QuantSession.kt")
        val research = source("ResearchCompanion.kt")
        assertFalse(scalp.contains("ResearchCompanion"))
        assertFalse(scalp.contains("ModelResearchAsk"))
        assertFalse(scalp.contains("LlmClient"))
        assertFalse(filter.contains("ModelResearchAsk"))
        assertFalse(filter.contains("LlmClient"))
        assertFalse(research.contains("kalshiodds.llm"))
        val scoreFn = session.substring(session.indexOf("fun score("), session.indexOf("fun setMode"))
        assertFalse(scoreFn.contains("attribute"))
        assertFalse(scoreFn.contains("Research"))
    }

    @Test fun manualResearchAskBypassesAutoPaceAndHandlesBackToBackTaps() {
        val pacer = FreeCallPacer()
        val now = 1_700_000_000_000L
        // Simulate a background window-watcher call 5s ago so the 120s auto-pace timer is active.
        pacer.record(LlmProviders.GROQ, LlmProviders.DEFAULT_GROQ_MODEL, now - 5_000L, 180)
        val groq = LlmEndpoint(LlmProviders.GROQ, "https://api.groq.com/openai/v1", LlmProviders.GROQ_120, "gsk_test")
        val calls = ArrayList<String>()
        val first = ModelResearchAsk.ask(
            prompt = "Explain why sf41-logistic skipped this tick.",
            local = LlmProviders.builtinEndpoint(),
            slots = listOf(groq),
            nowMs = now,
            pacer = pacer,
            quotaOf = { LlmProviders.baseQuota(it.provider, it.model) },
            allowed = { true },
            ask = { ep, _ ->
                calls += "${ep.provider}:${ep.model}"
                // Simulate gpt-oss-120b failing so candidateModels falls back to llama-3.1-8b-instant
                if (ep.model == LlmProviders.GROQ_120) error("model unavailable")
                LlmClient.Reply("Groq explained the trade.", ep.model, ep.model)
            },
            onBilling = { _, _ -> }
        )
        assertEquals("Groq explained the trade.", first)
        assertEquals(listOf("groq:${LlmProviders.GROQ_120}", "groq:${LlmProviders.DEFAULT_GROQ_MODEL}"), calls)

        // Second manual tap 1 second later (Session Summary button) must also succeed without waiting 120s.
        val second = ModelResearchAsk.ask(
            prompt = "Paper session journal. Closed 5.",
            local = LlmProviders.builtinEndpoint(),
            slots = listOf(groq.copy(model = LlmProviders.DEFAULT_GROQ_MODEL)),
            nowMs = now + 1_000L,
            pacer = pacer,
            quotaOf = { LlmProviders.baseQuota(it.provider, it.model) },
            allowed = { true },
            ask = { ep, _ ->
                calls += "${ep.provider}:${ep.model}"
                LlmClient.Reply("Groq summarized the session.", ep.model, ep.model)
            },
            onBilling = { _, _ -> }
        )
        assertEquals("Groq summarized the session.", second)
        assertEquals(3, calls.size)
    }

    @Test fun researchAskFallsBackToBuiltinQuantExplanationWhenCloudFailsOrMissing() {
        val x = DoubleArray(ModelFilter.FEATURES)
        x[0] = 0.30
        x[10] = 0.67
        x[16] = -1.80
        val row = ModelFilter.LogRow(
            id = "r1",
            twinId = "sf41-logistic",
            atMs = 1L,
            score = 0.50,
            scalar = 1.0,
            entered = false,
            reason = "expected net -0.02 ≤ 0",
            features = x,
            outcomeUsd = null,
            side = "YES",
            expectedNet = -0.018
        )
        val explain = ResearchCompanion.explain(row)
        val groq = LlmEndpoint(LlmProviders.GROQ, "https://api.groq.com/openai/v1", LlmProviders.DEFAULT_GROQ_MODEL, "gsk_test")
        val tradeReply = ModelResearchAsk.ask(
            prompt = explain.prompt,
            local = LlmProviders.builtinEndpoint(),
            slots = listOf(groq),
            nowMs = 10_000L,
            pacer = FreeCallPacer(),
            quotaOf = { LlmProviders.baseQuota(it.provider, it.model) },
            allowed = { true },
            ask = { _, _ -> throw com.dirk.kalshiodds.llm.LlmRateLimited(60_000L, "rate limited") },
            onBilling = { _, _ -> }
        )
        assertFalse(tradeReply.contains("Free providers did not answer"))
        assertTrue(tradeReply.contains("Built-in Quant Analyst"))
        assertTrue(tradeReply.contains("feature drivers"))
        assertEquals(2, tradeReply.split("\n\n").size)

        val journalPrompt = "Paper session journal. Closed 8.\nTime left 5–10 min: 6/8 net +1.20\nTwo short paragraphs: what regime is working, and whether to retrain. No orders."
        val journalReply = ModelResearchAsk.ask(
            prompt = journalPrompt,
            local = LlmProviders.builtinEndpoint(),
            slots = emptyList(),
            nowMs = 20_000L,
            pacer = FreeCallPacer(),
            quotaOf = { null },
            allowed = { true },
            ask = { _, _ -> error("no cloud") },
            onBilling = { _, _ -> }
        )
        assertFalse(journalReply.contains("Free providers did not answer"))
        assertTrue(journalReply.contains("Built-in Quant Analyst"))
        assertTrue(journalReply.contains("feature drivers"))
        assertEquals(2, journalReply.split("\n\n").size)
    }

    @Test fun syncClosedScalpsPopulatesSessionJournalAndRetrainFromStore() {
        val base = 1_700_000_000_000L
        val trades = List(42) { i ->
            ScalpTrade(
                id = "closed-$i",
                ticker = "KXBTC15M-26OCT221200-00",
                side = if (i % 2 == 0) "YES" else "NO",
                state = ScalpState.CLOSED,
                signalAtMs = base + i * 5_000L,
                signalAsk = 0.38,
                fairAtSignal = 0.52,
                contracts = 10,
                entryPrice = 0.38,
                entryAtMs = base + i * 5_000L + 1_000L,
                closedAtMs = base + i * 5_000L + 60_000L,
                netUsd = if (i % 3 == 0) -0.25 else 0.45,
                exitReason = "take profit",
                ruleVersion = "${ScalpRule.VERSION}|sf41|P"
            )
        }
        val added = ModelFilter.syncClosedScalps(trades)
        assertEquals(42, added)
        assertEquals(0, ModelFilter.syncClosedScalps(trades))
        val journal = ResearchCompanion.journal()
        assertEquals(42, journal.closed)
        assertTrue(journal.regimes.any { it.group == "Time left" })
        assertTrue(journal.regimes.any { it.group == "Vol" })
        assertTrue(journal.regimes.any { it.group == "Score" })
        val retrain = ResearchCompanion.retrain(base + 42 * 5_000L + ModelFilter.EMBARGO_MS + 120_000L)
        assertTrue(retrain, retrain.contains("Retrained offline on 42 samples"))
    }

    private fun log(id: String, score: Double, features: DoubleArray, outcome: Double?) = ModelFilter.LogRow(
        id, "sf41-model", 1L, score, score * 1.5, true, "enter", features, outcome, "YES"
    )

    private fun quote() = ScalpRule.Quote(
        ticker = "KXBTC15M-26OCT221200-00",
        nowMs = 1_700_000_000_000L,
        closeMs = 1_700_000_600_000L,
        bookAtMs = 1_700_000_000_000L,
        yesBid = 0.19,
        yesBidSize = 20.0,
        yesAsk = 0.20,
        yesAskSize = 20.0,
        spot = 100_000.0,
        strike = 100_000.0,
        sigmaPerSec = 3e-5
    )

    private fun source(name: String): String {
        val file = listOf(
            File("src/main/java/com/dirk/kalshiodds/decision/$name"),
            File("app/src/main/java/com/dirk/kalshiodds/decision/$name")
        ).first { it.isFile }
        return file.readText()
    }
}
