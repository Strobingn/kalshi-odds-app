package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.AiBench
import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.MemoryScalpControls
import com.dirk.kalshiodds.decision.PaperTape
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.StrategyAlgo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 1.0.14 tuner, 60s chat-LLM cap, Kalshi-tick feed, and usage charts. Old behavior stays. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiRelease1014Test {
    @get:Rule val tmp = TemporaryFolder()
    private val et = ZoneId.of("America/New_York")
    private fun at(day: LocalDate, hour: Int) = day.atTime(LocalTime.of(hour, 0)).atZone(et).toInstant().toEpochMilli()

    @Test fun spacingIsSixtySecondsForEveryChatProvider() {
        assertEquals(60_000L, AiLiveFeed.MIN_SPACING_MS)
        assertEquals(60_000L, AiWatchConfig.MIN_INTERVAL_MS)
        assertFalse(AiLiveFeed.llmDue(0L, 59_999L, LiveTrigger.LIVE_TICK, true))
        assertFalse(AiLiveFeed.llmDue(0L, 59_999L, LiveTrigger.WINDOW, true))
        assertTrue(AiLiveFeed.llmDue(0L, 60_000L, LiveTrigger.BIG_MOVE, false))
        assertTrue(AiLiveFeed.llmDue(0L, 60_000L, LiveTrigger.NONE, true))
        assertFalse(AiLiveFeed.llmDue(0L, 60_000L, LiveTrigger.NONE, false))
        assertTrue(AiLiveFeed.llmDue(Long.MIN_VALUE, 0L, LiveTrigger.NONE, true))
        val store = AiWatchStore()
        store.setIntervalMs(10_000L)
        assertEquals(60_000L, store.config.value.intervalMs)
    }

    @Test fun liveStateUpdatesOnEachKalshiTick() {
        val store = AiWatchStore()
        val w = watcher(MemoryScalpControls(), store)
        w.noteKalshi(snap(1_000L, 0.40, 80_000.0))
        w.noteKalshi(snap(1_100L, 0.47, 80_040.0))
        val live = store.live.value!!
        assertEquals(1_100L, live.atMs)
        assertEquals(0.47, live.yesAsk!!, 1e-9)
        assertEquals(80_040.0, live.btc!!, 1e-6)
        assertEquals(2, live.points.size)
        assertNotEquals(live.points[0].up, live.points[1].up)
        assertTrue(live.ageMs(1_100L) < 5_000L)
    }

    @Test fun algoStepsStayInsideBounds() {
        val controls = ScalpControls()
        val code = KeptScalpers.V2_FAIR_ID
        val entry = StrategyAlgo.knob(controls, code, "maxEntryCents")!!
        assertEquals(1.0, entry.min, 0.0)
        assertEquals(55.0, entry.max, 0.0)
        assertEquals(1.0, entry.step, 0.0)
        assertEquals(entry.min, StrategyAlgo.step(entry.min, entry.min - 25, entry)!!, 0.0)
        assertEquals(entry.max, StrategyAlgo.step(entry.max, entry.max + 25, entry)!!, 0.0)
        val size = StrategyAlgo.knob(controls, code, "contracts")!!
        val current = StrategyAlgo.current(controls, code, size)
        assertEquals(current + 1.0, StrategyAlgo.step(current, current + 40, size)!!, 0.0)
        assertTrue(size.max <= StrategyAlgo.contractCap(controls).toDouble() + 1e-9)
    }

    @Test fun manualForceTradeWinsAndFreezeKeepsTheStrategy() {
        val code = ScalpModels.ALL.first { it.code.startsWith("v2-") && it.code != "v2-llm" }.code
        assertTrue(ScalpModels.ALL.any { it.code == code })
        val losing = List(30) { -1.0 }
        assertTrue(AiBench.decide(false, losing, emptyList(), 30).bench)
        assertFalse(AiBench.decide(false, List(30) { -0.01 }, emptyList(), 30).bench)
        assertFalse(AiBench.decide(false, List(29) { -5.0 }, emptyList(), 30).bench)
        assertTrue(AiBench.decide(true, losing, List(30) { 0.2 }, 30).unbench)

        val forced = MemoryScalpControls(ScalpControls(benchOverrides = mapOf(code to "ACTIVE")))
        val store = AiWatchStore()
        watcher(forced, store, paper = { listOf(PaperTape(code, 30, 0, -30.0, 0.0, losing, emptyList())) }).tick()
        assertFalse(code in forced.state.value.aiBench)
        assertTrue(ScalpModels.modelByCode(code) != null)

        val open = MemoryScalpControls()
        watcher(open, store, paper = { listOf(PaperTape(code, 30, 0, -30.0, 0.0, losing, emptyList())) }).tick()
        assertTrue(code in open.state.value.aiBench)
        assertEquals("FROZEN", store.adjustments.value.first().newValue)
    }

    @Test fun tunerCannotTouchRealMoneyAndOldRecordsLoad() {
        val src = listOf("AiWindowWatch.kt", "AiLiveFeed.kt", "StrategyAlgo.kt").joinToString("\n") { name ->
            listOf(java.io.File("app/src/main/java/com/dirk/kalshiodds/llm/$name"), java.io.File("app/src/main/java/com/dirk/kalshiodds/decision/$name"),
                java.io.File("src/main/java/com/dirk/kalshiodds/llm/$name"), java.io.File("src/main/java/com/dirk/kalshiodds/decision/$name"))
                .firstOrNull { it.isFile }?.readText().orEmpty()
        }
        assertFalse(src.contains("KalshiTradeClient"))
        assertFalse(src.contains("liveTradingArmed"))
        val c = MemoryScalpControls(ScalpControls(killSwitch = false))
        val log = AiWatchStore()
        val w = watcher(c, log) { _, _ ->
            reply("""{"adjustments":[
                {"scalper":"v2-fair","setting":"liveTradingArmed","value":true,"reason":"arm"},
                {"scalper":"v2-fair","setting":"placeOrder","value":1,"reason":"buy"},
                {"scalper":"v2-fair","setting":"maxEntryCents","value":1,"reason":"paper"}
            ]}""")
        }
        w.tick()
        assertFalse(c.state.value.killSwitch)
        assertTrue(log.adjustments.value.none { it.setting.contains("live") || it.setting.contains("Order") || it.setting.contains("place") })
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<ScalpControls>("""{"contracts":4,"stopCents":6}""")
        assertTrue(old.algo.isEmpty())
        assertTrue(old.aiBench.isEmpty())
        assertEquals(4, old.contracts)
        assertEquals(6, old.stopCents)
    }

    @Test fun promptSaysTheFeedIsLiveAndTheSnapshotIsFreshAtSend() {
        val system = ChatPrompt.system("settings", "ctx")
        assertTrue(system.contains(ChatPrompt.LIVE_FEED))
        assertTrue(system.contains("Never tell the user to ping or message again"))
        assertTrue(system.contains("Never say you cannot watch continuously"))
        assertFalse(system.contains("as of this message"))
        assertFalse(system.contains("next message"))
        assertFalse(system.contains("snapshot", ignoreCase = true))
        assertTrue(system.contains("can never touch real money"))
        assertTrue(system.startsWith(ChatPrompt.MARKER))
        var now = 5_000L
        var reads = 0
        val scope = TestScope(StandardTestDispatcher())
        var sent = ""
        val chat = AiChatController(
            ChatHistoryStore(tmp.newFile("h.json")), MemoryScalpControls(),
            ask = { sent = it.first().second; "grok-4.7" to "watching" },
            hasKey = { true }, modelName = { "grok-4.7" }, scope = scope, now = { now },
            liveContext = { reads++; "builtAt=$now" }
        )
        scope.advanceUntilIdle()
        assertEquals(0, reads)
        now = 8_000L
        chat.send("what is the window", "")
        scope.advanceUntilIdle()
        assertEquals(1, reads)
        assertFalse(sent.contains("snapshotAsOfMs"))
        assertTrue(sent.contains("builtAt=8000"))
    }

    @Test fun usageChartsAggregateRealCallsOnly() {
        val store = AiWatchStore()
        val day = LocalDate.of(2026, 10, 19)
        val noon = at(day, 12)
        store.record(LlmSources.LIVE_TICK, LlmProviders.XAI, "grok-4.7", 1_000_000, 0, noon)
        store.record(LlmSources.CHAT, LlmProviders.XAI, "grok-4.7", 0, 500_000, noon)
        store.record(LlmSources.WINDOW, LlmProviders.XAI, "grok-4.7", 0, 0, at(day, 9))
        store.record(LlmSources.BIG_MOVE, LlmProviders.XAI, "grok-4.7", 0, 0, at(day.minusDays(1), 15))
        val prices = store.prices()
        val dollars = AiUsageCharts.dollarsToday(store.hourBuckets(), prices, noon)
        assertEquals(13, dollars.size)
        assertEquals(2.0 + 3.0, dollars[12].dollars, 1e-9) // $2 in + $6 * 0.5 out
        assertEquals(dollars[12].dollars, dollars[12].cumulative - dollars[11].cumulative, 1e-9)
        val calls = AiUsageCharts.callsToday(store.hourBuckets(), noon)
        assertEquals(1, calls[12].bySource.getValue(LlmSources.LIVE_TICK))
        assertEquals(1, calls[12].bySource.getValue(LlmSources.CHAT))
        assertEquals(1, calls[9].bySource.getValue(LlmSources.WINDOW))
        assertEquals(0, calls[12].bySource.getValue(LlmSources.BIG_MOVE))
        val days = AiUsageCharts.tokensLast7(store.hourBuckets(), noon)
        assertEquals(7, days.size)
        assertEquals(1_000_000L, days.last().prompt)
        assertEquals(500_000L, days.last().completion)
        val pace = AiUsageCharts.projection(5.0, noon, 4.0)
        assertTrue(pace.projectedUsd > 5.0)
        assertTrue(pace.overPace)
        assertFalse(AiUsageCharts.projection(1.0, noon, null).overPace)
        assertTrue(AiUsageCharts.dollarsToday(emptyList(), prices, noon).all { it.dollars == 0.0 })
    }

    private fun snap(at: Long, ask: Double, btc: Double) = AiLiveSnapshot(
        atMs = at, windowKey = "W", ticker = "KXBTC15M-W-T", closeMs = at + 600_000L,
        strike = 80_000.0, btc = btc, yesBid = ask - 0.01, yesAsk = ask, noBid = 1 - ask, noAsk = 1 - ask + 0.01, modelUp = 0.62
    )

    private fun watcher(
        controls: MemoryScalpControls,
        store: AiWatchStore = AiWatchStore(),
        paper: () -> List<PaperTape> = { emptyList() },
        ask: (LlmEndpoint, List<Pair<String, String>>) -> LlmClient.Reply = { _, _ -> reply("""{"adjustments":[]}""") }
    ): AiWindowWatcher {
        store.setAutoCalls(true)
        return AiWindowWatcher(controls, store, { WindowSnapshot("W", 1_700_000_900_000L, "live", "fp") }, ask, { LlmProviders.resolve(LlmProviders.XAI, "k", "", "") }, now = { 1_700_000_000_000L }, paper = paper)
    }

    private fun reply(text: String) = LlmClient.Reply(text, "grok-4.7", "grok-4.7", 12, 4)
}
