package com.dirk.kalshiodds.signal.ml

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Online clustering of aggressor / flow snapshots.
 * Boost when the live pattern sits in the “smart-like” centroid
 * (persistent same-side size that the mid follows).
 */
enum class FlowCluster { MIXED, CHASE, SMART }

data class FlowClusterResult(
    val cluster: FlowCluster,
    val smartAlign: Boolean,
    val boost: Double,
    val note: String
)

class RivalFlowCluster {
    private val centroids = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.15),   // MIXED
        doubleArrayOf(0.55, 0.05, 0.25), // CHASE — loud aggressor, mid lags
        doubleArrayOf(0.35, 0.40, 0.55)  // SMART — aggressor + mid follow + size
    )
    private val counts = intArrayOf(1, 1, 1)

    fun observe(aggressor: Double, midFollow: Double, sizeNorm: Double): FlowClusterResult {
        val x = doubleArrayOf(
            abs(aggressor).coerceIn(0.0, 1.0),
            midFollow.coerceIn(-1.0, 1.0).let { abs(it) },
            sizeNorm.coerceIn(0.0, 1.0)
        )
        var best = 0
        var bestD = Double.POSITIVE_INFINITY
        for (i in centroids.indices) {
            val d = dist(x, centroids[i])
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        val c = centroids[best]
        val n = counts[best]
        for (j in c.indices) c[j] = (c[j] * n + x[j]) / (n + 1)
        counts[best] = n + 1
        val cluster = FlowCluster.entries[best]
        val aligned = cluster == FlowCluster.SMART &&
            aggressor * midFollow > 0.0 &&
            abs(aggressor) >= 0.15
        val boost = if (aligned) 0.18 else if (cluster == FlowCluster.CHASE) -0.06 else 0.0
        return FlowClusterResult(
            cluster = cluster,
            smartAlign = aligned,
            boost = boost,
            note = if (aligned) "smart-flow" else cluster.name.lowercase()
        )
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return sqrt(s)
    }
}
