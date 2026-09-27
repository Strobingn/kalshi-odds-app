package com.dirk.kalshiodds.signal.lastminute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastMinuteMathTest {

    @Test
    fun goldenFairPVectorsMatchPythonTo1e4() {
        val cases = listOf(
            Quad(0.0006, 45.0, 0.0004, 5e-05) to 0.998239,
            Quad(-0.0003, 30.0, -0.0002, 4e-05) to 0.020074,
            Quad(0.001, 59.0, 0.0009, 6e-05) to 0.999475,
            Quad(0.0002, 10.0, 0.0005, 3e-05) to 0.999996,
            Quad(-0.0001, 5.0, 0.0001, 5e-05) to 0.797261
        )
        for ((q, expected) in cases) {
            val got = LastMinuteMath.fairP(q.x, q.tau, q.om, q.sig)
            assertEquals("fairP$q", expected, got, 1e-4)
        }
    }

    @Test
    fun sizingAtTenDollarsMatchesPython() {
        val cases = listOf(
            0.03 to (312 to 10.00),
            0.005 to (1869 to 10.00),
            0.25 to (38 to 10.00),
            0.62 to (15 to 9.55)
        )
        for ((p, expected) in cases) {
            val (c, cost) = LastMinuteMath.sizeBet(p, 10.0)
            assertEquals("C at P=$p", expected.first, c)
            assertEquals("cost at P=$p", expected.second, cost, 1e-4)
        }
    }

    @Test
    fun paramsAreLastFoldValues() {
        assertEquals(1.1, LastMinuteConstants.K, 1e-12)
        assertEquals(0.0001, LastMinuteConstants.ETA, 1e-12)
        assertEquals(0.35, LastMinuteConstants.MARGIN_EV_PER_DOLLAR, 1e-12)
        assertEquals(10.0, LastMinuteConstants.MAX_STAKE_USD, 1e-12)
    }

    @Test
    fun perSecondVolNeedsThirtyCompletedMinutes() {
        val closes = (0 until 20).map { 11.0 + it * 0.0001 }
        assertEquals(null, LastMinuteMath.perSecondVol(closes))
        val enough = (0 until 40).map { 11.0 + it * 0.0002 }
        val sig = LastMinuteMath.perSecondVol(enough)
        assertTrue(sig != null && sig > 0.0)
    }

    @Test
    fun evPerDollarUsesAllInCost() {
        val (c, cost) = LastMinuteMath.sizeBet(0.03, 10.0)
        val ev = LastMinuteMath.evPerDollar(c, 0.80, cost)!!
        assertTrue(ev > 20.0)
    }

    private data class Quad(val x: Double, val tau: Double, val om: Double, val sig: Double)
}
