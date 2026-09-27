package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

class SpotTapeTest {

    private val t0 = 1_700_000_000_000L

    /** One print mid-bin for each 10 s bin, log-returns [rets] between closes. */
    private fun SpotTape.feedBins(startBin: Long, p0: Double, rets: List<Double>): Pair<Long, Double> {
        var p = p0
        var bin = startBin
        add(bin * 10_000L + 5_000L, p)
        for (r in rets) {
            p *= exp(r)
            bin += 1
            add(bin * 10_000L + 5_000L, p)
        }
        return bin to p
    }

    private fun alternating(r: Double, n: Int) = List(n) { if (it % 2 == 0) r else -r }

    @Test
    fun ringBufferIsBoundedAndKeepsOnlyTheWindow() {
        val tape = SpotTape()
        var t = t0
        // 30 minutes at 10 prints/s: 18,000 prints.
        repeat(18_000) { i ->
            assertTrue(tape.add(t, 100_000.0 + (i % 50)))
            t += 100L
        }
        val last = tape.lastTimeMs!!
        assertTrue(tape.sampleCount <= tape.capacity)
        // ~1 sample per second over 20 minutes.
        assertTrue("samples=${tape.sampleCount}", tape.sampleCount in 1_150..1_202)
        assertTrue(tape.oldestTimeMs!! >= last - SpotTape.WINDOW_MS)
        assertEquals(100_000.0 + (17_999 % 50), tape.lastPrice!!, 1e-9)
    }

    @Test
    fun returnsOverOneAndFiveMinutesFromTheBuffer() {
        val tape = SpotTape()
        // One print a second, +$1/s from 60,000.
        for (s in 0..600) tape.add(t0 + s * 1_000L, 60_000.0 + s)
        val now = t0 + 600_000L
        val r1 = tape.returnOver(60_000L, now)!!
        val r5 = tape.returnOver(300_000L, now)!!
        assertEquals(60_600.0 / 60_540.0 - 1.0, r1, 1e-12)
        assertEquals(60_600.0 / 60_300.0 - 1.0, r5, 1e-12)
        // "now" a little after the last print still uses the last print.
        val later = tape.returnOver(60_000L, now + 2_000L)!!
        assertEquals(60_600.0 / 60_542.0 - 1.0, later, 1e-12)
    }

    @Test
    fun returnIsNullUntilTheBufferReachesBack() {
        val tape = SpotTape()
        for (s in 0..120) tape.add(t0 + s * 1_000L, 100.0 + s * 0.01)
        val now = t0 + 120_000L
        assertNotNull(tape.returnOver(60_000L, now))
        assertNull("only 2 minutes of prints", tape.returnOver(300_000L, now))
    }

    @Test
    fun returnIsNullWhenTheBasePrintIsFromBeforeAnOutage() {
        val tape = SpotTape()
        for (s in 0..30) tape.add(t0 + s * 1_000L, 100.0)
        // 90 s outage, then prints resume.
        for (s in 120..140) tape.add(t0 + s * 1_000L, 101.0)
        // 1m target (t0+80s) falls in the gap; nearest earlier print is 50 s older.
        assertNull(tape.returnOver(60_000L, t0 + 140_000L))
    }

    @Test
    fun ewmaOfConstantSizeReturnsIsExact() {
        val tape = SpotTape()
        val r = 0.0002 // 2 bp per 10 s
        tape.feedBins(t0 / 10_000L, 50_000.0, alternating(r, 200))
        assertEquals(r, tape.binStd()!!, 1e-9)
        assertEquals(r * sqrt(6.0), tape.barStd1m()!!, 1e-9)
    }

    @Test
    fun barStd1mAnnualizesLikeTheRestOfTheApp() {
        val tape = SpotTape()
        val r = 0.0003
        tape.feedBins(t0 / 10_000L, 3_000.0, alternating(r, 100))
        // ScoringEngine: sigmaAnnual = realizedVol15m × √(SECONDS_PER_YEAR / 60).
        val viaApp = tape.barStd1m()!! * sqrt(DigitalOptionFairValue.SECONDS_PER_YEAR / 60.0)
        val direct = r * sqrt(DigitalOptionFairValue.SECONDS_PER_YEAR / 10.0)
        assertEquals(direct, viaApp, 1e-9)
    }

    @Test
    fun sigmaNeedsMinReturnsBeforeItIsExposed() {
        val tape = SpotTape()
        // Prints in bins 0..30 → 29 completed returns.
        tape.feedBins(t0 / 10_000L, 100.0, alternating(0.001, SpotTape.MIN_RETURNS))
        assertEquals(SpotTape.MIN_RETURNS - 1, tape.returnCount)
        assertNull(tape.binStd())
        assertNull(tape.barStd1m())
        val (bin, p) = tape.feedBins(t0 / 10_000L + SpotTape.MIN_RETURNS + 5, 100.0, emptyList())
        assertTrue(bin > 0 && p > 0)
        assertNotNull(tape.binStd())
    }

    @Test
    fun ewmaHalfLifeWeighsTheLastThirtyMinutesAtOneHalf() {
        val tape = SpotTape()
        val r1 = 0.0001
        val r2 = 0.0004
        val (endBin, endP) = tape.feedBins(t0 / 10_000L, 20_000.0, alternating(r1, 2_000))
        // Exactly one half-life (180 bins of 10 s) of the new regime.
        var p = endP
        var bin = endBin
        repeat(180) { i ->
            p *= exp(if (i % 2 == 0) r2 else -r2)
            bin += 1
            tape.add(bin * 10_000L + 5_000L, p)
        }
        // Close the last new-regime bin.
        tape.add((bin + 1) * 10_000L + 5_000L, p)
        val expected = 0.5 * r2 * r2 + 0.5 * r1 * r1
        val got = tape.binStd()!!.let { it * it }
        assertEquals(expected, got, expected * 0.01)
    }

    @Test
    fun quietBinsCountAsZeroReturns() {
        val a = SpotTape()
        val b = SpotTape()
        val r = 0.0005
        a.feedBins(0L, 100.0, alternating(r, 60))
        b.feedBins(0L, 100.0, alternating(r, 60))
        // a: one print, then 5 empty bins, then a flat print; b: 6 flat prints.
        val last = a.lastPrice!!
        a.add(66L * 10_000L + 5_000L, last)
        a.add(67L * 10_000L + 5_000L, last)
        for (bin in 61L..67L) b.add(bin * 10_000L + 5_000L, last)
        assertEquals(b.returnCount, a.returnCount)
        assertEquals(b.binStd()!!, a.binStd()!!, 1e-12)
    }

    @Test
    fun badTickIsRejectedAndDoesNotMoveSigma() {
        val tape = SpotTape()
        tape.feedBins(0L, 100.0, alternating(0.0002, 60))
        val before = tape.binStd()!!
        val lastT = tape.lastTimeMs!!
        assertFalse(tape.add(lastT + 1_000L, tape.lastPrice!! * 1.5))
        assertFalse(tape.add(lastT + 1_000L, Double.NaN))
        assertFalse(tape.add(lastT + 1_000L, 0.0))
        assertFalse(tape.add(lastT + 1_000L, -5.0))
        assertEquals(before, tape.binStd()!!, 0.0)
        assertEquals(lastT, tape.lastTimeMs)
    }

    @Test
    fun longGapRestartsTheChainWithoutAJumpReturn() {
        val tape = SpotTape()
        val (bin, p) = tape.feedBins(0L, 100.0, alternating(0.0002, 60))
        val before = tape.binStd()!!
        val count = tape.returnCount
        // 5-minute outage, then +3% (a real move while we were away).
        val resume = (bin + 30) * 10_000L + 5_000L
        tape.add(resume, p * 1.03)
        tape.add(resume + 10_000L, p * 1.03)
        tape.add(resume + 20_000L, p * 1.03)
        assertEquals(before * before, tape.binStd()!!.let { it * it }, before * before * 0.05)
        assertTrue("no return spans the gap", tape.returnCount <= count + 2)
    }

    @Test
    fun breakChainDropsTheReturnAcrossAReconnect() {
        val tape = SpotTape()
        val (bin, p) = tape.feedBins(0L, 100.0, alternating(0.0002, 60))
        val count = tape.returnCount
        tape.breakChain()
        tape.add((bin + 1) * 10_000L + 5_000L, p * 1.01)
        tape.add((bin + 2) * 10_000L + 5_000L, p * 1.01)
        // The +1% step is never a return; the first new bin only re-anchors.
        assertEquals(count, tape.returnCount)
    }

    @Test
    fun veryLongGapClearsTheTape() {
        val tape = SpotTape()
        val (bin, p) = tape.feedBins(0L, 100.0, alternating(0.0002, 60))
        assertNotNull(tape.binStd())
        tape.add(bin * 10_000L + SpotTape.RESET_AFTER_MS + 60_000L, p)
        assertEquals(1, tape.sampleCount)
        assertEquals(0, tape.returnCount)
        assertNull(tape.binStd())
    }

    @Test
    fun clockSteppingBackwardsKeepsTheBufferSorted() {
        val tape = SpotTape()
        tape.add(t0 + 10_000L, 100.0)
        tape.add(t0 + 5_000L, 100.5)
        assertEquals(t0 + 10_000L, tape.lastTimeMs)
        assertEquals(100.5, tape.lastPrice!!, 0.0)
        tape.add(t0 + 70_000L, 101.0)
        assertEquals(101.0 / 100.5 - 1.0, tape.returnOver(60_000L, t0 + 70_000L)!!, 1e-12)
    }
}
