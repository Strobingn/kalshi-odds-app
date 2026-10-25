package com.dirk.kalshiodds.signal.scalper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI supervisor: bounded settings changes, no cooldown, and learning from every closed trade. */
class LlmSupervisorTest {

    /** A strategy that still runs: only the late buy-and-hold ones do (every target/stop scalper is retired). */
    private val st = ScalpStrategy.KASHI_FAV_BASE
    private val ticker = "KXBTC15M-26OCT091215-15"

    private fun applied(c: SupervisorBounds.Change) = c as SupervisorBounds.Change.Applied

    @Test
    fun aChangeIsClampedToAQuarterOfTheRange() {
        val t = ScalpTuning()
        val before = t.tuned(st).targetDollars * 100.0
        val r = applied(SupervisorBounds.apply(t, st.name, "targetCents", 1_000.0))
        val range = SupervisorBounds.rangeOf(st, "targetCents")!!
        val step = (range.hi - range.lo) * SupervisorBounds.STEP_FRACTION
        assertEquals(before + step, r.new, 0.11)
        assertEquals(r.new, t.tuned(st).targetDollars * 100.0, 0.01)
    }

    @Test
    fun thereIsNoCooldownBetweenChanges() {
        val t = ScalpTuning()
        val a = applied(SupervisorBounds.apply(t, st.name, "stopCents", 30.0))
        val b = applied(SupervisorBounds.apply(t, st.name, "stopCents", 30.0))
        assertTrue("moves again on the very next change", b.new > a.new)
    }

    @Test
    fun badInputIsRejectedAndLeavesTheSettingsAlone() {
        val t = ScalpTuning()
        assertTrue(SupervisorBounds.apply(t, "NOT_A_STRATEGY", "targetCents", 5.0) is SupervisorBounds.Change.Rejected)
        assertTrue(SupervisorBounds.apply(t, st.name, "nope", 5.0) is SupervisorBounds.Change.Rejected)
        assertTrue(SupervisorBounds.apply(t, st.name, "targetCents", Double.NaN) is SupervisorBounds.Change.Rejected)
        assertTrue(t.knobs(st).isDefault)
    }

    @Test
    fun theEntryBandNeverLeavesTenToNinetyEightCents() {
        val t = ScalpTuning()
        repeat(6) { SupervisorBounds.apply(t, st.name, "entryMinCents", 1.0) }
        repeat(6) { SupervisorBounds.apply(t, st.name, "entryMaxCents", 120.0) }
        val tn = t.tuned(st)
        assertTrue(tn.entryMin * 100.0 >= 10.0 - 1e-9)
        assertTrue(tn.entryMax * 100.0 <= 98.0 + 1e-9)
        assertTrue(tn.entryMin < tn.entryMax)
    }

    @Test
    fun lateFavouriteSettingsMoveOnlyOnTheStrategiesThatHaveThem() {
        val t = ScalpTuning()
        assertTrue(SupervisorBounds.apply(t, ScalpStrategy.LATE_COMEBACK.name, "edgeCents", 5.0) is SupervisorBounds.Change.Rejected)
        val edge = applied(SupervisorBounds.apply(t, st.name, "edgeCents", 9.0))
        assertEquals(edge.new, t.tuned(st).favGap!! * 100.0, 0.01)
        val before = t.tuned(st).secondsLeft!!
        applied(SupervisorBounds.apply(t, st.name, "secondsLeftMax", 400.0))
        assertTrue(t.tuned(st).secondsLeft!!.last > before.last)
    }

    @Test
    fun theAiMayNotSwitchAStrategyOffButMaySwitchItBackOn() {
        val t = ScalpTuning()
        // You decided 2026-10-24: strategies stay on.
        assertTrue(SupervisorBounds.apply(t, st.name, "enabled", 0.0) is SupervisorBounds.Change.Rejected)
        assertTrue(t.tuned(st).enabled)
        // Your own Apply (the chat) may switch one off, and the AI may turn it back on.
        applied(SupervisorBounds.apply(t, st.name, "enabled", 0.0, allowOff = true))
        assertFalse(t.tuned(st).enabled)
        applied(SupervisorBounds.apply(t, st.name, "enabled", 1.0))
        assertTrue(t.tuned(st).enabled)
    }

    @Test
    fun theJournalTellsWhichStrategiesTheAiLeftOff() {
        val dir = java.nio.file.Files.createTempDirectory("journal").toFile()
        java.io.File(dir, "supervisor_journal.1.jsonl").writeText(
            "{\"event\":\"applied\",\"strategy\":\"BOUNCE\",\"param\":\"enabled\",\"old\":1.0,\"new\":0.0}\n"
        )
        java.io.File(dir, "supervisor_journal.jsonl").writeText(
            "{\"event\":\"applied\",\"strategy\":\"LONG_SHOT\",\"param\":\"enabled\",\"old\":1.0,\"new\":0.0}\n" +
                "{\"event\":\"rejected\",\"strategy\":\"RANGE_FLOOR\",\"param\":\"enabled\"}\n" +
                "{\"event\":\"applied\",\"strategy\":\"BOUNCE\",\"param\":\"enabled\",\"old\":0.0,\"new\":1.0}\n" +
                "{\"event\":\"applied\",\"strategy\":\"QUIET_MAKER\",\"param\":\"contracts\",\"new\":0.0}\n"
        )
        assertEquals(setOf("LONG_SHOT"), SupervisorLog(dir).turnedOffByAi())
        dir.deleteRecursively()
    }

    private fun closed(id: Long, postedAt: Long, closedAt: Long, pnl: Double) = ClosedScalp(
        order = ScalpOrder(
            id = id, strategy = st, ticker = ticker, side = "YES", price = 0.50, contracts = 10,
            queueAhead = 0.0, queueKnown = false, postedAtMs = postedAt, predictionCents = 0.0, expectedCents = 0.0
        ),
        exitPrice = 0.55, kind = if (pnl > 0) ExitKind.TARGET else ExitKind.STOP, feeUsd = 0.0, pnlUsd = pnl,
        filledAtMs = postedAt + 500, closedAtMs = closedAt
    )

    @Test
    fun everyClosedTradeIsLearnedWithTheSettingsItRanUnder() {
        val tuning = ScalpTuning()
        var now = 1_000L
        val seen = ArrayList<String>()
        val answers = ArrayDeque(
            listOf(
                """{"changes":[{"strategy":"${st.name}","param":"targetCents","value":25,"reason":"wider target"}],"lesson":"dips bounce"}""",
                """{"changes":[]}"""
            )
        )
        val sup = LlmSupervisor(
            tuning = tuning,
            chat = LlmChat { _, user, _ -> seen.add(user); answers.removeFirst() },
            feed = { buildJsonObject { put("ticker", ticker) } },
            stats = { ScalpStats() },
            nowMs = { now }
        )

        // A trade that ran before any change, then the model changes the target.
        sup.onClosed(closed(1, postedAt = 100, closedAt = 900, pnl = -0.2))
        assertEquals("tick 1: 1 change applied", sup.tick())
        val target = tuning.tuned(st).targetDollars * 100.0

        // A trade posted after the change.
        now = 5_000L
        sup.onClosed(closed(2, postedAt = 2_000, closedAt = 4_000, pnl = 0.6))
        assertEquals("tick 2: no change", sup.tick())

        val ctx = Json.parseToJsonElement(seen[1]).jsonObject
        val trades: JsonArray = ctx["lastTradesWithSettings"]!!.jsonArray
        assertEquals(2, trades.size)
        val first = trades[0].jsonObject["knobs"]!!.jsonObject
        val second = trades[1].jsonObject["knobs"]!!.jsonObject
        assertNull("the first trade ran on the defaults", first["targetCents"])
        assertNotNull("the second ran on the changed target", second["targetCents"])
        assertEquals(target, second["targetCents"]!!.toString().toDouble(), 0.01)
        assertTrue(ctx["yourNotes"]!!.jsonArray.toString().contains("dips bounce"))
        assertTrue((ctx["feed"] as JsonObject)["ticker"].toString().contains("KXBTC15M"))
    }

    @Test
    fun aChangeIsScoredAfterEnoughTradesAndTheModelSeesTheVerdict() {
        val tuning = ScalpTuning()
        var now = 1_000L
        val seen = ArrayList<String>()
        val answers = ArrayDeque(
            listOf(
                """{"changes":[{"strategy":"${st.name}","param":"stopCents","value":30,"reason":"wider stop"}]}""",
                """{"changes":[]}"""
            )
        )
        val sup = LlmSupervisor(
            tuning = tuning,
            chat = LlmChat { _, user, _ -> seen.add(user); answers.removeFirst() },
            feed = { JsonObject(emptyMap()) },
            stats = { ScalpStats() },
            nowMs = { now }
        )
        sup.onClosed(closed(1, 10, 900, 0.5)) // before: +0.50
        sup.tick()
        now = 10_000L
        repeat(LlmSupervisor.EVAL_TRADES) { i -> sup.onClosed(closed(10L + i, 2_000, 3_000L + i, -0.3)) }
        sup.tick()
        val ctx = Json.parseToJsonElement(seen[1]).jsonObject
        val scored = ctx["yourScoredChanges"]!!.jsonArray
        assertEquals(1, scored.size)
        assertEquals("\"worse\"", scored[0].jsonObject["verdict"].toString())
    }

    @Test
    fun noAnswerIsReportedAndChangesNothing() {
        val tuning = ScalpTuning()
        val sup = LlmSupervisor(tuning, LlmChat { _, _, _ -> null }, { JsonObject(emptyMap()) }, { ScalpStats() })
        assertTrue(sup.tick().contains("no answer"))
        assertTrue(tuning.all().isEmpty())
    }

    @Test
    fun toolCallArgumentsAreReadFromAnOpenAiStyleAnswer() {
        val body = """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"1","type":"function",
            "function":{"name":"apply_changes","arguments":"{\"changes\":[]}"}}]}}]}"""
        assertEquals("""{"changes":[]}""", HttpLlmChat.parseArguments(body))
        assertNull(HttpLlmChat.parseArguments("""{"choices":[{"message":{"content":"hi"}}]}"""))
        assertNull(HttpLlmChat.parseArguments("not json"))
    }
}
