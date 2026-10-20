package com.dirk.kalshiodds.chart

/**
 * Rolling hit rate from settled outcomes already on the scorecard.
 * [wonOldestFirst] is true for a win. The window never looks ahead.
 */
object ScorecardTrend {

    const val DEFAULT_WINDOW = 10

    fun rollingHitRate(
        wonOldestFirst: List<Boolean>,
        window: Int = DEFAULT_WINDOW
    ): List<Double> {
        if (wonOldestFirst.isEmpty()) return emptyList()
        val w = window.coerceAtLeast(1)
        val out = ArrayList<Double>(wonOldestFirst.size)
        for (i in wonOldestFirst.indices) {
            val from = (i - w + 1).coerceAtLeast(0)
            var hits = 0
            var count = 0
            for (j in from..i) {
                count += 1
                if (wonOldestFirst[j]) hits += 1
            }
            out.add(hits.toDouble() / count.toDouble())
        }
        return out
    }
}
