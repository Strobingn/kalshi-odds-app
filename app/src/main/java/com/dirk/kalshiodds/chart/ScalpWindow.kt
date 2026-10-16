package com.dirk.kalshiodds.chart

import com.dirk.kalshiodds.decision.ScalpFillLog
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpTrade

data class ScalpWindow(
    val ticker: String = "", val startMs: Long = 0, val endMs: Long = 0, val nowMs: Long = 0,
    val points: List<BidPoint> = emptyList(), val trades: List<ScalpTrade> = emptyList(),
    val quote: ScalpRule.Quote? = null, val error: String? = null
) {
    data class Marker(val tradeId: String, val side: String, val fill: ScalpFillLog.Fill)
    fun markers(filtered: List<ScalpTrade> = trades): List<Marker> = filtered.flatMap { t ->
        ScalpFillLog.display(t).filter { it.atMs in startMs..minOf(endMs, nowMs) }.map { Marker(t.id, t.side, it) }
    }
    companion object {
        const val DURATION_MS = 900_000L
        fun points(stored: List<BidPoint>, live: List<BidPoint>, start: Long, end: Long, now: Long): List<BidPoint> {
            // Bucket actual prints, never stretch a stale quote forward to the current wall clock.
            val bySecond = linkedMapOf<Long, BidPoint>()
            (stored + live).filter { it.tMs in start..minOf(end, now) }.sortedBy { it.tMs }.forEach { p ->
                val key = p.tMs / 1000
                val old = bySecond[key]
                bySecond[key] = p.copy(upBidCents = p.upBidCents ?: old?.upBidCents,
                    downBidCents = p.downBidCents ?: old?.downBidCents, spotUsd = p.spotUsd ?: old?.spotUsd)
            }
            return ChartDownsampler.downsample(bySecond.values.toList(), 900)
        }
    }
}
