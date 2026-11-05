package com.dirk.kalshiodds.signal.updown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.exp

/**
 * The up/down model: the chance that a Bitcoin 15-minute window settles UP, from the book, the Coinbase spot and the
 * app's fair price ([UpDownFeatures]). Trained by `tools/research/updown/train_updown.py` (a logistic regression on
 * 11 inputs) and shipped twice: `assets/updown_model.json` (read here; the Kotlin answer) and
 * `assets/updown_model.onnx` (the same model for ONNX Runtime, run in the `:ml` process).
 *
 * ONNX Runtime is used only after [attachOnnx] finds it gives the Kotlin answer on [probeRows]; if a call to it ever
 * fails, the model drops back to Kotlin for good and [engine] says why.
 *
 * What it is worth, from the walk-forward test in the file ([report]): about as good as the market's own price, not
 * better. It is a second opinion beside the market, not an edge.
 */
class UpDownModel(
    val features: List<String>,
    private val weights: DoubleArray,
    private val bias: Double,
    val report: Report
) {
    init {
        require(features.size == weights.size) { "${features.size} features, ${weights.size} weights" }
    }

    /** The walk-forward numbers the trainer wrote into the model file, for the screens. */
    data class Report(
        val firstDay: String = "",
        val lastDay: String = "",
        val trainedWindows: Int = 0,
        val testWindows: Int = 0,
        val testDays: Int = 0,
        val model: Score = Score(),
        val market: Score = Score(),
        val fair: Score = Score(),
        /** (mean chance it gave, share that settled UP, rows), per tenth. */
        val calibration: List<Triple<Double, Double, Int>> = emptyList(),
        val marketCalibration: List<Triple<Double, Double, Int>> = emptyList(),
        /** (time left, model log loss, market log loss). */
        val byTimeLeft: List<Triple<String, Double, Double>> = emptyList(),
        /** (cut, bets, cents a contract after the fee), the replay of one 1-lot a minute. */
        val replay: List<Triple<String, Int, Double>> = emptyList()
    )

    data class Score(val logLoss: Double = Double.NaN, val brier: Double = Double.NaN, val accuracy: Double = Double.NaN, val ece: Double = Double.NaN)

    @Volatile private var onnx: ((FloatArray) -> Double)? = null

    /** Where it runs now: "Kotlin" until ONNX Runtime is attached and matches, then the ONNX label. */
    @Volatile var engine: String = "Kotlin (ONNX Runtime not opened yet)"
        private set

    /** The chance of UP for one input row, on the Kotlin math. */
    fun upKotlin(x: FloatArray): Double {
        var z = bias
        for (i in weights.indices) z += weights[i] * x[i]
        return 1.0 / (1.0 + exp(-z))
    }

    /** The chance of UP for one input row, on ONNX Runtime when attached (else Kotlin). Never throws. */
    fun up(x: FloatArray): Double {
        val run = onnx ?: return upKotlin(x)
        return try {
            val v = run(x)
            check(v.isFinite() && v in 0.0..1.0) { "ONNX Runtime gave $v" }
            v
        } catch (t: Throwable) {
            onnx = null
            engine = "Kotlin (ONNX Runtime failed: ${t.message?.take(80) ?: t.javaClass.simpleName})"
            upKotlin(x)
        }
    }

    /** Uses [run] (an ONNX engine) only when it gives the Kotlin answer on every probe row; returns why not, or null. */
    fun attachOnnx(run: (FloatArray) -> Double, label: String, probes: List<FloatArray> = probeRows()): String? {
        val worst = try {
            probes.maxOf { abs(run(it) - upKotlin(it)) }
        } catch (t: Throwable) {
            return "failed on a test row: ${t.message?.take(80) ?: t.javaClass.simpleName}"
        }
        if (!(worst <= PARITY_TOLERANCE)) return "differs from the Kotlin math by ${"%.2g".format(worst)}"
        onnx = run
        engine = label
        return null
    }

    fun markKotlin(why: String) {
        onnx = null
        engine = "Kotlin ($why)"
    }

    /** Rows that cover the inputs' real range (log-odds out to ±5, spreads, moves). Deterministic. */
    fun probeRows(n: Int = 64): List<FloatArray> {
        val rnd = java.util.Random(11)
        return List(n) {
            FloatArray(features.size) { i ->
                val name = features[i]
                when {
                    name.startsWith("logit") || name.startsWith("edge") -> (rnd.nextGaussian() * 2.0).toFloat()
                    name == "spread" -> (rnd.nextDouble() * 0.06).toFloat()
                    name == "imb" -> (rnd.nextDouble() * 2.0 - 1.0).toFloat()
                    name.startsWith("dmid") -> (rnd.nextGaussian() * 0.08).toFloat()
                    else -> (rnd.nextGaussian() * 1.5).toFloat()
                }
            }
        }
    }

    companion object {
        const val ASSET_NAME = "updown_model.json"
        const val ONNX_ASSET_NAME = "updown_model.onnx"
        /** float32 ONNX against double Kotlin on a chance in 0..1. */
        const val PARITY_TOLERANCE = 1e-4

        fun parse(text: String): UpDownModel {
            val o = Json.parseToJsonElement(text).jsonObject
            require((o["kind"] as? JsonPrimitive)?.contentOrNull == "logistic") { "not an up/down logistic model" }
            val feats = o.getValue("features").jsonArray.map { (it as JsonPrimitive).content }
            val w = o.getValue("weights").jsonArray.map { (it as JsonPrimitive).doubleOrNull ?: error("bad weight") }.toDoubleArray()
            val b = (o["bias"] as? JsonPrimitive)?.doubleOrNull ?: error("no bias")
            return UpDownModel(feats, w, b, report(o))
        }

        private fun d(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
        private fun i(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
        private fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun score(o: JsonObject?) = Score(d(o, "logloss"), d(o, "brier"), d(o, "accuracy"), d(o, "ece"))
        private fun cal(a: JsonArray?): List<Triple<Double, Double, Int>> = a.orEmpty().mapNotNull { e ->
            val r = (e as? JsonArray) ?: return@mapNotNull null
            if (r.size < 3) return@mapNotNull null
            Triple((r[0] as JsonPrimitive).doubleOrNull ?: return@mapNotNull null, (r[1] as JsonPrimitive).doubleOrNull ?: 0.0, (r[2] as JsonPrimitive).intOrNull ?: 0)
        }

        private fun report(o: JsonObject): Report {
            val wf = o["walk_forward"] as? JsonObject
            val by = (wf?.get("by_time_left") as? JsonObject)?.map { (k, v) ->
                val m = v as? JsonObject
                Triple(k, d(m, "compact"), d(m, "market"))
            }.orEmpty()
            val replay = (wf?.get("paper_replay") as? JsonObject)?.map { (k, v) ->
                val m = v as? JsonObject
                Triple(k.removePrefix("cut_"), i(m, "bets"), d(m, "cents_per_contract"))
            }.orEmpty()
            return Report(
                firstDay = s(o, "first_day"), lastDay = s(o, "last_day"), trainedWindows = i(o, "trained_windows"),
                testWindows = i(wf, "windows"), testDays = i(wf, "days"),
                model = score(wf?.get("model") as? JsonObject), market = score(wf?.get("market") as? JsonObject),
                fair = score(wf?.get("fair") as? JsonObject),
                calibration = cal(wf?.get("calibration") as? JsonArray),
                marketCalibration = cal(wf?.get("market_calibration") as? JsonArray),
                byTimeLeft = by, replay = replay
            )
        }
    }
}
