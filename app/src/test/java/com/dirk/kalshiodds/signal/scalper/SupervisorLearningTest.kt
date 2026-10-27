package com.dirk.kalshiodds.signal.scalper

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Scalping AI must learn from the scalps it makes, and keep that learning across a restart. */
class SupervisorLearningTest {
    /** An active strategy: SupervisorBounds refuses a change to a retired one. */
    private val st = ScalpStrategy.LATE_COMEBACK

    private fun closed(pnl: Double, atMs: Long, strategy: ScalpStrategy = st) = ClosedScalp(
        ScalpOrder(
            atMs, strategy, "KXBTC15M-T", "YES", 0.40, 10,
            queueAhead = 0.0, queueKnown = true, postedAtMs = atMs - 1_000L,
            predictionCents = 0.0, expectedCents = 0.0
        ),
        exitPrice = 0.40 + pnl / 10.0, kind = ExitKind.TARGET, feeUsd = 0.0,
        pnlUsd = pnl, filledAtMs = atMs - 500L, closedAtMs = atMs
    )

    /** A chat that records the prompt it was handed and asks for one change. */
    private class Spy(private val answer: String? = null) : LlmChat {
        var lastUser: String? = null
        override fun ask(system: String, user: String, tools: JsonArray): String? {
            lastUser = user
            return answer
        }
    }

    private fun supervisor(
        chat: LlmChat,
        tuning: ScalpTuning = ScalpTuning(),
        memory: SupervisorMemory = SupervisorMemory(),
        loadTrades: () -> List<LearnedTrade> = { emptyList() },
        saved: MutableList<String> = ArrayList(),
        nowMs: () -> Long = { 10_000L }
    ) = LlmSupervisor(
        tuning = tuning,
        chat = chat,
        feed = { JsonObject(emptyMap()) },
        stats = { ScalpStats() },
        nowMs = nowMs,
        persistMemory = { saved.add(it) },
        initialMemory = memory,
        loadTrades = loadTrades,
        strategies = listOf(st)
    )

    @Test
    fun theScalpsItMakesReachItsNextPrompt() {
        val spy = Spy()
        val s = supervisor(spy)
        s.onClosed(closed(-0.75, 1_000L))
        s.onClosed(closed(1.25, 2_000L))
        s.tick()

        val ctx = Json.parseToJsonElement(spy.lastUser!!).jsonObject
        val trades = ctx["lastTradesWithSettings"]!!.jsonArray
        assertEquals(2, trades.size)
        assertEquals(-0.75, trades[0].jsonObject["pnlUsd"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(1.25, trades[1].jsonObject["pnlUsd"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun theTradesItRecordedBeforeTheRestartAreReadBackOnTheFirstTick() {
        val spy = Spy()
        val past = (1..3).map { i ->
            LearnedTrade(
                closedAtMs = i * 100L, strategy = st.name, ticker = "T", side = "YES", kind = "TARGET",
                contracts = 10, entry = 0.40, exit = 0.42, pnlUsd = 0.2, heldSeconds = 5, knobs = ScalpKnobs()
            )
        }
        val s = supervisor(spy, loadTrades = { past })
        s.onClosed(closed(1.0, 5_000L))
        s.tick()

        val trades = Json.parseToJsonElement(spy.lastUser!!).jsonObject["lastTradesWithSettings"]!!.jsonArray
        assertEquals("three off disk plus the live one", 4, trades.size)
        assertEquals("oldest first", 100.0, trades[0].jsonObject["closedAtMs"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(5_000.0, trades[3].jsonObject["closedAtMs"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun theDiskIsReadOnceNotOnEveryTick() {
        var reads = 0
        val s = supervisor(Spy(), loadTrades = { reads += 1; emptyList() })
        s.tick(); s.tick(); s.tick()
        assertEquals(1, reads)
    }

    @Test
    fun aChangeAwaitingJudgementIsKeptAndScoredAfterARestart() {
        val saved = ArrayList<String>()
        val tuning = ScalpTuning()
        val applied = """{"changes":[{"strategy":"${st.name}","param":"contracts","value":7,"reason":"smaller"}]}"""
        val first = supervisor(Spy(applied), tuning = tuning, saved = saved)
        first.onClosed(closed(-1.0, 1_000L))
        first.tick()

        val kept = Json { ignoreUnknownKeys = true }
            .decodeFromString(SupervisorMemory.serializer(), saved.last())
        assertEquals("the change is waiting to be judged", 1, kept.pending.size)
        assertEquals("contracts", kept.pending.first().param)

        // A new launch: same memory, the trades read back off disk.
        val spy2 = Spy()
        val past = (1..LlmSupervisor.EVAL_TRADES).map { i ->
            LearnedTrade(
                closedAtMs = 20_000L + i, strategy = st.name, ticker = "T", side = "YES", kind = "TARGET",
                contracts = 7, entry = 0.40, exit = 0.45, pnlUsd = 0.5, heldSeconds = 5, knobs = ScalpKnobs()
            )
        }
        val saved2 = ArrayList<String>()
        val second = supervisor(spy2, memory = kept, loadTrades = { past }, saved = saved2, nowMs = { 30_000L })
        second.tick()

        val after = Json { ignoreUnknownKeys = true }
            .decodeFromString(SupervisorMemory.serializer(), saved2.last())
        assertTrue("it was judged after the restart", after.pending.isEmpty())
        assertEquals(1, after.scored.size)
        assertEquals("contracts", after.scored.first().param)
        assertEquals("better", after.scored.first().verdict)
    }

    @Test
    fun theSupervisorLogReadsBackWhatItWrote() {
        val dir = Files.createTempDirectory("supervisor").toFile()
        val log = SupervisorLog(dir)
        val json = Json { encodeDefaults = false; explicitNulls = false }
        val trades = (1..5).map { i ->
            LearnedTrade(
                closedAtMs = i * 1_000L, strategy = st.name, ticker = "T", side = "NO", kind = "STOP",
                contracts = 100, entry = 0.30, exit = 0.27, pnlUsd = -3.0, heldSeconds = 9, knobs = ScalpKnobs()
            )
        }
        trades.forEach { log.append("trades", json.encodeToString(LearnedTrade.serializer(), it)) }
        // The log writes on its own thread; wait for the file to catch up.
        val f = java.io.File(dir, "supervisor_trades.jsonl")
        for (i in 1..100) {
            if (f.isFile && f.readLines().size == 5) break
            Thread.sleep(20)
        }

        val back = log.recentTrades(3)
        assertEquals(3, back.size)
        assertEquals("newest three, oldest first", listOf(3_000L, 4_000L, 5_000L), back.map { it.closedAtMs })
        assertEquals(100, back.first().contracts)
        assertEquals(st.name, back.first().strategy)
        assertNotNull(back.first().knobs)
        assertEquals("nothing asked for, nothing read", emptyList<LearnedTrade>(), log.recentTrades(0))
    }

    @Test
    fun anEmptyOrMissingLogIsNotAProblem() {
        val dir = Files.createTempDirectory("supervisor-empty").toFile()
        assertEquals(emptyList<LearnedTrade>(), SupervisorLog(dir).recentTrades(10))
    }

    @Test
    fun aMemoryWrittenBeforePendingWasKeptStillLoads() {
        val old = """{"lessons":["one"],"scored":[]}"""
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(SupervisorMemory.serializer(), old)
        assertEquals(listOf("one"), m.lessons)
        assertTrue(m.pending.isEmpty())
    }

    @Test
    fun theLiveTunerAlsoSeesEveryClosedScalp() {
        val tuning = ScalpTuning()
        val changes = ArrayList<LiveTuner.Change>()
        val t = LiveTuner(tuning) { changes.add(it) }
        repeat(LiveTuner.EVAL_TRADES * 2) { t.onClosed(closed(-1.0, 1_000L + it, ScalpStrategy.LATE_COMEBACK)) }
        assertFalse("a losing run tightens the strategy", t.step().isEmpty())
        assertTrue(changes.isNotEmpty())
    }
}
