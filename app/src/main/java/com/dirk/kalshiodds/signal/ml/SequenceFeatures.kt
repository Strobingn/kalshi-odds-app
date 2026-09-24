package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.domain.CryptoMarkets
import kotlin.math.ln

/**
 * One resampled frame of the last 1–5 minutes:
 * mid, size, imbalance, aggressor flow, spot return.
 */
data class SequenceFrame(
    val mid: Float,
    val size: Float,
    val imbalance: Float,
    val aggressor: Float,
    val spot: Float,
    val tMs: Long
) {
    fun toRow(): FloatArray = floatArrayOf(mid, size, imbalance, aggressor, spot)

    companion object {
        const val CHANNELS = 5
        val NAMES = listOf("mid", "size", "imbalance", "aggressor", "spot")
    }
}

object SequenceFeatures {
    const val FRAMES = 30
    const val WINDOW_MS = 5 * 60_000L
    const val BIN_MS = WINDOW_MS / FRAMES
    const val MIN_FRAMES_FOR_SEQUENCE = 8

    fun normalizeSize(raw: Double): Float {
        val n = ln(1.0 + raw.coerceAtLeast(0.0)) / 8.0
        return n.toFloat().coerceIn(0f, 1f)
    }

    fun seriesIndex(series: String): Int {
        val u = series.uppercase()
        return when {
            u.startsWith(KalshiApi.SERIES_BTC) || (u.contains("BTC") && !u.contains("ETH")) -> 0
            u.startsWith(KalshiApi.SERIES_ETH) || (u.contains("ETH") && !u.contains("BTC")) -> 1
            u.startsWith(KalshiApi.SERIES_SOL) || u.contains("SOL") -> 2
            else -> 3
        }
    }

    fun seriesKey(ticker: String): String = CryptoMarkets.inferSeries(ticker)

    /**
     * Downsample raw ticks into a fixed [FRAMES] × 5 tensor.
     * Pads on the left with the first observed frame (or zeros if empty).
     */
    fun resample(raw: List<SequenceFrame>, nowMs: Long, frames: Int = FRAMES, windowMs: Long = WINDOW_MS): Array<FloatArray> {
        val start = nowMs - windowMs
        val binMs = (windowMs / frames).coerceAtLeast(1L)
        val sums = Array(frames) { FloatArray(SequenceFrame.CHANNELS) }
        val counts = IntArray(frames)
        for (f in raw) {
            if (f.tMs < start) continue
            val idx = ((f.tMs - start) / binMs).toInt().coerceIn(0, frames - 1)
            val row = sums[idx]
            row[0] += f.mid
            row[1] += f.size
            row[2] += f.imbalance
            row[3] += f.aggressor
            row[4] += f.spot
            counts[idx]++
        }
        val out = Array(frames) { FloatArray(SequenceFrame.CHANNELS) }
        var last = FloatArray(SequenceFrame.CHANNELS)
        var haveLast = false
        val seed = raw.firstOrNull { it.tMs >= start } ?: raw.lastOrNull()
        if (seed != null) {
            last[0] = seed.mid
            last[1] = seed.size
            last[2] = seed.imbalance
            last[3] = seed.aggressor
            last[4] = seed.spot
            haveLast = true
        }
        for (i in 0 until frames) {
            val dest = out[i]
            val n = counts[i]
            if (n > 0) {
                val inv = 1f / n
                val src = sums[i]
                dest[0] = src[0] * inv
                dest[1] = src[1] * inv
                dest[2] = src[2] * inv
                dest[3] = src[3] * inv
                dest[4] = src[4] * inv
                last = dest
                haveLast = true
            } else if (haveLast) {
                dest[0] = last[0]
                dest[1] = last[1]
                dest[2] = last[2]
                dest[3] = last[3]
                dest[4] = last[4]
            }
        }
        return out
    }

    fun populatedBins(raw: List<SequenceFrame>, nowMs: Long, frames: Int = FRAMES, windowMs: Long = WINDOW_MS): Int {
        if (raw.isEmpty()) return 0
        val start = nowMs - windowMs
        val binMs = (windowMs / frames).coerceAtLeast(1L)
        val seen = BooleanArray(frames)
        for (f in raw) {
            if (f.tMs < start) continue
            val idx = ((f.tMs - start) / binMs).toInt().coerceIn(0, frames - 1)
            seen[idx] = true
        }
        return seen.count { it }
    }
}
