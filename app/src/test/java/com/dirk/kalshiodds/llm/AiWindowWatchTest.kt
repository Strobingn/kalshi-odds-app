package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.MemoryScalpControls
import com.dirk.kalshiodds.decision.PaperTape
import com.dirk.kalshiodds.decision.Research
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpStrategySettings
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
import java.time.LocalDateTime
import java.time.ZoneId

/** 1.0.12: window follower clamps, yields to a recent manual edit, and never touches real money. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiWindowWatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val code = ScalpModels.ALL.first { ScalpModels.isV2(it) && it != ScalpModels.Model.V2_LLM }.code
    private val research = KeptScalpers.FAIR_ID
    private var nowMs = 1_700_000_000_000L
    private var snap = WindowSnapshot("W1", 1_700_000_900_000L, "live window", "fp-1")
    private var provider = LlmProviders.MISTRAL

    @Test fun clampsToTheAiLearnsBandAndDoesNotRestartTheLearner() {
        val c = MemoryScalpControls(ScalpControls(strategySettings = mapOf(
            code to ScalpStrategySettings(maxEntryCents = 48, stopCents = 6, aiBaseCents = 50, aiN = 2, aiNet = 1.0, aiNote = "learning"),
            research to ScalpStrategySettings(maxEntryCents = 40, aiBaseCents = 40, aiN = 1, aiNote = "learning")
        )))
        val w = watcher(c) { _, _ ->
            reply("""{"adjustments":[
                {"scalper":"$code","setting":"maxEntryCents","value":30,"reason":"too wide"},
                {"scalper":"$code","setting":"maxEntryCents","value":90,"reason":"ignored second"},
                {"scalper":"$code","setting":"stopCents","value":99,"reason":"wider stop"}
            ]}""")
        }
        // Direct band, same numbers either way around — no side input exists.
        assertEquals("34", AiWatchBounds.clamp(c.state.value, code, "maxEntryCents", "1", 50))
        assertEquals("50", AiWatchBounds.clamp(c.state.value, code, "maxEntryCents", "90", 50))
        assertEquals("24", AiWatchBounds.clamp(c.state.value, research, "maxEntryCents", "20", 40))
        assertEquals("40", AiWatchBounds.clamp(c.state.value, research, "maxEntryCents", "90", 40))
        assertEquals("14", AiWatchBounds.clamp(c.state.value, code, "stopCents", "99", 6))
        assertEquals(
            AiWatchBounds.clamp(c.state.value, code, "maxEntryCents", "30", 50),
            AiWatchBounds.clamp(c.state.value, code, "maxEntryCents", "30", 50)
        )
        w.tick()
        val v2 = c.state.value.settings(code)
        val edge = c.state.value.settings(research)
        assertEquals(50, v2.maxEntryCents) // 30 clamps into the band, then 90 clamps to the user baseline 50
        assertEquals(50, v2.aiBaseCents)
        assertEquals(2, v2.aiN)
        assertEquals("learning", v2.aiNote)
        assertEquals(40, edge.maxEntryCents)
        assertEquals(40, edge.aiBaseCents)
        assertEquals(14, v2.stopCents) // other knobs stay inside −16..+8
        assertFalse(v2.aiNote.contains("set by you"))
    }

    @Test fun manualEditWinsForTheRestOfTheWindow() {
        val c = MemoryScalpControls(ScalpControls(strategySettings = mapOf(
            code to ScalpStrategySettings(maxEntryCents = 50, stopCents = 6)
        )))
        val store = AiWatchStore()
        val w = watcher(c, store) { _, _ ->
            reply("""{"adjustments":[
                {"scalper":"$code","setting":"maxEntryCents","value":40,"reason":"ai"},
                {"scalper":"$code","setting":"stopCents","value":9,"reason":"tighter stop"}
            ]}""")
        }
        store.noteManual("strategy.$code.maxEntryCents", nowMs + 60_000)
        w.tick()
        assertEquals(50, c.state.value.settings(code).maxEntryCents)
        assertEquals(9, c.state.value.settings(code).stopCents)
        nowMs += 120_000
        snap = snap.copy(windowKey = "W2", fingerprint = "fp-2")
        w.tick()
        assertEquals(40, c.state.value.settings(code).maxEntryCents)
    }

    @Test fun realMoneyIsUntouchedAndNoOrderIsPlaced() {
        val c = MemoryScalpControls(ScalpControls(
            killSwitch = false,
            research = Research.Params(kellyBankrollUsd = 1000.0),
            strategySettings = mapOf(code to ScalpStrategySettings(maxEntryCents = 50))
        ))
        val store = AiWatchStore()
        val w = watcher(c, store) { _, _ ->
            reply("""{"orders":[{"ticker":"KXBTC15M","side":"YES"}],"adjustments":[
                {"scalper":"$code","setting":"liveTradingArmed","value":true,"reason":"arm"},
                {"scalper":"$code","setting":"placeOrder","value":1,"reason":"buy yes"},
                {"scalper":"$code","setting":"killSwitch","value":true,"reason":"stop"},
                {"scalper":"$code","setting":"apiKey","value":"secret","reason":"key"},
                {"scalper":"$code","setting":"maxEntryCents","value":45,"reason":"paper only"}
            ]}""")
        }
        w.tick()
        assertFalse(c.state.value.killSwitch)
        assertEquals(1000.0, c.state.value.research.kellyBankrollUsd, 0.0)
        assertEquals(45, c.state.value.settings(code).maxEntryCents)
        assertTrue(store.adjustments.value.none { it.setting.contains("live") || it.setting.contains("Order") || it.setting.contains("apiKey") || it.setting == "killSwitch" })
        assertEquals(listOf("maxEntryCents"), store.adjustments.value.map { it.setting })
        val src = source("AiWindowWatch.kt")
        assertFalse(src.contains("KalshiTradeClient"))
        assertFalse(src.contains("liveTradingArmed"))
    }

    @Test fun toggleOffMakesNoCall() {
        val store = AiWatchStore()
        store.setFollows(false)
        var calls = 0
        var snaps = 0
        val w = AiWindowWatcher(
            controls = MemoryScalpControls(),
            store = store,
            snapshot = { snaps++; snap },
            ask = { _, _ -> calls++; error("must not be called") },
            endpoint = { LlmProviders.resolve(provider, "k", "", "") },
            now = { nowMs }
        )
        w.tick()
        assertEquals(0, calls)
        assertEquals(0, snaps)
        assertTrue(AiWatchStore().config.value.followsWindow)
    }

    @Test fun skipsAQuietMinuteThenCallsWhenTheWindowOrTheMarketMoves() {
        var calls = 0
        val w = watcher(MemoryScalpControls()) { _, _ -> calls++; reply("""{"adjustments":[]}""") }
        w.tick()
        nowMs += 60_000
        w.tick()
        nowMs += 60_000
        snap = snap.copy(fingerprint = "fp-moved")
        w.tick()
        nowMs += 5_000
        snap = snap.copy(windowKey = "W2", fingerprint = "fp-next")
        w.tick()
        assertEquals(2, calls) // 120s floor blocks the 5s poll and a window roll
        nowMs += 120_000
        w.tick()
        assertEquals(3, calls)
    }

    @Test fun atMostThreeChangesPerScalperPerWindow() {
        val c = MemoryScalpControls(ScalpControls(strategySettings = mapOf(
            code to ScalpStrategySettings(maxEntryCents = 50, stopCents = 6, cadenceSeconds = 0)
        )))
        val w = watcher(c) { _, _ ->
            reply("""{"adjustments":[
                {"scalper":"$code","setting":"maxEntryCents","value":45,"reason":"a"},
                {"scalper":"$code","setting":"stopCents","value":9,"reason":"b"},
                {"scalper":"$code","setting":"cadenceSeconds","value":3,"reason":"c"},
                {"scalper":"$code","setting":"contracts","value":14,"reason":"d"}
            ]}""")
        }
        w.tick()
        val s = c.state.value.settings(code)
        assertEquals(45, s.maxEntryCents)
        assertEquals(9, s.stopCents)
        assertEquals(3, s.cadenceSeconds)
        assertNull(s.contracts)
    }

    @Test fun providerSwitchIsUsedOnTheNextCall() {
        val seen = mutableListOf<LlmEndpoint>()
        val w = watcher(MemoryScalpControls()) { ep, _ -> seen += ep; reply("""{"adjustments":[]}""") }
        w.tick()
        provider = LlmProviders.GROQ
        nowMs += 120_000
        snap = snap.copy(windowKey = "W2", fingerprint = "fp-2")
        w.tick()
        assertEquals(listOf(LlmProviders.MISTRAL, LlmProviders.GROQ), seen.map { it.provider })
        provider = LlmProviders.OPENAI
        nowMs += 60_000
        snap = snap.copy(windowKey = "W3", fingerprint = "fp-3")
        w.tick()
        assertEquals(listOf(LlmProviders.MISTRAL, LlmProviders.GROQ), seen.map { it.provider })
    }

    @Test fun mistralUsesTheFreePaceInsteadOfThePaidCap() {
        provider = LlmProviders.MISTRAL
        var calls = 0
        val w = watcher(MemoryScalpControls()) { _, _ -> calls++; reply("""{"adjustments":[]}""") }
        w.tick()
        assertEquals(1, calls)
        nowMs += 100
        snap = snap.copy(fingerprint = "inside-pace")
        w.tick()
        assertEquals(1, calls)
        nowMs += 5_000
        snap = snap.copy(fingerprint = "pace-open")
        w.tick()
        assertEquals(1, calls)
        nowMs += 120_000
        snap = snap.copy(fingerprint = "pace-120s")
        w.tick()
        assertEquals(2, calls)
    }

    @Test fun paidProvidersAreNotCalledAutomatically() {
        val seen = mutableListOf<String>()
        val w = watcher(MemoryScalpControls()) { ep, _ -> seen += ep.provider; reply("""{"adjustments":[]}""") }
        w.tick()
        assertEquals(listOf(LlmProviders.MISTRAL), seen)
        for (id in listOf(LlmProviders.OPENAI, LlmProviders.OPENROUTER, LlmProviders.CUSTOM, LlmProviders.XAI)) {
            provider = id
            nowMs += 20_000
            snap = snap.copy(windowKey = "W-$id", fingerprint = "fp-$id")
            w.tick()
        }
        assertEquals(listOf(LlmProviders.MISTRAL), seen)
        provider = LlmProviders.OPENAI
        nowMs += 120_000
        snap = snap.copy(windowKey = "W-paid", fingerprint = "fp-paid")
        w.tick()
        assertEquals(listOf(LlmProviders.MISTRAL, LlmProviders.OPENAI), seen)
    }

    @Test fun onDeviceFreezeRunsWhileTheChatLlmWaits() {
        val codes = ScalpModels.ALL.filter { ScalpModels.isV2(it) && it != ScalpModels.Model.V2_LLM }.take(2).map { it.code }
        val first = codes[0]
        val second = codes[1]
        val controls = MemoryScalpControls()
        var paperCode = first
        var calls = 0
        val losing = List(30) { -1.0 }
        val w = AiWindowWatcher(
            controls = controls,
            store = AiWatchStore().also { it.setAutoCalls(true) },
            snapshot = { snap },
            ask = { _, _ -> calls++; reply("""{"adjustments":[]}""") },
            endpoint = { LlmProviders.resolve(provider, "k", "", "") },
            now = { nowMs },
            paper = { listOf(PaperTape(paperCode, 30, 0, -30.0, 0.0, losing, emptyList())) }
        )
        w.tick()
        assertEquals(1, calls)
        assertFalse(first in controls.state.value.aiBench)
        nowMs += 100
        paperCode = second
        snap = snap.copy(fingerprint = "inside-cap")
        w.tick()
        assertEquals(1, calls)
        assertFalse(second in controls.state.value.aiBench)
    }

    @Test fun liveCardUpdatesOnEveryTickWithoutAnotherChatCall() {
        val store = AiWatchStore()
        var calls = 0
        val w = watcher(MemoryScalpControls(), store) { _, _ -> calls++; reply("""{"adjustments":[]}""") }
        w.onWake = { w.tick() }
        fun point(i: Int) = AiLiveSnapshot(
            atMs = nowMs, windowKey = "W", ticker = "KXBTC15M-W", closeMs = nowMs + 600_000L,
            strike = 80_000.0, btc = 80_010.0 + i, yesBid = 0.40 + i * 0.12, yesAsk = 0.42 + i * 0.12,
            noBid = 0.50, noAsk = 0.52, modelUp = 0.55
        )
        w.noteKalshi(point(0))
        assertEquals(1, calls)
        repeat(6) { i ->
            nowMs += 40
            w.noteKalshi(point(i + 1))
        }
        assertEquals(1, calls)
        assertEquals(7, store.live.value!!.points.size)
        assertEquals(nowMs, store.live.value!!.atMs)
    }

    @Test fun aHandTypedMessageIsNotHeldForTheCap() {
        var calls = 0
        val w = watcher(MemoryScalpControls()) { _, _ -> calls++; reply("""{"adjustments":[]}""") }
        w.tick()
        nowMs += 1_000
        val scope = TestScope(StandardTestDispatcher())
        var chatCalls = 0
        val chat = AiChatController(
            ChatHistoryStore(tmp.newFile("hand.json")), MemoryScalpControls(),
            ask = { chatCalls++; "gpt-4.1" to "answered" },
            hasKey = { true }, modelName = { "gpt-4.1" }, scope = scope
        )
        scope.advanceUntilIdle()
        chat.send("what is BTC doing", "leaderboard")
        scope.advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(1, chatCalls)
        assertTrue(chat.messages.value.any { it.role == ChatMessage.AI && it.text.contains("answered") })
    }

    @Test fun budgetPausesTheWatcherAndChatStillSends() {
        val store = AiWatchStore()
        store.setBudgetUsd(0.01)
        var calls = 0
        val w = watcher(MemoryScalpControls(), store) { _, _ -> calls++; reply("""{"adjustments":[]}""", prompt = 10_000) }
        w.tick()
        nowMs += 61_000
        snap = snap.copy(fingerprint = "fp-due")
        w.tick()
        assertEquals(1, calls)
        assertTrue(store.notice.value.contains("Chat still works"))
        assertTrue(store.budgetPaused(nowMs))

        var chatCalls = 0
        val scope = TestScope(StandardTestDispatcher())
        val chat = AiChatController(
            ChatHistoryStore(tmp.newFile("h.json")), MemoryScalpControls(),
            ask = { chatCalls++; "m" to "still chatting" },
            hasKey = { true }, modelName = { "grok-4.7" }, scope = scope
        )
        scope.advanceUntilIdle()
        chat.send("what is the window doing", "leaderboard")
        scope.advanceUntilIdle()
        assertEquals(1, chatCalls)
        assertTrue(chat.messages.value.any { it.role == ChatMessage.AI })
    }

    @Test fun promptIsSideNeutralAndQuietMarketsShareAFingerprint() {
        assertTrue(AiWatchPrompt.SYSTEM.contains("side-neutral"))
        assertTrue(AiWatchPrompt.SYSTEM.contains("Do not prefer UP"))
        assertTrue(AiWatchPrompt.SYSTEM.contains("Do not introduce an UP bias"))
        assertTrue(AiWatchPrompt.SYSTEM.contains("DOWN"))
        assertFalse(AiWatchPrompt.SYSTEM.contains("always buy YES"))
        val scalpers = listOf(AiWindowContext.Scalper("v2-fair", "ACTIVE", 3, 1.25, "maxEntryCents=50", 0))
        val a = AiWindowContext.Market("W", 80_901.0, 0.46, 0.48, 0.52, 0.54, 0.614, "flat")
        val b = AiWindowContext.Market("W", 80_904.0, 0.464, 0.484, 0.516, 0.536, 0.611, "flat")
        assertEquals(AiWindowContext.fingerprint(a, scalpers), AiWindowContext.fingerprint(b, scalpers))
        val moved = a.copy(spot = 80_914.0)
        assertFalse(AiWindowContext.fingerprint(a, scalpers) == AiWindowContext.fingerprint(moved, scalpers))
        val parsed = AiWatchJson.parse("Sure.\n```json\n{\"adjustments\":[{\"scalper\":\"v2-fair\",\"setting\":\"stopCents\",\"value\":8,\"reason\":\"tighter\"}]}\n```")
        assertEquals("v2-fair", parsed.single().scalper)
        assertEquals("8", parsed.single().value)
    }

    @Test fun adjustmentLogIsEasternAndCapped() {
        val store = AiWatchStore()
        val ms = LocalDateTime.of(2026, 10, 19, 15, 6, 5).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val line = AiAdjustmentLog.line(AiAdjustment(ms, "v2-fair", "maxEntryCents", "50", "45", "tighter"))
        assertTrue(line.contains("3:06:05 PM ET"))
        assertTrue(line.contains("maxEntryCents 50 → 45"))
        assertTrue(line.contains("tighter"))
        repeat(60) { store.addAdjustment(AiAdjustment(it.toLong(), "v2-fair", "stopCents", "6", "7", "r$it")) }
        assertEquals(AiAdjustmentLog.MAX, store.adjustments.value.size)
        assertEquals("r59", store.adjustments.value.first().reason)
        assertEquals("r10", store.adjustments.value.last().reason)
    }

    private fun watcher(controls: MemoryScalpControls, store: AiWatchStore = AiWatchStore(), ask: (LlmEndpoint, List<Pair<String, String>>) -> LlmClient.Reply): AiWindowWatcher {
        store.setAutoCalls(true)
        return AiWindowWatcher(controls, store, { snap }, ask, { LlmProviders.resolve(provider, "k", "", "") }, now = { nowMs })
    }

    private fun reply(text: String, prompt: Long = 12, completion: Long = 4) =
        LlmClient.Reply(text, "grok-4.7", "grok-4.7", prompt, completion)

    private fun source(name: String): String = listOf(
        java.io.File("app/src/main/java/com/dirk/kalshiodds/llm/$name"),
        java.io.File("src/main/java/com/dirk/kalshiodds/llm/$name")
    ).first { it.isFile }.readText()
}
