package com.dirk.kalshiodds.chart

/**
 * One print of the YES (UP) and NO (DOWN) best bids, in cents (0–100).
 * Used by the per-market chart and persisted snapshots.
 */
data class BidPoint(
    val tMs: Long,
    val upBidCents: Float?,
    val downBidCents: Float?,
    val spotUsd: Double? = null
)

data class BidSeries(
    val ticker: String,
    val points: List<BidPoint>,
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val strikeUsd: Double? = null,
    val spotUsd: Double? = null
)

/**
 * Memory-safe downsample. Time-buckets then keeps min/max in each bucket
 * so spikes survive. Never allocates more than [maxPoints] + a small scratch.
 */
object ChartDownsampler {
    // 1.20.7: finer cards (a 15-minute window in ~6 s steps instead of ~14 s).
    const val CARD_POINTS = 150
    const val DETAIL_POINTS = 300
    const val MAX_RAW_POINTS = 720

    fun downsample(points: List<BidPoint>, maxPoints: Int = CARD_POINTS): List<BidPoint> {
        if (points.size <= maxPoints) return points
        if (maxPoints <= 2) return listOfNotNull(points.firstOrNull(), points.lastOrNull())
        val sorted = if (isSorted(points)) points else points.sortedBy { it.tMs }
        val first = sorted.first().tMs
        val last = sorted.last().tMs
        val span = (last - first).coerceAtLeast(1L)
        // Per bucket: first, last, and the low and high of the UP bid (DOWN when there is no UP), so a one-sample spike
        // survives in either direction. Before, only the first 3 samples and the newest were kept and only a low was
        // picked, so upward spikes were dropped and the chart leaned down.
        val firstOf = arrayOfNulls<BidPoint>(maxPoints)
        val lastOf = arrayOfNulls<BidPoint>(maxPoints)
        val lowOf = arrayOfNulls<BidPoint>(maxPoints)
        val highOf = arrayOfNulls<BidPoint>(maxPoints)
        fun level(p: BidPoint): Float? = p.upBidCents ?: p.downBidCents
        for (p in sorted) {
            val idx = (((p.tMs - first) * (maxPoints - 1L)) / span).toInt().coerceIn(0, maxPoints - 1)
            if (firstOf[idx] == null) firstOf[idx] = p
            lastOf[idx] = p
            val v = level(p) ?: continue
            if (lowOf[idx]?.let { level(it)!! > v } != false) lowOf[idx] = p
            if (highOf[idx]?.let { level(it)!! < v } != false) highOf[idx] = p
        }
        val out = ArrayList<BidPoint>(maxPoints)
        for (i in 0 until maxPoints) {
            val f = firstOf[i] ?: continue
            val picks = listOfNotNull(f, lowOf[i], highOf[i], lastOf[i]).distinct().sortedBy { it.tMs }
            out.addAll(picks)
        }
        return if (out.size <= maxPoints) out else lttb(out, maxPoints)
    }

    /** Largest-triangle-three-buckets on the UP bid (fallback to DOWN). */
    fun lttb(points: List<BidPoint>, maxPoints: Int): List<BidPoint> {
        if (points.size <= maxPoints || maxPoints < 3) return downsample(points, maxPoints)
        val n = points.size
        val out = ArrayList<BidPoint>(maxPoints)
        out.add(points.first())
        val bucketSize = (n - 2).toDouble() / (maxPoints - 2)
        var a = 0
        for (i in 0 until maxPoints - 2) {
            val start = (kotlin.math.floor(i * bucketSize).toInt() + 1).coerceIn(1, n - 2)
            val end = (kotlin.math.floor((i + 1) * bucketSize).toInt() + 1).coerceIn(start + 1, n - 1)
            val nextStart = (kotlin.math.floor((i + 1) * bucketSize).toInt() + 1).coerceIn(1, n - 1)
            val nextEnd = (kotlin.math.floor((i + 2) * bucketSize).toInt() + 1).coerceIn(nextStart + 1, n)
            var avgX = 0.0
            var avgY = 0.0
            val count = (nextEnd - nextStart).coerceAtLeast(1)
            for (j in nextStart until nextEnd) {
                avgX += points[j].tMs.toDouble()
                avgY += yOf(points[j])
            }
            avgX /= count
            avgY /= count
            val ax = points[a].tMs.toDouble()
            val ay = yOf(points[a])
            var maxArea = -1.0
            var maxIdx = start
            for (j in start until end) {
                val area = kotlin.math.abs((ax - avgX) * (yOf(points[j]) - ay) - (ax - points[j].tMs) * (avgY - ay))
                if (area > maxArea) {
                    maxArea = area
                    maxIdx = j
                }
            }
            out.add(points[maxIdx])
            a = maxIdx
        }
        out.add(points.last())
        return out
    }

    fun window(points: List<BidPoint>, startMs: Long, endMs: Long): List<BidPoint> {
        if (points.isEmpty()) return points
        return points.filter { it.tMs in startMs..endMs }
    }

    private fun yOf(p: BidPoint): Double =
        (p.upBidCents ?: p.downBidCents ?: 50f).toDouble()

    private fun isSorted(points: List<BidPoint>): Boolean {
        var prev = Long.MIN_VALUE
        for (p in points) {
            if (p.tMs < prev) return false
            prev = p.tMs
        }
        return true
    }
}

fun BidPoint.hasQuote(): Boolean = upBidCents != null || downBidCents != null

fun BidPoint.hasSpot(): Boolean = spotUsd != null && spotUsd.isFinite() && spotUsd > 0.0

fun dollarsToCents(dollars: Double?): Float? {
    if (dollars == null || !dollars.isFinite()) return null
    return (dollars * 100.0).toFloat()
}

/**
 * Y-range for the underlying spot panel so the dashed TARGET line
 * sits in real USD space (not a mid-height hack).
 */
object SpotAxis {
    fun range(spots: List<Double>, strike: Double?): Pair<Double, Double>? {
        val clean = spots.filter { it.isFinite() && it > 0.0 }
        val k = strike?.takeIf { it.isFinite() && it > 0.0 }
        if (clean.isEmpty() && k == null) return null
        val lo = listOfNotNull(clean.minOrNull(), k).minOrNull() ?: return null
        val hi = listOfNotNull(clean.maxOrNull(), k).maxOrNull() ?: return null
        val pad = maxOf((hi - lo) * 0.12, (k ?: hi) * 0.0002, 1.0)
        return (lo - pad) to (hi + pad)
    }

    fun yFraction(value: Double, minY: Double, maxY: Double): Float {
        val span = (maxY - minY).coerceAtLeast(1e-9)
        return (1.0 - ((value - minY) / span)).toFloat().coerceIn(0f, 1f)
    }
}
