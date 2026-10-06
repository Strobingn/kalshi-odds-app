package com.dirk.kalshiodds.decision

import java.util.Random

/**
 * Market-clustered bootstrap of the mean P&L per entry. Entries from the
 * same market (or event) move together, so whole clusters are resampled.
 * Seeded, so the same ledger always gives the same CI.
 */
object ClusteredBootstrap {
    const val DEFAULT_RESAMPLES = 2000
    const val SEED = 0L

    data class Ci(val mean: Double, val lo: Double, val hi: Double, val n: Int, val clusters: Int)

    fun meanCi(
        values: List<Pair<String, Double>>,
        resamples: Int = DEFAULT_RESAMPLES,
        seed: Long = SEED,
        alpha: Double = 0.05
    ): Ci? {
        val clean = values.filter { it.second.isFinite() }
        if (clean.isEmpty()) return null
        val clusters = clean.groupBy { it.first }.values.map { rows -> rows.sumOf { it.second } to rows.size }
        val mean = clean.sumOf { it.second } / clean.size
        if (clusters.size < 2) return Ci(mean, mean, mean, clean.size, clusters.size)
        val rnd = Random(seed)
        val stats = DoubleArray(resamples)
        for (b in 0 until resamples) {
            var sum = 0.0
            var n = 0
            repeat(clusters.size) {
                val c = clusters[rnd.nextInt(clusters.size)]
                sum += c.first
                n += c.second
            }
            stats[b] = if (n == 0) 0.0 else sum / n
        }
        stats.sort()
        val loIdx = ((alpha / 2.0) * resamples).toInt().coerceIn(0, resamples - 1)
        val hiIdx = ((1.0 - alpha / 2.0) * resamples).toInt().coerceIn(0, resamples - 1)
        return Ci(mean, stats[loIdx], stats[hiIdx], clean.size, clusters.size)
    }
}
