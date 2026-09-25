package com.dirk.kalshiodds.chart

/**
 * Merge stored + backfill + live chart prints.
 * Later sources win on the same 1-second bucket; fields fill from earlier
 * sources when the newer print is missing a bid or spot.
 * Never returns more than [MAX_WINDOW_POINTS].
 */
object ChartSeriesMerge {
    const val MAX_WINDOW_POINTS = 240
    const val BUCKET_MS = 1_000L
    const val MIN_COMPLETE_POINTS = 8
    const val MAX_FIRST_GAP_MS = 90_000L

    fun merge(vararg series: List<BidPoint>): List<BidPoint> {
        if (series.isEmpty()) return emptyList()
        val byBucket = LinkedHashMap<Long, BidPoint>()
        for (list in series) {
            for (p in list) {
                if (p.tMs <= 0L) continue
                val key = (p.tMs / BUCKET_MS) * BUCKET_MS
                byBucket[key] = prefer(byBucket[key], p.copy(tMs = key))
            }
        }
        val sorted = byBucket.values.sortedBy { it.tMs }
        return if (sorted.size <= MAX_WINDOW_POINTS) {
            sorted
        } else {
            ChartDownsampler.downsample(sorted, MAX_WINDOW_POINTS)
        }
    }

    fun prefer(older: BidPoint?, newer: BidPoint): BidPoint {
        if (older == null) return newer
        return BidPoint(
            tMs = newer.tMs,
            upBidCents = newer.upBidCents ?: older.upBidCents,
            downBidCents = newer.downBidCents ?: older.downBidCents,
            spotUsd = newer.spotUsd ?: older.spotUsd
        )
    }

    fun withLiveHead(
        points: List<BidPoint>,
        nowMs: Long,
        yesBid: Double?,
        noBid: Double?,
        spotUsd: Double?
    ): List<BidPoint> {
        val up = dollarsToCents(yesBid)
        val down = dollarsToCents(noBid)
        val spot = spotUsd?.takeIf { it.isFinite() && it > 0.0 }
        if (up == null && down == null && spot == null) return points
        return merge(points, listOf(BidPoint(tMs = nowMs, upBidCents = up, downBidCents = down, spotUsd = spot)))
    }

    fun inWindow(points: List<BidPoint>, startMs: Long, endMs: Long): List<BidPoint> {
        if (points.isEmpty()) return points
        val lo = minOf(startMs, endMs)
        val hi = maxOf(startMs, endMs)
        return points.filter { it.tMs in lo..hi }
    }

    fun coversWindow(
        points: List<BidPoint>,
        startMs: Long,
        endMs: Long,
        minPoints: Int = MIN_COMPLETE_POINTS,
        maxFirstGapMs: Long = MAX_FIRST_GAP_MS
    ): Boolean {
        val inWin = inWindow(points, startMs, endMs)
        if (inWin.size < minPoints) return false
        return inWin.first().tMs <= startMs + maxFirstGapMs
    }
}
