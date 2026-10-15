package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V-shaped synthetic series: falls from 50¢ to 42¢ (accelerating), then
 * flattens at the bottom. Velocity must be negative on the way down and
 * near zero at the bottom; acceleration must flip positive at the turn.
 */
class ScalpMathTest {

    private fun vSeries(): Pair<List<Double>, List<Long>> {
        val mids = mutableListOf<Double>()
        val times = mutableListOf<Long>()
        var t = 0L
        // 20 steps down, each drop growing: 0.5, 0.6, 0.7 ... cents per step (accelerating fall)
        var price = 50.0
        repeat(20) { i ->
            mids += price
            times += t
            price -= 0.5 + 0.05 * i
            t += 1000L
        }
        // 6 steps at the bottom: 4 flat, then a faint uptick (stabilization
        // turning into a bounce — the recent-half slope exceeds the older half).
        repeat(4) {
            mids += price
            times += t
            t += 1000L
        }
        repeat(2) {
            price += 0.03
            mids += price
            times += t
            t += 1000L
        }
        return mids to times
    }

    @Test
    fun velocityNegativeWhileFallingAndFlatAtBottom() {
        val (mids, times) = vSeries()
        // During the accelerating decline (last 6 of the falling leg).
        val fallingVel = ScalpMath.velocity(mids.subList(10, 20), times.subList(10, 20))
        assertNotNull(fallingVel)
        assertTrue("falling velocity $fallingVel", fallingVel!! < 0.0)

        // At the bottom (last 6 points, all flat): slope ≈ 0.
        val flatVel = ScalpMath.velocity(mids.takeLast(6), times.takeLast(6))
        assertNotNull(flatVel)
        assertTrue("flat velocity $flatVel", kotlin.math.abs(flatVel!!) < 0.05)
    }

    @Test
    fun accelerationNegativeInAcceleratingFallAndPositiveAtTheTurn() {
        val (mids, times) = vSeries()
        // Accelerating fall: recent-half slope more negative than older half.
        val fallingAccel = ScalpMath.acceleration(mids.subList(10, 20), times.subList(10, 20))
        assertNotNull(fallingAccel)
        assertTrue("falling acceleration $fallingAccel", fallingAccel!! < 0.0)

        // At the V bottom the recent half is flat vs the older half still falling.
        val bottomAccel = ScalpMath.acceleration(mids.takeLast(6), times.takeLast(6))
        assertNotNull(bottomAccel)
        assertTrue("bottom acceleration $bottomAccel", bottomAccel!! > 0.0)
    }

    @Test
    fun emasWindowMinMaxAndDropsOnSyntheticV() {
        val math = ScalpMath(windowSeconds = 60)
        val (mids, times) = vSeries()
        for (i in mids.indices) math.onPrice(mids[i], times[i])

        val f = math.snapshot()
        assertEquals(26, f.sampleCount)
        // Total drop = Σ(0.5 + 0.05i) over 20 steps ≈ 19.5¢.
        assertEquals(30.5, f.windowMinPp!!, 0.5)
        assertEquals(50.0, f.windowMaxPp!!, 0.5)
        // Price at the bottom is below both EMAs and below the window max.
        assertTrue(f.shortEmaPp!! > f.lastMidPp!!)
        assertTrue(f.longEmaPp!! > f.lastMidPp!!)
        assertTrue(f.dropFromWindowMaxPp!! >= 7.0)
        assertTrue(f.dropFromShortEmaPp!! > 0.0)
        // The dip ran well below the fast trend at its deepest point.
        assertTrue(f.maxDropFromShortEmaPp!! >= 2.0)
    }

    @Test
    fun windowPrunesOldSamples() {
        val math = ScalpMath(windowSeconds = 10)
        // 20 samples 1s apart starting at t=0 → only the last 10 survive at t=20_000.
        for (i in 0 until 20) math.onPrice(50.0 - i, i * 1000L)
        val f = math.snapshot(nowMs = 20_000L)
        assertEquals(10, f.sampleCount)
    }

    @Test
    fun rejectsOutOfRangePrices() {
        val math = ScalpMath()
        math.onPrice(0.0, 0L)
        math.onPrice(100.0, 0L)
        math.onPrice(Double.NaN, 0L)
        assertEquals(0, math.snapshot().sampleCount)
        math.onPrice(50.0, 0L)
        assertEquals(1, math.snapshot().sampleCount)
    }

    @Test
    fun magnitudeShrinkingDetectsDeceleration() {
        assertTrue(ScalpMath.magnitudeShrinking(-1.0, -0.4))
        assertTrue(!ScalpMath.magnitudeShrinking(-0.4, -1.0))
        assertTrue(!ScalpMath.magnitudeShrinking(null, -0.4))
    }

    @Test
    fun resetClearsState() {
        val math = ScalpMath()
        math.onPrice(50.0, 0L)
        math.reset()
        assertNull(math.snapshot().lastMidPp)
        assertEquals(0, math.snapshot().sampleCount)
    }
}
