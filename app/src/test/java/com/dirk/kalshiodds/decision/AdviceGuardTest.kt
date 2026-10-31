package com.dirk.kalshiodds.decision

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AdviceGuardTest {
    private val dirk = """
        Working regime: only the time-left <5 min bucket is consistently profitable (27 wins of 34 trades, net +1.13). <=10 min and >10 min windows together lose roughly -211 pts; 'score > 0.65' and 'hybrid-gate' categories deep red. Retraining recommendation: time-left weighting too generous to losing trades (0.06 higher time-left weight than winners) so down-scale/recalibrate; focused retraining that (1) reduces time-left influence for longer horizons, (2) removes or heavily penalizes the hybrid-gate and fib-barrier flags (which show no contribution), (3) reinforces the short-window signal.
    """.trimIndent()

    @Before fun reset() = ModelFilter.resetForTest()
    @After fun clear() = ModelFilter.resetForTest()

    @Test fun dirkAdviceIsProtectedAndKeepsTheScoreDrop() {
        ModelFilter.setMtfAgree(false)
        ModelFilter.setStrongMove(false)
        ModelFilter.setFibMode(HybridQuantGates.FibMode.LOG_ONLY)
        ModelFilter.setThreshold(0.57)
        val applied = ModelFilter.applySessionAdvice(dirk)
        assertEquals(ModelFilter.HIGH_SCORE, applied.scoreCeiling, 0.0)
        assertEquals(ModelFilter.DEFAULT_THRESHOLD, applied.scoreFloor, 0.0)
        assertFalse(applied.lateWindowOnly)
        assertTrue(ModelFilter.hybridGateOn())
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, ModelFilter.hybridPrefs().fibMode)
        assertFalse(ModelFilter.hybridPrefs().mtfAgree)
        assertFalse(ModelFilter.hybridPrefs().strongMove)
        assertEquals(1.0, ModelFilter.featureScaleOf("timeLeft"), 0.0)
        assertTrue(ModelFilter.featurePenalties().isEmpty())
        assertTrue(applied.note.contains("Not applied (protected): down-scale or recalibrate the time-left weight"))
        assertTrue(applied.note.contains("Not applied (protected): remove or penalize the hybrid-gate flag"))
        assertTrue(applied.note.contains("Not applied (protected): remove or penalize the fib-barrier flag"))
        assertTrue(applied.note.contains("Not applied (protected): late-window-only on the shared Hybrid Quant gate"))
        assertTrue(applied.note.contains("Kept score ceiling 0.65"))
    }

    @Test fun undoRestoresTheSnapshotTakenBeforeApply() {
        ModelFilter.setThreshold(0.60)
        ModelFilter.setFibMode(HybridQuantGates.FibMode.LOG_ONLY)
        ModelFilter.applySessionAdvice(dirk)
        assertEquals(0.55, ModelFilter.gatePrefs().scoreFloor, 0.0)
        assertTrue(ModelFilter.undoLastAdvice())
        assertEquals(0.60, ModelFilter.gatePrefs().scoreFloor, 0.0)
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, ModelFilter.hybridPrefs().fibMode)
        assertTrue(ModelFilter.gatePrefs().note.contains("Undid"))
        assertFalse(ModelFilter.hasAdviceUndo())
    }

    @Test fun healRestoresFactoryHybridStateAndPreAdviceWeightsOnce() {
        val prior = DoubleArray(ModelFilter.FEATURES) { 0.2 }
        prior[0] = 0.25
        ModelFilter.installPreAdviceForTest(prior, 0.15)
        ModelFilter.installWeightsForTest(DoubleArray(ModelFilter.FEATURES) { -1.0 }, -0.4, 0)
        ModelFilter.installFeatureScaleForTest("timeLeft", 0.4)
        ModelFilter.installPenaltyForTest("hybrid-gate")
        ModelFilter.installPenaltyForTest("fib_barrier")
        ModelFilter.setHybridGateForTest(false)
        ModelFilter.setFibMode(HybridQuantGates.FibMode.OFF)
        ModelFilter.setMtfAgree(true)
        ModelFilter.setStrongMove(true)
        val dir = File("build/advice-heal").apply { mkdirs() }
        val file = File(dir, "model-filter-runtime.json")
        file.writeText(
            """{"mode":"LOGISTIC","lateWindowOnly":true,"scoreCeiling":0.80,"fibMode":"BLOCK","mtfAgree":true,"strongMove":true,"hybridGate":false}"""
        )
        ModelFilter.bindGateFile(file)
        assertTrue(ModelFilter.hybridGateOn())
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, ModelFilter.hybridPrefs().fibMode)
        assertFalse(ModelFilter.hybridPrefs().mtfAgree)
        assertFalse(ModelFilter.hybridPrefs().strongMove)
        assertFalse(ModelFilter.gatePrefs().lateWindowOnly)
        assertEquals(ModelFilter.HIGH_SCORE, ModelFilter.gatePrefs().scoreCeiling, 0.0)
        assertEquals(1.0, ModelFilter.featureScaleOf("timeLeft"), 0.0)
        assertFalse(ModelFilter.featurePenalties().contains("hybrid-gate"))
        assertFalse(ModelFilter.featurePenalties().contains("fib_barrier"))
        val (w, b) = ModelFilter.weightCopy()
        assertEquals(0.25, w[0], 1e-9)
        assertEquals(0.15, b, 1e-9)
        val saved = JSONObject(file.readText())
        assertTrue(saved.getBoolean("adviceHeal1055"))
        assertEquals("LOGISTIC", saved.getString("mode"))
        ModelFilter.setMtfAgree(true)
        ModelFilter.resetForTest()
        ModelFilter.bindGateFile(file)
        assertTrue(ModelFilter.hybridPrefs().mtfAgree)
        assertFalse(ModelFilter.healAdviceOnce())
    }

    @Test fun healRetrainsFromFactoryWhenNoPreAdviceSnapshotExists() {
        ModelFilter.installWeightsForTest(DoubleArray(ModelFilter.FEATURES) { 3.0 }, 1.0, 0)
        ModelFilter.installFeatureScaleForTest("timeLeft", 0.1)
        assertTrue(ModelFilter.healAdviceOnce())
        val (w, _) = ModelFilter.weightCopy()
        assertEquals(ModelFilter.priorWeights()[0], w[0], 1e-9)
        assertEquals(1.0, ModelFilter.featureScaleOf("timeLeft"), 0.0)
        assertFalse(ModelFilter.healAdviceOnce())
    }

    @Test fun repairControlsRestoresAiTouchedDirkDefaultsOnly() {
        val touched = ScalpStrategySettings(
            minEntryCents = 30,
            maxEntryCents = 40,
            takeProfitCents = 10,
            contracts = 10,
            maxOpenPerSide = 4,
            aiN = 3,
            aiNote = "AI tightened"
        )
        val hand = ScalpStrategySettings(minEntryCents = 20, maxEntryCents = 40, takeProfitCents = 25, aiN = 0, aiNote = "")
        val next = AdviceApply.repairControls(
            ScalpControls(strategySettings = mapOf("sf41" to touched, "sf42" to hand))
        )
        val sf41 = next.settings("sf41")
        assertEquals(PaperFleetDefaults.BUY_MIN, sf41.minEntryCents)
        assertEquals(PaperFleetDefaults.BUY_MAX, sf41.maxEntryCents)
        assertEquals(PaperFleetDefaults.PROFIT_CENTS, sf41.takeProfitCents)
        assertEquals(PaperFleetDefaults.OPEN_AT_ONCE, sf41.maxOpenPerSide)
        assertTrue((sf41.contracts ?: 0) >= PaperClip.MIN)
        val sf42 = next.settings("sf42")
        assertEquals(20, sf42.minEntryCents)
        assertEquals(40, sf42.maxEntryCents)
    }

    @Test fun lateWindowBlocksOutsideFiveMinutesAndHighScores() {
        assertNull(LateWindowScalper.blockReason("BTC", emptySet(), 299.0, 0.40, 0.50, true, "enter"))
        assertTrue(LateWindowScalper.blockReason("BTC", emptySet(), 300.0, 0.40, 0.50, true, "enter")!!.contains("outside late window"))
        assertTrue(LateWindowScalper.blockReason("BTC", emptySet(), 120.0, 0.40, 0.66, true, "enter")!!.contains("high-score"))
        assertTrue(LateWindowScalper.blockReason("BTC", emptySet(), 120.0, 0.10, 0.40, true, "enter")!!.contains("15–55"))
        assertTrue(LateWindowScalper.blockReason("BTC", emptySet(), 120.0, 0.40, 0.40, false, "hybrid gate")!!.contains("hybrid gate"))
        assertTrue(LateWindowScalper.blockReason("ETH", emptySet(), 120.0, 0.40, 0.40, true, "enter")!!.contains("ETH"))
        assertNull(LateWindowScalper.blockReason("ETH", setOf("ETH"), 120.0, 0.40, 0.40, true, "enter"))
        assertEquals(setOf("BTC", "SOL"), LateWindowScalper.coins(setOf("sol")))
    }

    @Test fun lateWindowMinutesStayInRangeAndAiCannotMoveThem() {
        LateWindowScalper.setMinutes(5)
        val note = LateWindowScalper.applyAiMinutes(1)
        assertEquals(5, LateWindowScalper.minutes())
        assertTrue(note.startsWith("Not applied (protected):"))
        LateWindowScalper.setMinutes(9)
        assertEquals(5, LateWindowScalper.minutes())
        LateWindowScalper.setMinutes(1)
        assertEquals(1, LateWindowScalper.minutes())
        assertTrue(LateWindowScalper.inWindow(59.0, 1))
        assertFalse(LateWindowScalper.inWindow(60.0, 1))
        assertEquals("needs 200 trades · 27/200", LateWindowScalper.promoteLabel(27))
        assertEquals(27f / 200f, LateWindowScalper.progress(27), 1e-6f)
        assertTrue(LateWindowScalper.promoteLabel(200).startsWith("Ready to promote"))
        assertEquals(
            "<5 min 1/3 +1.13",
            LateWindowScalper.regimeLine(listOf("<5 min" to 2.0, "<5 min" to -0.5, "<5 min" to -0.37))
        )
    }
}
