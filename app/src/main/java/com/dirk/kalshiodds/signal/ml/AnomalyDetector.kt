package com.dirk.kalshiodds.signal.ml

import kotlin.math.abs

/**
 * Microstructure anomaly / spoof detector.
 *
 * Flags quote stuffing (best-quote flicker), cancel storms, and fake
 * depth (far size >> near size that then vanishes). Downrank or block.
 */
object AnomalyDetector {

    data class Result(
        val score: Double,
        val kinds: List<String>,
        val block: Boolean,
        val note: String
    ) {
        val anomalous: Boolean get() = score >= 0.45 || kinds.isNotEmpty()
    }

    fun evaluate(
        cancelSpike: Double?,
        quotePull: Double?,
        depthNear: Double?,
        depthFar: Double?,
        spread: Double?,
        flickerPerSec: Double?,
        enabled: Boolean,
        blockThreshold: Double = 0.72
    ): Result {
        if (!enabled) return Result(0.0, emptyList(), false, "anomaly off")
        val kinds = mutableListOf<String>()
        var s = 0.0
        val cancel = abs(cancelSpike ?: 0.0)
        if (cancel >= 0.55) {
            kinds += "cancel-storm"
            s += 0.40 * cancel
        }
        val pull = abs(quotePull ?: 0.0)
        if (pull >= 0.55) {
            kinds += "quote-pull"
            s += 0.25 * pull
        }
        val flick = flickerPerSec ?: 0.0
        if (flick >= 4.0) {
            kinds += "quote-stuffing"
            s += (0.12 * flick).coerceAtMost(0.45)
        }
        val near = depthNear ?: 0.0
        val far = depthFar ?: 0.0
        if (far > 8.0 && near > 0.0 && far / near >= 12.0) {
            kinds += "fake-depth"
            s += 0.35
        }
        val spr = spread ?: 0.0
        if (spr >= 0.12 && flick >= 2.0) {
            kinds += "wide-flicker"
            s += 0.15
        }
        val score = s.coerceIn(0.0, 1.0)
        val block = score >= blockThreshold
        val note = if (kinds.isEmpty()) "book clean" else kinds.joinToString("+")
        return Result(score = score, kinds = kinds, block = block, note = note)
    }
}
