package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.serialization.Serializable

/**
 * On-device adapter that **reweights blend inputs** and applies a
 * lightweight slope/intercept calibration — not just temperature.
 *
 * ## How it updates
 *
 * After each non-void settlement with a stored feature snapshot:
 *
 * 1. **Feature weights** (EMA). Each blend channel stores a signed
 *    deviation `c_i = featureFair_i − mid` (percentage points).
 *    If `sign(c_i)` agreed with the realized YES/NO, the weight moves
 *    toward 1.5; if it disagreed, toward 0.5:
 *
 *        w_i ← (1−α) w_i + α · (1 + 0.5 · agree)
 *
 *    then clipped to `[0.35, 2.0]`. α = [SignalConstants.ADAPTER_WEIGHT_EMA].
 *
 * 2. **Platt-style slope / intercept** on `logit(p)` via SGD:
 *
 *        p̂ = σ(a · logit(p) + b)
 *        a ← a + η (y − p̂) logit(p)
 *        b ← b + η (y − p̂)
 *
 *    This is *in addition to* [Calibrator]'s temperature. Cold start
 *    (`sampleCount < MIN_ADAPTER_SAMPLES`) leaves weights at 1.0 and
 *    `(a, b) = (1, 0)` so the live blend is unchanged.
 *
 * Persistence: JSON in DataStore ([LearnedWeightsStore]). No network
 * training, no remote server.
 */
object OnlineAdapter {

    val FEATURE_KEYS = listOf(
        "ai", "flow", "related", "velocity", "imbalance",
        "leadLag", "depth", "cancel", "spot"
    )

    @Serializable
    data class State(
        val weights: Map<String, Double> = FEATURE_KEYS.associateWith { 1.0 },
        val slope: Double = 1.0,
        val intercept: Double = 0.0,
        val sampleCount: Int = 0,
        val lastSettledAtMs: Long = 0L,
        val updatedAtMs: Long = 0L
    ) {
        val ready: Boolean get() = sampleCount >= SignalConstants.MIN_ADAPTER_SAMPLES

        fun weight(key: String): Double = weights[key] ?: 1.0
    }

    data class FeatureDevs(
        val ai: Double? = null,
        val flow: Double? = null,
        val related: Double? = null,
        val velocity: Double? = null,
        val imbalance: Double? = null,
        val leadLag: Double? = null,
        val depth: Double? = null,
        val cancel: Double? = null,
        val spot: Double? = null
    ) {
        fun asMap(): Map<String, Double> = buildMap {
            ai?.let { put("ai", it) }
            flow?.let { put("flow", it) }
            related?.let { put("related", it) }
            velocity?.let { put("velocity", it) }
            imbalance?.let { put("imbalance", it) }
            leadLag?.let { put("leadLag", it) }
            depth?.let { put("depth", it) }
            cancel?.let { put("cancel", it) }
            spot?.let { put("spot", it) }
        }

        companion object {
            fun fromMap(map: Map<String, Double>?): FeatureDevs {
                if (map.isNullOrEmpty()) return FeatureDevs()
                return FeatureDevs(
                    ai = map["ai"],
                    flow = map["flow"],
                    related = map["related"],
                    velocity = map["velocity"],
                    imbalance = map["imbalance"],
                    leadLag = map["leadLag"],
                    depth = map["depth"],
                    cancel = map["cancel"],
                    spot = map["spot"]
                )
            }
        }
    }

    fun identity(): State = State()

    /**
     * Incremental update from newly settled rows (watermarked by
     * [State.lastSettledAtMs]). Safe to call with the full log.
     */
    fun update(
        state: State,
        entries: List<PredictionLogEntry>,
        nowMs: Long = System.currentTimeMillis(),
        alpha: Double = SignalConstants.ADAPTER_WEIGHT_EMA,
        lr: Double = SignalConstants.ADAPTER_LEARNING_RATE
    ): State {
        val fresh = entries
            .filter { e ->
                val y = e.outcome?.lowercase()
                (y == "yes" || y == "no") && (e.settledAtMs ?: e.timestampMs) > state.lastSettledAtMs
            }
            .sortedBy { it.settledAtMs ?: it.timestampMs }
        if (fresh.isEmpty()) return state

        var weights = state.weights.toMutableMap()
        FEATURE_KEYS.forEach { weights.putIfAbsent(it, 1.0) }
        var a = state.slope
        var b = state.intercept
        var n = state.sampleCount
        var last = state.lastSettledAtMs

        for (e in fresh) {
            val y = if (e.outcome.equals("yes", true)) 1.0 else 0.0
            val p = e.predictedYes.coerceIn(0.02, 0.98)
            val devs = e.featureDevs
            if (devs.isNotEmpty()) {
                val actualYes = y > 0.5
                for ((key, c) in devs) {
                    if (abs(c) < 1e-6) continue
                    val featureWantedYes = c > 0.0
                    val agree = if (featureWantedYes == actualYes) 1.0 else -1.0
                    val prev = weights[key] ?: 1.0
                    val target = 1.0 + 0.5 * agree
                    weights[key] = ((1.0 - alpha) * prev + alpha * target).coerceIn(0.35, 2.0)
                }
            }
            val z = logit(p)
            val pHat = sigmoid(a * z + b).coerceIn(1e-4, 1.0 - 1e-4)
            val err = y - pHat
            a = (a + lr * err * z).coerceIn(0.40, 2.50)
            b = (b + lr * err).coerceIn(-1.50, 1.50)
            n += 1
            last = maxOf(last, e.settledAtMs ?: e.timestampMs)
        }

        return State(
            weights = FEATURE_KEYS.associateWith { weights[it] ?: 1.0 },
            slope = a,
            intercept = b,
            sampleCount = n,
            lastSettledAtMs = last,
            updatedAtMs = nowMs
        )
    }

    /** Scale documented blend weights; identity while cold. */
    fun scaleBlend(base: ScoringEngine.BlendWeights, state: State): ScoringEngine.BlendWeights {
        if (!state.ready) return base
        val ai = base.ai * state.weight("ai")
        val flow = base.flow * state.weight("flow")
        val related = base.related * state.weight("related")
        val velocity = base.velocity * state.weight("velocity")
        val imbalance = base.imbalance * state.weight("imbalance")
        val leadLag = base.leadLag * state.weight("leadLag")
        val depth = base.depth * state.weight("depth")
        val cancel = base.cancel * state.weight("cancel")
        val spot = base.spot * state.weight("spot")
        val sum = ai + flow + related + velocity + imbalance + leadLag + depth + cancel + spot
        if (sum < 1e-9) return base
        return ScoringEngine.BlendWeights(
            ai = ai / sum,
            flow = flow / sum,
            related = related / sum,
            velocity = velocity / sum,
            imbalance = imbalance / sum,
            leadLag = leadLag / sum,
            depth = depth / sum,
            cancel = cancel / sum,
            spot = spot / sum
        )
    }

    fun scaleSpot(baseSpot: Double, state: State): Double {
        if (!state.ready || baseSpot <= 0.0) return baseSpot
        return (baseSpot * state.weight("spot")).coerceAtLeast(0.0)
    }

    /**
     * Probability calibration is disabled. [Calibrator] is the only layer
     * that remaps p. Feature reweighting ([scaleBlend]) still runs.
     */
    fun apply(pYes: Double, state: State): Double {
        return pYes.coerceIn(0.02, 0.98)
    }

    fun applyPp(pYesPp: Double, state: State): Double = apply(pYesPp / 100.0, state) * 100.0

    fun logit(p: Double): Double {
        val x = p.coerceIn(1e-4, 1.0 - 1e-4)
        return ln(x / (1.0 - x))
    }

    fun sigmoid(z: Double): Double {
        val e = exp(-z.coerceIn(-30.0, 30.0))
        return 1.0 / (1.0 + e)
    }
}
