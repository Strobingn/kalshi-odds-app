package com.dirk.kalshiodds.prediction

import org.json.JSONObject

/**
 * Small JSON next to the weekly-trained edge model.
 * `version`, `trained_at`, `n_samples`, Brier / log-loss vs market.
 */
data class EdgeModelManifest(
    val version: String,
    val trainedAt: String,
    val nSamples: Int,
    val modelBrier: Double,
    val marketBrier: Double,
    val modelLogLoss: Double,
    val marketLogLoss: Double,
    val modelAsset: String = "edge_model.json",
    val tag: String = com.dirk.kalshiodds.signal.config.SignalConstants.EDGE_MODEL_RELEASE_TAG,
    val simPnl: Double? = null,
    val simHitRate: Double? = null,
    /** Trained on the fixture / fallback data, never on settled markets. */
    val synthetic: Boolean = false
) {
    val beatsMarket: Boolean
        get() = !synthetic &&
            nSamples > 0 &&
            modelBrier < marketBrier &&
            modelLogLoss < marketLogLoss

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", version)
        o.put("trained_at", trainedAt)
        o.put("n_samples", nSamples)
        o.put("model_brier", modelBrier)
        o.put("market_brier", marketBrier)
        o.put("model_logloss", modelLogLoss)
        o.put("market_logloss", marketLogLoss)
        o.put("model_asset", modelAsset)
        o.put("tag", tag)
        if (simPnl != null) o.put("sim_pnl", simPnl)
        if (simHitRate != null) o.put("sim_hit_rate", simHitRate)
        if (synthetic) o.put("synthetic", true)
        o.put("beats_market", beatsMarket)
        return o.toString()
    }

    companion object {
        fun parse(raw: String): EdgeModelManifest {
            val o = JSONObject(raw)
            val n = when {
                o.has("n_samples") -> o.optInt("n_samples")
                o.has("n_holdout") -> o.optInt("n_holdout")
                else -> 0
            }
            require(n >= 0) { "n_samples must be >= 0" }
            val modelBrier = requireFinite(o, "model_brier")
            val marketBrier = requireFinite(o, "market_brier")
            val modelLl = requireFinite(o, "model_logloss")
            val marketLl = requireFinite(o, "market_logloss")
            val version = o.optString("version").ifBlank { "1" }
            val trainedAt = o.optString("trained_at").ifBlank {
                error("manifest missing trained_at")
            }
            return EdgeModelManifest(
                version = version,
                trainedAt = trainedAt,
                nSamples = n,
                modelBrier = modelBrier,
                marketBrier = marketBrier,
                modelLogLoss = modelLl,
                marketLogLoss = marketLl,
                modelAsset = o.optString("model_asset").ifBlank { "edge_model.json" },
                tag = o.optString("tag").ifBlank {
                    com.dirk.kalshiodds.signal.config.SignalConstants.EDGE_MODEL_RELEASE_TAG
                },
                simPnl = o.optDoubleOrNull("sim_pnl"),
                simHitRate = o.optDoubleOrNull("sim_hit_rate"),
                synthetic = o.optBoolean("synthetic", false)
            )
        }

        fun fromModelMetrics(
            model: EdgeModel,
            trainedAt: String,
            version: String = model.version.toString()
        ): EdgeModelManifest {
            val m = model.metrics
            return EdgeModelManifest(
                version = version,
                trainedAt = trainedAt,
                nSamples = (m["n_holdout"] ?: m["n_samples"] ?: 0.0).toInt(),
                modelBrier = m["model_brier"] ?: error("model missing model_brier"),
                marketBrier = m["market_brier"] ?: error("model missing market_brier"),
                modelLogLoss = m["model_logloss"] ?: error("model missing model_logloss"),
                marketLogLoss = m["market_logloss"] ?: error("model missing market_logloss"),
                simPnl = m["sim_pnl"],
                simHitRate = m["sim_hit_rate"],
                synthetic = (m["synthetic"] ?: 0.0) > 0.0
            )
        }

        private fun requireFinite(o: JSONObject, key: String): Double {
            if (!o.has(key) || o.isNull(key)) error("manifest missing $key")
            val v = o.optDouble(key, Double.NaN)
            require(v.isFinite()) { "manifest $key is not finite" }
            return v
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val v = optDouble(key, Double.NaN)
            return if (v.isFinite()) v else null
        }
    }
}

data class ModelActivationDecision(
    val activate: Boolean,
    val rollback: Boolean = false,
    val reason: String,
    val manifest: EdgeModelManifest? = null
)

object ModelActivation {
    fun decide(manifest: EdgeModelManifest, modelValid: Boolean): ModelActivationDecision {
        if (!modelValid) {
            return ModelActivationDecision(
                activate = false,
                reason = "Model JSON failed validation — previous model stays active."
            )
        }
        if (manifest.synthetic) {
            return ModelActivationDecision(
                activate = false,
                manifest = manifest,
                reason = "Model was trained on synthetic fixture data — not activating."
            )
        }
        if (manifest.nSamples <= 0) {
            return ModelActivationDecision(
                activate = false,
                manifest = manifest,
                reason = "Manifest has no holdout samples — not activating."
            )
        }
        if (!manifest.beatsMarket) {
            return ModelActivationDecision(
                activate = false,
                manifest = manifest,
                reason = "Holdout does not beat the market " +
                    "(Brier ${fmt(manifest.modelBrier)} vs ${fmt(manifest.marketBrier)}, " +
                    "log-loss ${fmt(manifest.modelLogLoss)} vs ${fmt(manifest.marketLogLoss)}). " +
                    "Previous model stays active."
            )
        }
        return ModelActivationDecision(
            activate = true,
            manifest = manifest,
            reason = "Activated — holdout beats market " +
                "(Brier ${fmt(manifest.modelBrier)} < ${fmt(manifest.marketBrier)}, " +
                "log-loss ${fmt(manifest.modelLogLoss)} < ${fmt(manifest.marketLogLoss)}, " +
                "n=${manifest.nSamples})."
        )
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)
}
