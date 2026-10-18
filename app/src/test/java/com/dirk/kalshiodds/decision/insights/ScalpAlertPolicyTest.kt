package com.dirk.kalshiodds.decision.insights

import org.junit.Assert.*
import org.junit.Test

class ScalpAlertPolicyTest {
    private val on = AlertToggles()
    private fun streak(p: ScalpAlertPolicy, nets: List<Double>) = p.onSettled("fg", series("t", nets), on).filter { it.kind == AlertKind.HOT_STREAK }

    @Test fun fiveWinsFireOnceAndSixthDoesNot() {
        val p = ScalpAlertPolicy()
        assertEquals(1, streak(p, List(5) { 1.0 }).size)
        assertEquals(0, streak(p, List(5) { 1.0 }).size) // same event again
        assertEquals(0, streak(p, listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)).size) // streak continues — t1 starts window but streak began at t0
    }
    @Test fun fourWinsNoAlert() { assertTrue(streak(ScalpAlertPolicy(), List(4) { 1.0 }).isEmpty()) }
    @Test fun newStreakAfterLossFiresAgain() {
        val p = ScalpAlertPolicy()
        assertEquals(1, streak(p, List(5) { 1.0 }).size)
        assertEquals(1, streak(p, List(5) { 1.0 } + listOf(-1.0) + List(5) { 1.0 }).size)
    }
    @Test fun bigLossThreshold() {
        val p = ScalpAlertPolicy()
        assertTrue(p.onSettled("fg", listOf(settled("a", -5.0, 1)), on).none { it.kind == AlertKind.BIG_LOSS })
        val a = p.onSettled("fg", listOf(settled("b", -5.01, 2)), on)
        assertEquals(1, a.count { it.kind == AlertKind.BIG_LOSS }); assertEquals("loss:b", a.first().key)
        assertTrue(p.onSettled("fg", listOf(settled("b", -5.01, 2)), on).isEmpty())
    }
    @Test fun disabledToggleDoesNotConsumeKey() {
        val p = ScalpAlertPolicy()
        assertTrue(p.onSettled("fg", listOf(settled("b", -9.0, 2)), on.copy(bigLoss = false)).isEmpty())
        assertEquals(1, p.onSettled("fg", listOf(settled("b", -9.0, 2)), on).size)
        assertTrue(p.onSettled("fg", series("t", List(5) { 1.0 }), on.copy(hotStreak = false)).isEmpty())
    }
    @Test fun strongAiThresholdsAndDedupe() {
        val p = ScalpAlertPolicy()
        assertNull(p.onAiCall("w1", "UP", 0.69, on))
        assertNotNull(p.onAiCall("w2", "up", 0.70, on))
        assertNull(p.onAiCall("w2", "UP", 0.90, on))
        assertNotNull(p.onAiCall("w3", "DOWN", 72.0, on))
        assertNull(p.onAiCall("w4", "FLAT", 0.99, on))
        assertNull(p.onAiCall("w5", "UP", 0.99, on.copy(strongAi = false)))
        assertNotNull(p.onAiCall("w5", "UP", 0.99, on))
    }
    @Test fun evictsOldestKeys() {
        val p = ScalpAlertPolicy(maxKeys = 2)
        assertTrue(p.firstTime("a")); assertTrue(p.firstTime("b")); assertTrue(p.firstTime("c"))
        assertTrue(p.firstTime("a")); assertFalse(p.firstTime("c"))
    }
}
