package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.insights.BenchRegistry
import com.dirk.kalshiodds.decision.insights.BenchStatus
import com.dirk.kalshiodds.decision.insights.ScalpBench
import com.dirk.kalshiodds.decision.insights.ScalpInsights
import com.dirk.kalshiodds.llm.AiAutoCalls
import com.dirk.kalshiodds.llm.AiLiveFeed
import com.dirk.kalshiodds.llm.AiLivePosition
import com.dirk.kalshiodds.llm.AiLiveSnapshot
import com.dirk.kalshiodds.llm.FreeCallPacer
import com.dirk.kalshiodds.llm.LiveTrigger
import com.dirk.kalshiodds.llm.LlmProviders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** 1.0.28: no auto-freeze, full variant rows, floors, and the aggressive paper tuner. */
class ScalpSanity1028Test {
    @After fun resetRegistry() { BenchRegistry.publish(emptyMap()) }

    @Test fun duplicateZeroClipsCollapseToOneFlatLineAndVariantsKeepTheirSuffix() {
        val parent = "v2-dip-g05-t08-s06-d10-w180"
        val rows = AiLiveFeed.positionRows(listOf(
            AiLivePosition(parent, "NO", 0, 0.0),
            AiLivePosition(parent, "NO", 0, 0.0),
            AiLivePosition("$parent-v1", "NO", 0, 0.0),
            AiLivePosition("$parent-v2", "YES", 4, 1.25),
            AiLivePosition("$parent-v2", "YES", 1, -0.25)
        ))
        assertEquals(listOf("$parent flat", "$parent-v1 flat", "$parent-v2 YES 5 · +1.00"), rows.map { it.text })
        assertTrue(rows[0].flat)
        assertFalse(rows[2].flat)
        assertTrue(rows.none { it.id.length < parent.length })
    }

    @Test fun livePostNamesTunerChangesInsteadOfNoSettingChanges() {
        val code = KeptScalpers.V2_DIP_ID
        val controls = ScalpControls()
        val recent = List(12) { 0.4 }
        val tape = PaperTape(code, 12, 12, recent.sum(), 1.0, recent, emptyList())
        val changes = PaperSettingTuner.propose(controls, listOf(tape))
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.none { it.setting.contains("bench") })
        assertTrue(changes.all { it.newValue.toInt() >= ScalpFloors.cents(it.scalper) || it.setting != "minEntryCents" })
        val tweaks = changes.map {
            com.dirk.kalshiodds.llm.AiAdjustment(1L, it.scalper, it.setting, it.oldValue, it.newValue, it.reason)
        }
        val post = AiLiveFeed.post(live(), LiveTrigger.FILL, tweaks)
        assertFalse(post.contains("no setting changes"))
        assertTrue(post.contains(code))
        assertTrue(post.contains("→"))
        val empty = AiLiveFeed.post(live(), LiveTrigger.FILL, emptyList())
        assertTrue(empty.contains("no setting changes"))
    }

    @Test fun aLoserIsTightenedAndNotBenched() {
        val code = "sf17f-v1"
        val before = ScalpControls().settings(code)
        val tape = PaperTape(code, 8, 1, -6.0, 0.125, List(8) { -0.75 }, emptyList())
        val changes = PaperSettingTuner.propose(ScalpControls(), listOf(tape))
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.none { it.setting == "bench" || it.setting == "enabled" })
        val floor = ScalpFloors.cents(code)
        changes.filter { it.setting == "minEntryCents" }.forEach { assertTrue(it.newValue.toInt() >= PaperFleetDefaults.BUY_MIN) }
        val (lo, hi) = PaperFleetDefaults.band(code)
        assertEquals(lo, before.minEntryCents)
        assertEquals(hi, before.maxEntryCents)
        assertTrue(before.takeProfitCents!! in 1..PaperFleetDefaults.PROFIT_CENTS)
        assertTrue((before.minEntryCents + before.maxEntryCents!!) / 2 + before.takeProfitCents!! <= 99)
        assertTrue(floor >= 50)
        val book = ScalpBook()
        val insights = ScalpInsights(book, MemoryScalpControls(), null) {}
        insights.start()
        repeat(40) { i ->
            insights.onSettled(code, ScalpTrade("$code-$i", "KXBTC15M-26OCT181200-00", "NO", ScalpState.CLOSED,
                i * 1000L, 0.80, 0.90, contracts = 1, entryPrice = 0.80, closedAtMs = i * 1000L + 10, netUsd = -1.0,
                ruleVersion = "v|$code|P"))
        }
        assertFalse(code in book.benched)
        assertEquals(BenchStatus.ACTIVE, BenchRegistry.decisions.value.getValue(code).status)
    }

    @Test fun manualBlockSkipsTheTunerAndTheFloorStopsTheLearner() {
        val code = KeptScalpers.V2_FAIR_ID
        val blocked = PaperSettingTuner.propose(
            ScalpControls(),
            listOf(PaperTape(code, 4, 4, 2.0, 1.0, listOf(0.5, 0.5, 0.5, 0.5), emptyList()))
        ) { true }
        assertTrue(blocked.isEmpty())
        var s = ScalpStrategySettings(minEntryCents = 1, maxEntryCents = 40, aiLearns = true)
        repeat(5) { s = Research.learn(code, s, -3.0, 55) }
        assertTrue(s.minEntryCents >= ScalpFloors.cents(code))
        assertTrue(s.minEntryCents >= 10)
    }

    @Test fun noDefaultBuysAtOneToNineCentsAndFavouritesSitOnTheFairThreshold() {
        assertTrue(ScalpFloors.cents("sf17f-v1") >= 70)
        assertTrue(ScalpFloors.ceilingCents("sf17f-v1") >= 90)
        assertTrue(ScalpFloors.cents("sf16f") >= 70)
        assertTrue(ScalpFloors.cents("sf41") >= 10)
        assertTrue(ScalpFloors.cents(KeptScalpers.FAIR_ID) >= 10)
        assertTrue(ScalpFloors.cents(KeptScalpers.V2_DIP_ID) >= 10)
        val sf17 = ScalpControls().settings("sf17f-v1")
        val (lo, hi) = PaperFleetDefaults.band("sf17f-v1")
        assertEquals(lo, sf17.minEntryCents)
        assertEquals(hi, sf17.maxEntryCents)
        assertTrue(sf17.takeProfitCents!! in 1..PaperFleetDefaults.PROFIT_CENTS)
        assertTrue((sf17.minEntryCents + sf17.maxEntryCents!!) / 2 + sf17.takeProfitCents!! <= 99)
        assertFalse(ShockFav.shockEntryOk(ShockFav.v("sf04"), 0.80, 0.05))
        assertTrue(ShockFav.shockEntryOk(ShockFav.v("sf04"), 0.80, 0.20))
    }

    @Test fun everyRegisteredDefaultPassesSanity() {
        val bad = ScalpParamSanity.ids().map { it to ScalpParamSanity.violations(it) }.filter { it.second.isNotEmpty() }
        assertEquals(bad.joinToString("\n") { "${it.first}: ${it.second}" }, 0, bad.size)
        val rows = ScalpParamSanity.ids().map { ScalpParamSanity.audit(it) }
        assertTrue(rows.size >= ScalpModels.ALL.size)
        rows.forEach { row ->
            assertTrue(row.id, row.floor < row.ceiling)
            assertTrue(row.id, row.clip >= 1)
            assertTrue(row.id, row.stopCents > 0)
        }
    }

    @Test fun entryAndExitSanityRejectsPenniesAndAFlippedBook() {
        val floor = ScalpFloors.cents("sf42") / 100.0
        assertNotNull(ScalpParamSanity.entryBlock("NO", 0.03, 4, floor, 20_000.0))
        assertNull(ScalpParamSanity.entryBlock("DOWN", 0.40, 4, floor, 20_000.0))
        assertNotNull(ScalpParamSanity.entryBlock("MAYBE", 0.40, 4, floor, 20_000.0))
        assertNotNull(ScalpParamSanity.exitBlock(1.20, 0.40, 4, 10.0))
        assertNotNull(ScalpParamSanity.exitBlock(0.50, 0.40, 4, 10.0))
        assertNotNull(ScalpParamSanity.exitBlock(0.40, 0.40, 20, 4.0))
        assertNull(ScalpParamSanity.exitBlock(0.40, 0.41, 4, 10.0))
        val gap = ScalpParamSanity.pnlGap(0.40, 0.50, 10, 0.18, 0.18, 1.0 - 0.36)!!
        assertEquals(0.0, gap, 1e-9)
        assertFalse(ScalpParamSanity.pricesComplement(0.72, 0.72))
        assertTrue(ScalpParamSanity.pricesComplement(0.72, 0.28))
        assertEquals("YES", ScalpParamSanity.side("UP"))
        assertEquals("NO", ScalpParamSanity.side("DOWN"))
    }

    @Test fun paceMissNamesTheCapAndTheEtResumeTime() {
        val pacer = FreeCallPacer()
        val quota = LlmProviders.effectiveQuota(LlmProviders.GROQ, LlmProviders.GROQ_120, 1_000)!!
        val now = Instant.parse("2026-10-22T16:00:00Z").toEpochMilli()
        repeat(quota.requestsPerMinute) { i ->
            pacer.record(LlmProviders.GROQ, LlmProviders.GROQ_120, now - 1_000L, 100)
        }
        val hold = pacer.hold(LlmProviders.GROQ, listOf(LlmProviders.GROQ_120), quota, now, 100)
        assertNotNull(hold)
        assertTrue(hold!!.reason.contains("minute") || hold.reason.contains("pace") || hold.reason.contains("token"))
        val mistral = LlmProviders.resolve(LlmProviders.MISTRAL, "k", LlmProviders.MISTRAL_DEFAULT, "")
        val mistralQuota = LlmProviders.effectiveQuota(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 2_880)!!
        val mistralPacer = FreeCallPacer()
        repeat(mistralQuota.requestsPerMinute) {
            mistralPacer.record(LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, now - 1_000L, 100)
        }
        val wait = AiAutoCalls.explainWait(listOf(mistral), mistralPacer, now, 100, { mistralQuota }, { true })
        assertNotNull(wait)
        val text = AiAutoCalls.paceStatus(wait, now)
        assertTrue(text.contains("waiting for the free-tier pace"))
        assertTrue(text.contains("resumes"))
        assertTrue(text.contains("ET"))
        assertTrue(text.contains("PM") || text.contains("AM"))
    }

    @Test fun anErrorFallsThroughToTheNextFreeProviderAndNeverAPaidOne() {
        val seen = ArrayList<String>()
        val mistral = LlmProviders.resolve(LlmProviders.MISTRAL, "k", "", "")
        val groq = LlmProviders.resolve(LlmProviders.GROQ, "k", "", "")
        val paid = LlmProviders.resolve(LlmProviders.OPENAI, "k", "", "")
        val hit = AiAutoCalls.callFree(
            selected = mistral,
            slots = listOf(groq, paid),
            pacer = FreeCallPacer(),
            nowMs = 0L,
            messages = listOf("user" to "hi"),
            reserveCompletion = 8,
            quotaOf = { LlmProviders.baseQuota(it.provider, it.model) },
            allowed = { LlmProviders.freeFamily(it.provider, it.model) },
            ask = { ep, _ ->
                seen += ep.provider
                if (ep.provider == LlmProviders.MISTRAL) error("mistral down")
                com.dirk.kalshiodds.llm.LlmClient.Reply("YES 0.6", ep.model, ep.model, 1, 1)
            },
            onBilling = { _, _ -> }
        )
        assertEquals(LlmProviders.GROQ, hit!!.first.provider)
        assertEquals(listOf(LlmProviders.MISTRAL, LlmProviders.GROQ), seen)
    }

    private fun live() = AiLiveSnapshot(
        atMs = 1L, windowKey = "W", ticker = "KXBTC15M-W", closeMs = 2L,
        strike = 80_000.0, btc = 80_010.0, yesBid = 0.40, yesAsk = 0.42,
        noBid = 0.58, noAsk = 0.60, modelUp = 0.55
    )
}
