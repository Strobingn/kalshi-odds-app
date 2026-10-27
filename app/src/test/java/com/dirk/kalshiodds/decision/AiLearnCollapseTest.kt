package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI learns used to pin every strategy on a 1¢ buy band and minute 1.
 * Losers still tighten. They stop at a real band, a usable until-minute, and a stop that can hold a fill.
 */
class AiLearnCollapseTest {
    private val streakCodes = listOf("sf41", "sf42", "sf17f", KeptScalpers.V2_DIP_ID, "fair", "r-hold")

    private fun defMax(code: String): Int = when {
        Research.isResearch(code) -> 99
        code.startsWith("v2-") -> kotlin.math.round(ScalpV2.spec(code).maxAsk * 100).toInt()
        else -> FairGapTune.defaultMaxCents(code) ?: kotlin.math.round(ScalpRule.ENTRY_HI * 100).toInt()
    }

    private fun width(code: String, s: ScalpStrategySettings): Int {
        val high = s.maxEntryCents ?: ScalpFloors.ceilingCents(code)
        return high - s.minEntryCents
    }

    @Test fun modestLoserStreakCannotPinTheOneCentMinuteOneTrap() {
        val nets = listOf(-0.05, -1.0)
        streakCodes.forEach { code ->
            nets.forEach { net ->
                var s = ScalpControls().settings(code)
                repeat(40) { s = Research.learn(code, s, net, defMax(code)) }
                val room = AiLearnBounds.bandRoom(code)
                assertTrue("$code net $net width ${width(code, s)} note ${s.aiNote}", width(code, s) >= room)
                assertTrue("$code net $net until ${s.untilMinute}", s.untilMinute >= AiLearnBounds.UNTIL_FLOOR)
                assertFalse("$code net $net", s.untilMinute <= 1 && width(code, s) <= 1)
                if (s.stopLossPercent != null) {
                    assertTrue("$code stop ${s.stopLossPercent}", s.stopLossPercent!! >= AiLearnBounds.STOP_PCT_FLOOR)
                }
            }
        }
        var sf42 = ScalpControls().settings("sf42")
        repeat(40) { sf42 = Research.learn("sf42", sf42, -0.05, 55) }
        assertEquals(35, sf42.maxEntryCents)
        assertEquals(25, sf42.minEntryCents)
        assertEquals(AiLearnBounds.UNTIL_FLOOR, sf42.untilMinute)
        assertTrue(sf42.aiNote.startsWith("AI kept settings"))
        var stopped = ScalpControls().settings("sf42").copy(stopLossPercent = 20)
        repeat(40) { stopped = Research.learn("sf42", stopped, -0.05, 55) }
        assertEquals(AiLearnBounds.STOP_PCT_FLOOR, stopped.stopLossPercent)
        assertTrue(width("sf42", stopped) >= AiLearnBounds.MIN_BAND_CENTS)
    }

    @Test fun winnersStillLoosenAndAHandSetTightBandStays() {
        var s = ScalpStrategySettings(
            maxEntryCents = 40, minEntryCents = 20, untilMinute = 6, contracts = 4,
            stopLossPercent = 10, aiN = 10, aiNet = 2.0, aiLearns = true
        )
        repeat(3) { s = Research.learn("sf42", s, 0.50, 55) }
        assertTrue(s.maxEntryCents!! > 40)
        assertTrue(s.minEntryCents < 20)
        assertTrue(s.untilMinute > 6)
        assertTrue(s.contracts!! > 4)
        assertTrue(s.stopLossPercent!! > 10)
        assertTrue(s.aiNote.contains("winner"))

        val hand = ScalpStrategySettings(
            minEntryCents = 34, maxEntryCents = 35, untilMinute = 2, stopLossPercent = 3,
            takeProfitCents = 15, aiLearns = true
        ).manual()
        var kept = hand
        repeat(12) { kept = Research.learn("sf42", kept, -0.05, 55) }
        assertTrue(kept.maxEntryCents!! - kept.minEntryCents > PaperFleetDefaults.COLLAPSED_WIDTH)
        assertEquals(2, kept.untilMinute)
        assertEquals(3, kept.stopLossPercent)
        assertEquals(15, kept.takeProfitCents)
        val wide = ScalpStrategySettings(
            minEntryCents = 20, maxEntryCents = 40, untilMinute = 6, stopLossPercent = 12,
            takeProfitCents = 15, aiLearns = true
        ).manual()
        val repaired = ScalpAutoBenchMigration.repairLearnedBands(ScalpControls(strategySettings = mapOf("sf42" to hand, "sf41" to wide)))
        assertEquals(PaperFleetDefaults.BUY_MIN, repaired.settings("sf42").minEntryCents)
        assertEquals(PaperFleetDefaults.BUY_MAX, repaired.settings("sf42").maxEntryCents)
        assertEquals(2, repaired.settings("sf42").untilMinute)
        assertEquals(20, repaired.settings("sf41").minEntryCents)
        assertEquals(40, repaired.settings("sf41").maxEntryCents)
    }

    @Test fun storedCollapsedRowsRepairOnLoadAndDoNotRecollapse() {
        val shot = ScalpStrategySettings(
            minEntryCents = 34, maxEntryCents = 35, untilMinute = 1, stopLossPercent = 1,
            takeProfitCents = 15, aiLearns = true, aiN = 22, aiBaseCents = 55, aiNet = -1.2,
            aiNote = "AI kept settings (loser -0.05)"
        )
        val others = streakCodes.associateWith { code ->
            val floor = ScalpFloors.cents(code)
            val ceil = ScalpFloors.ceilingCents(code)
            val max = (floor + 1).coerceAtMost(ceil)
            ScalpStrategySettings(
                minEntryCents = (max - 1).coerceAtLeast(floor), maxEntryCents = max, untilMinute = 1,
                stopLossPercent = 1, stopCents = 1, takeProfitCents = 15, aiLearns = true, aiN = 18,
                aiBaseCents = ceil, aiNote = "AI kept settings (loser -0.05)"
            )
        }
        val blob = ScalpControls(strategySettings = others + ("sf42" to shot))
        val once = ScalpAutoBenchMigration.repairLearnedBands(blob)
        val twice = ScalpAutoBenchMigration.repairLearnedBands(once)
        assertEquals(once, twice)
        val sf42 = once.settings("sf42")
        assertEquals(PaperFleetDefaults.BUY_MIN, sf42.minEntryCents)
        assertEquals(PaperFleetDefaults.BUY_MAX, sf42.maxEntryCents)
        assertEquals(AiLearnBounds.UNTIL_FLOOR, sf42.untilMinute)
        assertEquals(AiLearnBounds.STOP_PCT_FLOOR, sf42.stopLossPercent)
        assertEquals(15, sf42.takeProfitCents)
        assertTrue(sf42.aiNote.contains("repaired"))
        assertTrue(sf42.aiNote.contains("15–55"))
        others.keys.forEach { code ->
            val row = once.settings(code)
            assertTrue("$code ${row.minEntryCents}–${row.maxEntryCents}", width(code, row) >= AiLearnBounds.bandRoom(code))
            assertTrue("$code until ${row.untilMinute}", row.untilMinute >= AiLearnBounds.UNTIL_FLOOR)
            assertTrue("$code stop ${row.stopLossPercent}", row.stopLossPercent!! >= AiLearnBounds.STOP_PCT_FLOOR)
            if (row.stopCents != null) assertTrue(row.stopCents!! >= AiLearnBounds.STOP_CENTS_FLOOR)
            val entry = (row.minEntryCents + (row.maxEntryCents ?: row.minEntryCents)) / 2
            val profit = if (15 + entry > 99) (99 - entry).coerceAtLeast(1) else 15
            assertEquals(code, profit, row.takeProfitCents)
        }
        var again = sf42
        repeat(30) { again = Research.learn("sf42", again, -0.05, 55) }
        assertEquals(25, again.minEntryCents)
        assertEquals(35, again.maxEntryCents)
        assertEquals(AiLearnBounds.UNTIL_FLOOR, again.untilMinute)
        assertEquals(AiLearnBounds.STOP_PCT_FLOOR, again.stopLossPercent)
        assertEquals(15, again.takeProfitCents)
    }

    @Test fun paperTunerLoserPassesCannotCloseTheBandOrTheWindow() {
        listOf("sf41", "sf42", "sf17f", KeptScalpers.V2_DIP_ID).forEach { code ->
            var controls = ScalpControls()
            val tape = PaperTape(code, 12, 3, -1.2, 0.25, List(12) { -0.10 }, emptyList())
            repeat(25) {
                val changes = PaperSettingTuner.propose(controls, listOf(tape))
                var row = controls.settings(code)
                changes.filter { it.scalper == code }.forEach { row = applyChange(row, it) }
                controls = controls.copy(strategySettings = controls.strategySettings + (code to row))
                val view = controls.settings(code)
                assertTrue("$code ${view.minEntryCents}–${view.maxEntryCents}", width(code, view) >= AiLearnBounds.bandRoom(code))
                assertTrue("$code until ${view.untilMinute}", view.untilMinute >= AiLearnBounds.UNTIL_FLOOR)
                if (view.stopLossPercent != null) {
                    assertTrue("$code stop% ${view.stopLossPercent}", view.stopLossPercent!! >= AiLearnBounds.STOP_PCT_FLOOR)
                }
                if (view.stopCents != null) {
                    assertTrue("$code stop¢ ${view.stopCents}", view.stopCents!! >= AiLearnBounds.STOP_CENTS_FLOOR)
                }
            }
            val end = controls.settings(code)
            assertEquals(code, AiLearnBounds.UNTIL_FLOOR, end.untilMinute)
        }
        val loosened = PaperSettingTuner.propose(
            ScalpControls(),
            listOf(PaperTape("sf42", 12, 9, 4.0, 0.75, List(12) { 0.30 }, emptyList()))
        )
        assertTrue(loosened.any { it.setting == "contracts" && it.newValue.toInt() > it.oldValue.toIntOrNull().let { n -> n ?: ScalpParamSanity.builtinClip("sf42") } })
        assertTrue(loosened.none { it.setting == "untilMinute" && it.newValue.toInt() < it.oldValue.toInt() })
    }

    private fun applyChange(s: ScalpStrategySettings, ch: PaperSettingTuner.Change): ScalpStrategySettings {
        val n = ch.newValue.toInt()
        return when (ch.setting) {
            "minEntryCents" -> s.copy(minEntryCents = n)
            "maxEntryCents" -> s.copy(maxEntryCents = n)
            "untilMinute" -> s.copy(untilMinute = n)
            "stopLossPercent" -> s.copy(stopLossPercent = n)
            "stopCents" -> s.copy(stopCents = n)
            "contracts" -> s.copy(contracts = n)
            "takeProfitCents" -> s.copy(takeProfitCents = n)
            "edgeCents" -> s.copy(edgeCents = n)
            else -> s
        }
    }
}
