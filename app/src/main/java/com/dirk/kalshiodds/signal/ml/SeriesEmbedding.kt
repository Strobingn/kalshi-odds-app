package com.dirk.kalshiodds.signal.ml

/**
 * Learned (or default) series embeddings so BTC / ETH / SOL share a backbone
 * and transfer lead–lag structure. Index 0=BTC, 1=ETH, 2=SOL, 3=other crypto.
 */
class SeriesEmbedding(
    private val table: Array<FloatArray>
) {
    val dim: Int get() = table.firstOrNull()?.size ?: DIM

    fun get(series: String): FloatArray {
        val idx = SequenceFeatures.seriesIndex(series).coerceIn(0, table.lastIndex)
        return table[idx].copyOf()
    }

    companion object {
        const val DIM = 8

        fun defaults(): SeriesEmbedding {
            val table = Array(4) { FloatArray(DIM) }
            // One-hot-ish + small shared crypto bias so transfer is possible cold-start.
            table[0][0] = 1f; table[0][3] = 0.25f; table[0][4] = 0.10f
            table[1][1] = 1f; table[1][3] = 0.18f; table[1][4] = 0.16f; table[1][5] = 0.08f
            table[2][2] = 1f; table[2][3] = 0.12f; table[2][4] = 0.22f; table[2][6] = 0.10f
            table[3][7] = 1f; table[3][3] = 0.10f
            return SeriesEmbedding(table)
        }

        fun fromRows(rows: List<List<Double>>): SeriesEmbedding {
            if (rows.size < 3) return defaults()
            val table = Array(maxOf(4, rows.size)) { i ->
                val src = rows.getOrNull(i) ?: rows.last()
                FloatArray(DIM) { j -> src.getOrNull(j)?.toFloat() ?: 0f }
            }
            return SeriesEmbedding(table)
        }
    }
}
