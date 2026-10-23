package com.dirk.kalshiodds.signal.scalper.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class FleetMathTest {

    @Test
    fun distributions() {
        assertEquals(0.5, FleetMath.normCdf(0.0), 1e-7)
        assertEquals(0.975002, FleetMath.normCdf(1.96), 1e-6)
        assertEquals(1.96, FleetMath.normInv(0.975002), 1e-5)
        // Student-t with 4 degrees of freedom: P(T ≤ 2) = 0.941942.
        assertEquals(0.941942, FleetMath.studentTCdf(2.0, 4.0), 1e-5)
        assertEquals(0.5, FleetMath.studentTCdf(0.0, 4.2), 1e-12)
        // Fat tails: at 3σ the unit-variance t(4.2) puts several times the normal's mass beyond.
        val t = FleetMath.studentTUnitCdf(-3.0, 4.2)
        val n = FleetMath.normCdf(-3.0)
        assertTrue("t $t vs normal $n", t > 2 * n)
        // Density integrates to the CDF difference.
        var area = 0.0
        val h = 0.001
        var x = -1.0
        while (x < 1.0) { area += FleetMath.studentTPdf(x + h / 2, 4.2) * h; x += h }
        assertEquals(FleetMath.studentTCdf(1.0, 4.2) - FleetMath.studentTCdf(-1.0, 4.2), area, 1e-5)
    }

    @Test
    fun gateAndKelly() {
        assertTrue(FleetMath.passesGate(0.62, 0.60, 0.0))
        assertFalse(FleetMath.passesGate(0.62, 0.60, 0.017))
        assertEquals(0, FleetMath.kellyContracts(0.55, 0.60, 0.0, 250.0))
        // p 0.70 at 0.60: b = 0.4/0.6, f* = (0.7·(b+1) − 1)/b = 0.25; quarter → 6.25% of $250 = $15.6 → 26 contracts.
        assertEquals(26, FleetMath.kellyContracts(0.70, 0.60, 0.0, 250.0))
        // No upper limit: Kelly alone sizes it.
        assertTrue(FleetMath.kellyContracts(0.999, 0.10, 0.0, 1e9) > 1_000_000)
        // Kalshi's fee at 50¢ is 1.75¢ a contract.
        assertEquals(0.0175, FleetMath.takerFeePer(100, 0.50), 1e-9)
        assertEquals(0.53, FleetMath.impliedYesAsk(0.47), 1e-12)
        assertEquals((10 * 0.40 + 5 * 0.41) / 15, FleetMath.walkBook(listOf(0.40 to 10.0, 0.41 to 20.0), 15.0)!!, 1e-12)
        assertNull(FleetMath.walkBook(listOf(0.40 to 10.0), 15.0))
    }

    @Test
    fun lockedInMatchesASimulation() {
        val strike = 100_000.0
        val prints = DoubleArray(40) { 100_000.0 + 3.0 }
        val last = 100_003.0
        val sigma = 2.0
        val p = FleetMath.lockedInProbYes(strike, prints, last, sigma)!!
        val rnd = Random(1)
        var wins = 0
        val n = 40_000
        repeat(n) {
            var x = last
            var sum = prints.sum()
            repeat(20) { x += sigma * gaussian(rnd); sum += x }
            if (sum / 60 >= strike) wins++
        }
        assertEquals(wins.toDouble() / n, p, 0.01)
        assertEquals(1.0, FleetMath.lockedInProbYes(strike, DoubleArray(60) { strike + 1 }, strike, 1.0)!!, 0.0)
        assertEquals((60 * strike - prints.sum()) / 20, FleetMath.requiredAverage(strike, prints), 1e-6)
    }

    private fun gaussian(r: Random): Double {
        val u = r.nextDouble().coerceAtLeast(1e-12)
        val v = r.nextDouble()
        return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    @Test
    fun finalMinuteHelpers() {
        assertEquals(10.0 / 60 * 30, FleetMath.spikeImpact(30.0, 50), 1e-12)
        val (b, se) = FleetMath.olsSlope(DoubleArray(30) { 5.0 + 2.0 * it })!!
        assertEquals(2.0, b, 1e-12)
        assertEquals(0.0, se, 1e-9)
        // Need > 6σ√(60−k) to turn: 50 prints far above the strike are locked.
        assertTrue(FleetMath.tailLocked(100.0, DoubleArray(50) { 200.0 }, 200.0, 1.0))
        assertFalse(FleetMath.tailLocked(100.0, DoubleArray(50) { 100.1 }, 100.1, 1.0))
        assertFalse(FleetMath.tailLocked(100.0, DoubleArray(40) { 200.0 }, 200.0, 1.0))
    }

    @Test
    fun volatilityHelpers() {
        val spot = 101.0
        val k = 100.0
        val sigma = 0.02
        val tau = 0.5
        val mid = FleetMath.normCdf(ln(spot / k) / (sigma * sqrt(tau)))
        assertEquals(sigma, FleetMath.impliedVol15m(mid, spot, k, tau)!!, 1e-6)
        assertNull(FleetMath.impliedVol15m(0.5, spot, k, tau))
        val r = DoubleArray(500) { if (it % 2 == 0) 0.01 else -0.01 }
        assertEquals(0.01, FleetMath.ewVol(r)!!, 1e-9)
        val a = DoubleArray(40) { it.toDouble() }
        assertEquals(1.0, FleetMath.correlation(a, DoubleArray(40) { 2.0 * it + 1 })!!, 1e-12)
        assertEquals(-1.0, FleetMath.correlation(a, DoubleArray(40) { -it.toDouble() })!!, 1e-12)
        assertEquals(0.9 * 2.0 * 0.01, FleetMath.conditionalReturn(0.9, 0.02, 0.01, 0.01), 1e-12)
    }

    @Test
    fun bookHelpers() {
        val (r, d) = FleetMath.avellanedaStoikov(0.50, 0.0, 0.2, 0.002, 600.0, 1.5)
        assertEquals(0.50, r, 1e-12)
        assertTrue(d >= 0.02 && d <= 0.10)
        val (rLong, _) = FleetMath.avellanedaStoikov(0.50, 2.0, 0.2, 0.002, 600.0, 1.5)
        assertTrue("long inventory skews the reservation down", rLong < 0.50)
        assertEquals(0.47 to 0.47 + 0.02 + 0.015, FleetMath.ladderRung(0.50, 3).let { (it.first * 1e6).toLong() / 1e6 to (it.second * 1e6).toLong() / 1e6 })
        assertEquals(1.0, FleetMath.obi(listOf(0.5 to 100.0), emptyList())!!, 1e-12)
        assertEquals(0.0, FleetMath.obi(listOf(0.5 to 100.0), listOf(0.4 to 100.0))!!, 1e-12)
        assertEquals(0.55, FleetMath.microPrice(0.50, 300.0, 0.60, 300.0), 1e-12)
        assertTrue(FleetMath.microPrice(0.50, 900.0, 0.60, 100.0) > 0.55)
        assertNull(FleetMath.vpin(List(10) { 250.0 to 250.0 }))
        assertEquals(1.0, FleetMath.vpin(List(20) { 500.0 to 0.0 })!!, 1e-12)
        assertEquals(0.0, FleetMath.vpin(List(20) { 250.0 to 250.0 })!!, 1e-12)
    }

    @Test
    fun venueHelpers() {
        val book = listOf(100.0 to 1.0, 99.0 to 5.0) to listOf(101.0 to 1.0, 102.0 to 5.0)
        val nc = FleetMath.cfNowcast(listOf(book))!!
        assertTrue(nc in 100.0..101.0)
        // A book leaning deeper on the ask side pulls the index up the curve, but stays between the touch prices.
        assertNotNull(FleetMath.cfNowcast(listOf(book, listOf(100.5 to 2.0) to listOf(100.6 to 2.0))))
        assertEquals(1.75 / 100, FleetMath.polymarketFeePer(0.5, 0.07), 1e-12)
        val d = FleetMath.binaryDelta(100_000.0, 100_000.0, 0.002, 0.5, 4.2)
        // Numerical check of dP/dS.
        val f = { s: Double -> FleetMath.studentTCdf(FleetMath.displacement(s, 100_000.0, 0.002, 0.5), 4.2) }
        assertEquals((f(100_000.5) - f(99_999.5)) / 1.0, d, d * 1e-3)
        assertEquals(0.02, FleetMath.pinSpread(0.0, 100_000.0, 1e-5, 1.0), 1e-12)
    }

    @Test
    fun quantiles() {
        val q = DoubleArray(9) { 100.0 + 10 * (it - 4) }
        assertEquals(0.5, FleetMath.probAboveFromQuantiles(q, 100.0)!!, 1e-12)
        // 85 sits halfway between q30 (80) and q40 (90): 35% below, 65% above.
        assertEquals(0.65, FleetMath.probAboveFromQuantiles(q, 85.0)!!, 1e-12)
        assertTrue(FleetMath.probAboveFromQuantiles(q, 200.0)!! < 0.01)
        assertTrue(FleetMath.probAboveFromQuantiles(q, 0.0)!! > 0.99)
        // Extra variance pulls toward a coin flip.
        assertTrue(FleetMath.probAboveFromQuantiles(q, 130.0, extraVar = 10_000.0)!! > FleetMath.probAboveFromQuantiles(q, 130.0)!!)
    }
}
