package com.dirk.kalshiodds.signal.ml

/**
 * Per-ticker ring of raw sequence frames covering the last ~5 minutes.
 * Scoring downsamples via [SequenceFeatures.resample].
 */
class SequenceBuffer(
    private val windowMs: Long = SequenceFeatures.WINDOW_MS,
    private val maxRaw: Int = 240
) {
    private val byTicker = linkedMapOf<String, ArrayDeque<SequenceFrame>>()

    @Synchronized
    fun push(ticker: String, frame: SequenceFrame) {
        val q = byTicker.getOrPut(ticker) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null && frame.tMs - last.tMs < 80 &&
            kotlin.math.abs(last.mid - frame.mid) < 1e-5f &&
            kotlin.math.abs(last.imbalance - frame.imbalance) < 1e-5f
        ) {
            return
        }
        q.addLast(frame)
        val cutoff = frame.tMs - windowMs
        while (q.isNotEmpty() && (q.size > maxRaw || q.first().tMs < cutoff)) {
            q.removeFirst()
        }
        while (byTicker.size > MAX_TICKERS) {
            val oldest = byTicker.keys.firstOrNull() ?: break
            byTicker.remove(oldest)
        }
    }

    @Synchronized
    fun raw(ticker: String): List<SequenceFrame> = byTicker[ticker]?.toList().orEmpty()

    @Synchronized
    fun window(ticker: String, nowMs: Long): Array<FloatArray> =
        SequenceFeatures.resample(raw(ticker), nowMs)

    @Synchronized
    fun populatedBins(ticker: String, nowMs: Long): Int =
        SequenceFeatures.populatedBins(raw(ticker), nowMs)

    @Synchronized
    fun ready(ticker: String, nowMs: Long, minBins: Int = SequenceFeatures.MIN_FRAMES_FOR_SEQUENCE): Boolean =
        populatedBins(ticker, nowMs) >= minBins

    companion object {
        const val MAX_TICKERS = 24
    }
}
