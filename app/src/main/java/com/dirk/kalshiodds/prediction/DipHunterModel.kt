package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.domain.MarketUiModel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device Dip Hunter predictor.
 *
 * Not a copy of Kalshi's displayed odds. Maintains a short live history of
 * YES mid / volume / time-to-expiry, then blends:
 *  - momentum (recent mid deltas)
 *  - mean reversion toward a soft prior (0.5 for early, sharpened near expiry)
 *  - volatility dampening
 *  - volume-weighted confidence
 *
 * Output is a proprietary YES probability in (0,1); NO = 1 − YES.
 * Seeded with fixed logistic weights (pure Kotlin — no TFLite dependency yet).
 */
class DipHunterModel(
    private val history: FeatureHistory = FeatureHistory(),
    private val maxPoints: Int = 48
) {

    data class Prediction(
        val yes: Double,
        val no: Double,
        val confidence: Double,
        val note: String
    )

    fun annotate(markets: List<MarketUiModel>, nowMs: Long = System.currentTimeMillis()): List<MarketUiModel> =
        markets.map { market ->
            val mid01 = (market.yesProbabilityPercent ?: return@map market) / 100.0
            val pred = predict(market.ticker, mid01, market.volume ?: 0.0, market.closeTimeEpochMs, nowMs)
            market.copy(
                aiYesPercent = pred.yes * 100.0,
                aiNoPercent = pred.no * 100.0,
                aiConfidence = pred.confidence,
                aiNote = pred.note
            )
        }

    fun predict(
        ticker: String,
        marketMid: Double,
        volume: Double,
        closeEpochMs: Long?,
        nowMs: Long
    ): Prediction {
        history.push(
            ticker = ticker,
            mid = marketMid,
            volume = volume,
            nowMs = nowMs,
            closeEpochMs = closeEpochMs,
            maxPoints = maxPoints
        )
        val series = history.series(ticker)
        val secsToClose = closeEpochMs?.let { ((it - nowMs) / 1000.0).coerceAtLeast(0.0) } ?: 900.0
        val lifeFrac = (1.0 - (secsToClose / 900.0)).coerceIn(0.0, 1.0) // 15m window heuristic

        val momentum = momentumSignal(series)
        val vol = volatility(series)
        val reversion = meanReversionSignal(marketMid, lifeFrac)
        val volumeBoost = ln(1.0 + volume / 50_000.0).coerceIn(0.0, 2.0)

        // Logistic features → proprietary logit (weights chosen so output ≠ market mid)
        val logit =
            BIAS +
                W_MOMENTUM * momentum +
                W_REVERSION * reversion +
                W_VOL * (-vol) +
                W_LIFE * (lifeFrac - 0.5) * 2.0 +
                W_MARKET * (logitFromP(marketMid) * 0.35) + // partial market info, heavily shrunk
                W_VOLUME * (volumeBoost - 0.5)

        var pYes = sigmoid(logit)
        // Nudge away if we accidentally hug the market print too closely.
        if (kotlin.math.abs(pYes - marketMid) < 0.015 && series.size >= 3) {
            pYes = (pYes + momentum * 0.08 + reversion * 0.05).coerceIn(0.02, 0.98)
        }
        pYes = pYes.coerceIn(0.02, 0.98)

        val confidence = (
            0.35 +
                0.25 * min(series.size / 12.0, 1.0) +
                0.20 * min(volumeBoost / 2.0, 1.0) +
                0.20 * (1.0 - min(vol * 4.0, 1.0))
            ).coerceIn(0.15, 0.95)

        val note = when {
            series.size < 4 -> "Warming up on live ticks"
            momentum > 0.08 -> "Momentum leaning YES"
            momentum < -0.08 -> "Momentum leaning NO"
            kotlin.math.abs(reversion) > 0.1 -> "Mean-reversion tilt"
            else -> "Balanced ensemble"
        }

        return Prediction(yes = pYes, no = 1.0 - pYes, confidence = confidence, note = note)
    }

    private fun momentumSignal(series: List<FeatureHistory.Point>): Double {
        if (series.size < 2) return 0.0
        val recent = series.takeLast(8)
        var sum = 0.0
        for (i in 1 until recent.size) {
            sum += recent[i].mid - recent[i - 1].mid
        }
        return (sum / (recent.size - 1)).coerceIn(-0.25, 0.25) * 4.0
    }

    private fun volatility(series: List<FeatureHistory.Point>): Double {
        if (series.size < 3) return 0.05
        val recent = series.takeLast(16).map { it.mid }
        val mean = recent.average()
        val variance = recent.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).coerceIn(0.0, 0.5)
    }

    private fun meanReversionSignal(mid: Double, lifeFrac: Double): Double {
        // Early: soft pull to 0.5. Late: weaker reversion (let momentum dominate).
        val pull = (0.5 - mid) * (1.0 - lifeFrac * 0.7)
        return pull.coerceIn(-0.4, 0.4)
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun logitFromP(p: Double): Double {
        val clipped = p.coerceIn(0.01, 0.99)
        return ln(clipped / (1.0 - clipped))
    }

    companion object {
        private const val BIAS = 0.0
        private const val W_MOMENTUM = 1.15
        private const val W_REVERSION = 0.85
        private const val W_VOL = 0.55
        private const val W_LIFE = 0.25
        private const val W_MARKET = 0.40
        private const val W_VOLUME = 0.20
    }
}

class FeatureHistory {
    data class Point(
        val mid: Double,
        val volume: Double,
        val nowMs: Long,
        val closeEpochMs: Long?
    )

    private val byTicker = linkedMapOf<String, ArrayDeque<Point>>()

    @Synchronized
    fun push(ticker: String, mid: Double, volume: Double, nowMs: Long, closeEpochMs: Long?, maxPoints: Int) {
        val q = byTicker.getOrPut(ticker) { ArrayDeque() }
        // Avoid duplicate spam if mid unchanged within 200ms
        val last = q.lastOrNull()
        if (last != null && nowMs - last.nowMs < 200 && kotlin.math.abs(last.mid - mid) < 1e-6) {
            return
        }
        q.addLast(Point(mid, volume, nowMs, closeEpochMs))
        while (q.size > maxPoints) q.removeFirst()
    }

    @Synchronized
    fun series(ticker: String): List<Point> = byTicker[ticker]?.toList().orEmpty()
}
