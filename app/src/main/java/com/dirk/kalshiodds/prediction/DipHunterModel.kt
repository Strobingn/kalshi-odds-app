package com.dirk.kalshiodds.prediction

import android.content.Context
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.withEdgeMetrics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.tensorflow.lite.Interpreter

/**
 * On-device Dip Hunter predictor powered by a small TFLite MLP.
 *
 * Features match ml/FEATURES.md (8 floats, standardized with feature_scaler.json).
 * Softmax output order: [P(NO), P(YES)].
 *
 * If TFLite fails to load, falls back to an embedded dense-net with the same
 * architecture / weights (see FallbackWeights).
 */
class DipHunterModel(
    context: Context? = null,
    private val history: FeatureHistory = FeatureHistory(),
    private val maxPoints: Int = 48
) {
    data class Prediction(
        val yes: Double,
        val no: Double,
        val confidence: Double,
        val note: String
    )

    private var interpreter: Interpreter? = null
    private var mean: FloatArray = FallbackWeights.MEAN.copyOf()
    private var std: FloatArray = FallbackWeights.STD.copyOf()
    private var tfliteReady: Boolean = false

    init {
        if (context != null) {
            runCatching {
                loadScaler(context)
                val model = context.assets.open("diphunter.tflite").use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
                buf.put(model)
                buf.rewind()
                interpreter = Interpreter(buf)
                tfliteReady = true
            }
        }
    }

    fun annotate(
        markets: List<MarketUiModel>,
        nowMs: Long = System.currentTimeMillis(),
        edgeThresholdPp: Double = com.dirk.kalshiodds.domain.EDGE_ALERT_THRESHOLD_PP
    ): List<MarketUiModel> =
        markets.map { market ->
            val mid01 = (market.yesProbabilityPercent ?: return@map market) / 100.0
            val pred = predict(
                ticker = market.ticker,
                marketMid = mid01,
                volume = market.volume ?: 0.0,
                closeEpochMs = market.closeTimeEpochMs,
                nowMs = nowMs,
                openInterest = market.openInterest ?: 0.0
            )
            market.copy(
                aiYesPercent = pred.yes * 100.0,
                aiNoPercent = pred.no * 100.0,
                aiConfidence = pred.confidence,
                aiNote = pred.note
            ).withEdgeMetrics(edgeThresholdPp)
        }

    /**
     * TFLite [Interpreter] is not thread-safe. ViewModel REST annotate and the
     * live WS scoring loop share one instance — concurrent [Interpreter.run]
     * native-crashes the process mid-session.
     */
    @Synchronized
    fun predict(
        ticker: String,
        marketMid: Double,
        volume: Double,
        closeEpochMs: Long?,
        nowMs: Long,
        openInterest: Double = 0.0
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
        val raw = FeatureVector.build(
            mid = marketMid,
            volume = volume,
            closeEpochMs = closeEpochMs,
            nowMs = nowMs,
            series = series,
            ticker = ticker,
            openInterest = openInterest
        )
        val scaled = FeatureVector.standardize(raw, mean, std)
        val probs = runInference(scaled)
        var pNo = probs[FeatureVector.IDX_NO].toDouble().coerceIn(0.02, 0.98)
        var pYes = probs[FeatureVector.IDX_YES].toDouble().coerceIn(0.02, 0.98)
        val sum = pNo + pYes
        if (sum > 1e-9) {
            pNo /= sum
            pYes /= sum
        }

        val confidence = (
            0.40 +
                0.25 * minOf(series.size / 12.0, 1.0) +
                0.20 * (1.0 - kotlin.math.abs(pYes - 0.5) * 0.5) +
                0.15 * if (tfliteReady) 1.0 else 0.6
            ).coerceIn(0.15, 0.95)

        val note = when {
            !tfliteReady -> "Fallback MLP (embedded weights)"
            series.size < 4 -> "TFLite warming on live ticks"
            pYes > 0.62 -> "Neural net leaning YES"
            pYes < 0.38 -> "Neural net leaning NO"
            else -> "TFLite balanced"
        }
        return Prediction(yes = pYes, no = pNo, confidence = confidence, note = note)
    }

    /** Expose last built features for tests. */
    fun buildFeaturesForTest(
        ticker: String,
        marketMid: Double,
        volume: Double,
        closeEpochMs: Long?,
        nowMs: Long
    ): FloatArray {
        history.push(ticker, marketMid, volume, nowMs, closeEpochMs, maxPoints)
        return FeatureVector.build(marketMid, volume, closeEpochMs, nowMs, history.series(ticker), ticker)
    }

    private fun runInference(scaled: FloatArray): FloatArray {
        val tflite = interpreter
        if (tflite != null && tfliteReady) {
            return runCatching {
                val input = Array(1) { scaled }
                val output = Array(1) { FloatArray(2) }
                tflite.run(input, output)
                output[0]
            }.getOrElse { FallbackWeights.forward(scaled) }
        }
        return FallbackWeights.forward(scaled)
    }

    private fun loadScaler(context: Context) {
        val text = context.assets.open("feature_scaler.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(text)
        val meanArr = obj.getJSONArray("mean")
        val stdArr = obj.getJSONArray("std")
        mean = FloatArray(FeatureVector.SIZE) { i -> meanArr.getDouble(i).toFloat() }
        std = FloatArray(FeatureVector.SIZE) { i -> stdArr.getDouble(i).toFloat() }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
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
