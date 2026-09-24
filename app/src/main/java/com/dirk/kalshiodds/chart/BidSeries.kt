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
    const val CARD_POINTS = 64
    const val DETAIL_POINTS = 180
    const val MAX_RAW_POINTS = 720

    fun downsample(points: List<BidPoint>, maxPoints: Int = CARD_POINTS): List<BidPoint> {
        if (points.size <= maxPoints) return points
        if (maxPoints <= 2) return listOfNotNull(points.firstOrNull(), points.lastOrNull())
        val sorted = if (isSorted(points)) points else points.sortedBy { it.tMs }
        val first = sorted.first().tMs
        val last = sorted.last().tMs
        val span = (last - first).coerceAtLeast(1L)
        val buckets = Array<MutableList<BidPoint>?>(maxPoints) { null }
        for (p in sorted) {
            val idx = (((p.tMs - first) * (maxPoints - 1L)) / span).toInt().coerceIn(0, maxPoints - 1)
            val bucket = buckets[idx] ?: ArrayList<BidPoint>(2).also { buckets[idx] = it }
            if (bucket.size < 4) {
                bucket.add(p)
            } else {
                // Keep first, last, min-up, max-up of the bucket (overwrite last).
                bucket[bucket.lastIndex] = p
            }
        }
        val out = ArrayList<BidPoint>(maxPoints)
        for (b in buckets) {
            if (b.isNullOrEmpty()) continue
            if (b.size == 1) {
                out.add(b[0])
            } else {
                out.add(b.first())
                val mid = b.drop(1).dropLast(1)
                val extrema = mid.minByOrNull { it.upBidCents ?: it.downBidCents ?: 0f }
                if (extrema != null) out.add(extrema)
                out.add(b.last())
            }
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

fun dollarsToCents(dollars: Double?): Float? {
    if (dollars == null || !dollars.isFinite()) return null
    return (dollars * 100.0).toFloat()
}
