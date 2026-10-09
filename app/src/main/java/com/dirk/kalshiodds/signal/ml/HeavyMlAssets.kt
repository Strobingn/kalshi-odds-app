package com.dirk.kalshiodds.signal.ml

import android.content.Context
import org.json.JSONObject

/**
 * Optional student snapshot from `assets/heavy_ml_student.json`.
 * Missing / unreadable files leave Kotlin defaults in place (0.2.x-safe).
 */
object HeavyMlAssets {
    const val STUDENT_JSON = "heavy_ml_student.json"

    fun apply(context: Context?, runtime: HeavyMlRuntime): Boolean {
        if (context == null) return false
        val text = runCatching {
            context.assets.open(STUDENT_JSON).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return false
        return applyJson(text, runtime)
    }

    fun applyJson(text: String, runtime: HeavyMlRuntime): Boolean {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return false
        obj.optJSONObject("stack")?.let { s ->
            runtime.stack = EnsembleStack.Weights(
                mlp = s.optDouble("mlp", 0.55),
                cnn = s.optDouble("cnn", 0.20),
                lstm = s.optDouble("lstm", 0.10),
                gbm = s.optDouble("gbm", 0.15),
                sampleCount = s.optInt("sampleCount", 0)
            )
        }
        obj.optJSONObject("gbm")?.let { g ->
            val trees = mutableListOf<GbmTree>()
            val arr = g.optJSONArray("trees")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val nodesArr = arr.getJSONObject(i).optJSONArray("nodes") ?: continue
                    val nodes = (0 until nodesArr.length()).map { j ->
                        val n = nodesArr.getJSONObject(j)
                        if (n.has("value")) {
                            GbmNode(value = n.getDouble("value").toFloat())
                        } else {
                            GbmNode(
                                feature = n.optInt("feature", -1),
                                threshold = n.optDouble("threshold", 0.0).toFloat(),
                                left = n.optInt("left", -1),
                                right = n.optInt("right", -1)
                            )
                        }
                    }
                    trees += GbmTree(nodes)
                }
            }
            if (trees.isNotEmpty()) {
                runtime.installGbm(
                    GbmModel(
                        trees = trees,
                        learningRate = g.optDouble("learningRate", 0.35),
                        baseScore = g.optDouble("baseScore", 0.0)
                    )
                )
            }
        }
        return true
    }
}
