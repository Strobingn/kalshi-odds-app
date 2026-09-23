package com.dirk.kalshiodds.signal.ml

/**
 * Shared encoder: temporal CNN (and optional LSTM) + series embedding → 16-d
 * representation reused by multi-task heads and the last-layer adapter.
 */
class SharedBackbone(
    private val project: Array<FloatArray>,
    private val projectBias: FloatArray,
    val series: SeriesEmbedding
) {
    val dim: Int get() = projectBias.size

    fun encode(
        cnnPool: FloatArray?,
        lstmH: FloatArray?,
        seriesKey: String
    ): FloatArray {
        val emb = series.get(seriesKey)
        val fused = FloatArray(TemporalCnn.CONV_OUT + TinyLstm.HIDDEN + SeriesEmbedding.DIM)
        if (cnnPool != null) {
            for (i in cnnPool.indices) if (i < TemporalCnn.CONV_OUT) fused[i] = cnnPool[i]
        }
        if (lstmH != null) {
            val off = TemporalCnn.CONV_OUT
            for (i in lstmH.indices) if (off + i < fused.size) fused[off + i] = lstmH[i]
        }
        val embOff = TemporalCnn.CONV_OUT + TinyLstm.HIDDEN
        for (i in emb.indices) if (embOff + i < fused.size) fused[embOff + i] = emb[i]
        return MlMath.dense(fused, project, projectBias, MlMath::relu)
    }

    companion object {
        const val DIM = 16
        private val IN = TemporalCnn.CONV_OUT + TinyLstm.HIDDEN + SeriesEmbedding.DIM

        fun defaults(series: SeriesEmbedding = SeriesEmbedding.defaults()): SharedBackbone {
            val w = Array(DIM) { i ->
                val row = FloatArray(IN)
                // Diagonal-ish so CNN channel i and LSTM unit i survive.
                if (i < TemporalCnn.CONV_OUT) row[i] = 0.55f
                if (i < TinyLstm.HIDDEN) row[TemporalCnn.CONV_OUT + i] = 0.35f
                if (i < SeriesEmbedding.DIM) row[TemporalCnn.CONV_OUT + TinyLstm.HIDDEN + i] = 0.40f
                row
            }
            return SharedBackbone(w, FloatArray(DIM), series)
        }
    }
}
