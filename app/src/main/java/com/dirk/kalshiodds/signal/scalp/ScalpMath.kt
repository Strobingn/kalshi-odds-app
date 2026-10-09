package com.dirk.kalshiodds.signal.scalp

import kotlin.math.abs

/**
 * Pure rolling-window feature extractor for the scalper. No Android, no
 * coroutines, no I/O — feed mid prices (cents 1..99) with wall-clock
 * timestamps and read [snapshot]. Fully deterministic and unit-testable.
 *
 * Features:
 * - short EMA (fast) and long EMA (slow) of mid, in cents
 * - velocity: least-squares slope over the newest [velocityPoints] samples,
 *   in cents/second
 * - acceleration: recent-half slope minus older-half slope of that same
 *   velocity window (cents/second²), mirroring
 *   [com.dirk.kalshiodds.signal.engine.LocalOrderBook.accelerationPerSec]
 * - rolling min / max of mid over the trailing [windowSeconds] window
 * - dropFromShortEma / dropFromWindowMax, both positive when price has
 *   dipped below the trend
 *
 * The docs note nothing beats the Kalshi mid — all signals here are
 * explicit price-shape rules, not ML.
 */
class ScalpMath(
    val windowSeconds: Int = 60,
    private val shortEmaAlpha: Double = 0.35,
    private val longEmaAlpha: Double = 0.08,
    private val velocityPoints: Int = 6
) {

    /** Immutable point-in-time features. Nulls mean "not enough data yet". */
    data class ScalpFeatures(
        val lastMidPp: Double?,
        val shortEmaPp: Double?,
        val longEmaPp: Double?,
        /** Slope of the newest samples, cents per second. */
        val velocityPpPerSec: Double?,
        /** Recent-half slope minus older-half slope, cents per second². */
        val accelerationPpPerSec2: Double?,
        val windowMinPp: Double?,
        val windowMaxPp: Double?,
        /** shortEma − lastMid. Positive = price below the fast trend (a dip). */
        val dropFromShortEmaPp: Double?,
        /** Deepest (emaAtSample − mid) within the window — how far price ran
         * below the fast trend during the dip, even after the EMA catches up. */
        val maxDropFromShortEmaPp: Double?,
        /** windowMax − lastMid. Positive = price off the window high. */
        val dropFromWindowMaxPp: Double?,
        val sampleCount: Int,
        val windowSpanMs: Long
    )

    private data class Sample(val midPp: Double, val nowMs: Long)

    private val samples = ArrayDeque<Sample>()
    private val emaGaps = ArrayDeque<Double>()
    private var shortEma: Double? = null
    private var longEma: Double? = null

    /** Feed one mid print. Prices outside 1..99¢ and non-finite are ignored. */
    fun onPrice(midPp: Double, nowMs: Long) {
        if (!midPp.isFinite() || midPp < 1.0 || midPp > 99.0) return
        val prevEma = shortEma
        val a = shortEma
        shortEma = if (a == null) midPp else shortEmaAlpha * midPp + (1.0 - shortEmaAlpha) * a
        val b = longEma
        longEma = if (b == null) midPp else longEmaAlpha * midPp + (1.0 - longEmaAlpha) * b
        // Gap vs the fast trend *before* this print — the dip this tick made.
        emaGaps.addLast((prevEma ?: midPp) - midPp)
        samples.addLast(Sample(midPp, nowMs))
        prune(nowMs)
    }

    fun reset() {
        samples.clear()
        emaGaps.clear()
        shortEma = null
        longEma = null
    }

    fun snapshot(nowMs: Long? = null): ScalpFeatures {
        nowMs?.let { prune(it) }
        val pts = samples.toList()
        val mids = pts.map { it.midPp }
        val times = pts.map { it.nowMs }
        val vel = velocity(mids, times, velocityPoints)
        val accel = acceleration(mids, times, velocityPoints)
        val last = pts.lastOrNull()?.midPp
        val emaS = shortEma
        val emaL = longEma
        val span = if (pts.size >= 2) pts.last().nowMs - pts.first().nowMs else 0L
        return ScalpFeatures(
            lastMidPp = last,
            shortEmaPp = emaS,
            longEmaPp = emaL,
            velocityPpPerSec = vel,
            accelerationPpPerSec2 = accel,
            windowMinPp = mids.minOrNull(),
            windowMaxPp = mids.maxOrNull(),
            dropFromShortEmaPp = if (emaS != null && last != null) emaS - last else null,
            maxDropFromShortEmaPp = emaGaps.maxOrNull(),
            dropFromWindowMaxPp = if (last != null) (mids.maxOrNull() ?: last) - last else null,
            sampleCount = pts.size,
            windowSpanMs = span
        )
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowSeconds * 1000L
        while (samples.isNotEmpty() && samples.first().nowMs < cutoff) {
            samples.removeFirst()
            emaGaps.removeFirst()
        }
    }

    companion object {
        /**
         * Least-squares slope (cents/second) over the newest [points]
         * samples. Null when fewer than 2 samples or the span is < 1 ms.
         */
        fun velocity(midsNewestLast: List<Double>, timesMs: List<Long>, points: Int = 6): Double? {
            if (midsNewestLast.size != timesMs.size || midsNewestLast.size < 2) return null
            val n = minOf(points.coerceAtLeast(2), midsNewestLast.size)
            val y = midsNewestLast.takeLast(n)
            val t = timesMs.takeLast(n)
            val t0 = t.first().toDouble()
            val xs = t.map { (it.toDouble() - t0) / 1000.0 }
            val xMean = xs.average()
            val yMean = y.average()
            var num = 0.0
            var den = 0.0
            for (i in xs.indices) {
                num += (xs[i] - xMean) * (y[i] - yMean)
                den += (xs[i] - xMean) * (xs[i] - xMean)
            }
            if (den < 1e-12) return null
            return num / den
        }

        /**
         * Short acceleration: slope of the recent half of the newest [points]
         * samples minus the slope of the older half (cents/second²). Positive
         * = the fall is decelerating / the bounce is starting.
         */
        fun acceleration(midsNewestLast: List<Double>, timesMs: List<Long>, points: Int = 6): Double? {
            if (midsNewestLast.size != timesMs.size || midsNewestLast.size < 4) return null
            val n = minOf(points.coerceAtLeast(4), midsNewestLast.size)
            val y = midsNewestLast.takeLast(n)
            val t = timesMs.takeLast(n)
            val half = n / 2
            if (half < 2 || n - half < 2) return null
            val older = velocity(y.subList(0, half), t.subList(0, half), half) ?: return null
            val recent = velocity(y.subList(half, n), t.subList(half, n), n - half) ?: return null
            return recent - older
        }

        /** |v| shrinking between two consecutive velocity readings. */
        fun magnitudeShrinking(prevPpPerSec: Double?, nowPpPerSec: Double?): Boolean {
            val p = prevPpPerSec ?: return false
            val c = nowPpPerSec ?: return false
            return abs(c) < abs(p) - 1e-9
        }
    }
}
