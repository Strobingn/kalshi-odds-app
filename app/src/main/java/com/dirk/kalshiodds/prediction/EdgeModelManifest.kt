package com.dirk.kalshiodds.prediction

import org.json.JSONObject

/**
 * Small JSON next to the weekly-trained edge model.
 * Sample counts, synthetic flag, and holdout margins are required
 * before the phone will turn a model on.
 */
data class EdgeModelManifest(
    val version: String,
    val trainedAt: String,
    val nSamples: Int,
    val nRows: Int = nSamples,
    val nMarkets: Int = 0,
    val nHoldout: Int = nSamples,
    val modelBrier: Double,
    val marketBrier: Double,
    val modelLogLoss: Double,
    val marketLogLoss: Double,
    val modelAsset: String = "edge_model.json",
    val tag: String = "edge-model-latest",
    val simPnl: Double? = null,
    val simHitRate: Double? = null,
    val synthetic: Boolean = false,
    val countsPresent: Boolean = true,
    val brierMargin: Double = marketBrier - modelBrier,
    val logLossMargin: Double = marketLogLoss - modelLogLoss
) {
    val beatsMarket: Boolean
        get() = ModelActivation.beatsMarket(this)

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", version)
        o.put("trained_at", trainedAt)
        o.put("n_samples", nSamples)
        o.put("n_rows", nRows)
        o.put("n_markets", nMarkets)
        o.put("n_holdout", nHoldout)
        o.put("model_brier", modelBrier)
        o.put("market_brier", marketBrier)
        o.put("model_logloss", modelLogLoss)
        o.put("market_logloss", marketLogLoss)
        o.put("brier_margin", brierMargin)
        o.put("logloss_margin", logLossMargin)
        o.put("model_asset", modelAsset)
        o.put("tag", tag)
        if (simPnl != null) o.put("sim_pnl", simPnl)
        if (simHitRate != null) o.put("sim_hit_rate", simHitRate)
        o.put("synthetic", synthetic)
        o.put("beats_market", beatsMarket)
        return o.toString()
    }

    companion object {
        fun parse(raw: String): EdgeModelManifest {
            val o = JSONObject(raw)
            val hasRows = o.has("n_rows") || o.has("n_samples")
            val hasMarkets = o.has("n_markets")
            val hasHoldout = o.has("n_holdout") || o.has("n_samples")
            val nSamples = when {
                o.has("n_samples") -> o.optInt("n_samples")
                o.has("n_rows") -> o.optInt("n_rows")
                o.has("n_holdout") -> o.optInt("n_holdout")
                else -> 0
            }
            val nRows = if (o.has("n_rows")) o.optInt("n_rows") else nSamples
            val nMarkets = if (o.has("n_markets")) o.optInt("n_markets") else 0
            val nHoldout = if (o.has("n_holdout")) o.optInt("n_holdout") else nSamples
            require(nSamples >= 0 && nRows >= 0 && nMarkets >= 0 && nHoldout >= 0) {
                "sample counts must be >= 0"
            }
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
                nSamples = nSamples,
                nRows = nRows,
                nMarkets = nMarkets,
                nHoldout = nHoldout,
                modelBrier = modelBrier,
                marketBrier = marketBrier,
                modelLogLoss = modelLl,
                marketLogLoss = marketLl,
                modelAsset = o.optString("model_asset").ifBlank { "edge_model.json" },
                tag = o.optString("tag").ifBlank { "edge-model-latest" },
                simPnl = o.optDoubleOrNull("sim_pnl"),
                simHitRate = o.optDoubleOrNull("sim_hit_rate"),
                synthetic = o.optBoolean("synthetic", false),
                countsPresent = hasRows && hasMarkets && hasHoldout,
                brierMargin = if (o.has("brier_margin")) o.optDouble("brier_margin") else marketBrier - modelBrier,
                logLossMargin = if (o.has("logloss_margin")) o.optDouble("logloss_margin") else marketLl - modelLl
            )
        }

        fun fromModelMetrics(
            model: EdgeModel,
            trainedAt: String,
            version: String = model.version.toString()
        ): EdgeModelManifest {
            val m = model.metrics
            val nSamples = (m["n_holdout"] ?: m["n_samples"] ?: m["n_rows"] ?: 0.0).toInt()
            val nRows = (m["n_rows"] ?: m["n_samples"] ?: nSamples.toDouble()).toInt()
            val nMarkets = (m["n_markets"] ?: 0.0).toInt()
            val nHoldout = (m["n_holdout"] ?: nSamples.toDouble()).toInt()
            return EdgeModelManifest(
                version = version,
                trainedAt = trainedAt,
                nSamples = nSamples,
                nRows = nRows,
                nMarkets = nMarkets,
                nHoldout = nHoldout,
                modelBrier = m["model_brier"] ?: error("model missing model_brier"),
                marketBrier = m["market_brier"] ?: error("model missing market_brier"),
                modelLogLoss = m["model_logloss"] ?: error("model missing model_logloss"),
                marketLogLoss = m["market_logloss"] ?: error("model missing market_logloss"),
                simPnl = m["sim_pnl"],
                simHitRate = m["sim_hit_rate"],
                synthetic = (m["synthetic"] ?: 0.0) > 0.5,
                countsPresent = m.containsKey("n_markets") &&
                    (m.containsKey("n_rows") || m.containsKey("n_samples")) &&
                    (m.containsKey("n_holdout") || m.containsKey("n_samples"))
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
    /** Must match ml/publish_gates.py. */
    const val MIN_PUBLISH_MARKETS = 2000
    const val MIN_PUBLISH_ROWS = 2000
    const val MIN_HOLDOUT_ROWS = 400
    const val MIN_BRIER_MARGIN = 0.010
    const val MIN_LOGLOSS_MARGIN = 0.010

    fun beatsMarket(m: EdgeModelManifest): Boolean {
        if (m.synthetic) return false
        if (!m.countsPresent) return false
        if (m.nMarkets < MIN_PUBLISH_MARKETS) return false
        if (m.nRows < MIN_PUBLISH_ROWS && m.nSamples < MIN_PUBLISH_ROWS) return false
        if (m.nHoldout < MIN_HOLDOUT_ROWS) return false
        if (m.brierMargin < MIN_BRIER_MARGIN) return false
        if (m.logLossMargin < MIN_LOGLOSS_MARGIN) return false
        return true
    }

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
                reason = "Manifest is marked synthetic — falling back to market-only."
            )
        }
        if (!manifest.countsPresent) {
            return ModelActivationDecision(
                activate = false,
                manifest = manifest,
                reason = "Manifest lacks n_markets / n_rows / n_holdout — falling back to market-only."
            )
        }
        if (manifest.nMarkets < MIN_PUBLISH_MARKETS ||
            (manifest.nRows < MIN_PUBLISH_ROWS && manifest.nSamples < MIN_PUBLISH_ROWS) ||
            manifest.nHoldout < MIN_HOLDOUT_ROWS
        ) {
            return ModelActivationDecision(
                activate = false,
                manifest = manifest,
                reason = "Holdout sample too small " +
                    "(markets=${manifest.nMarkets}, rows=${manifest.nRows}, holdout=${manifest.nHoldout}; " +
                    "need ≥$MIN_PUBLISH_MARKETS markets, ≥$MIN_PUBLISH_ROWS rows, ≥$MIN_HOLDOUT_ROWS holdout). " +
                    "Falling back to market-only."
            )
        }
        if (!manifest.beatsMarket) {
            return ModelActivationDecision(
                activate = false,
                manifest = manifest,
                reason = "Holdout does not beat the market by the required margin " +
                    "(Brier ${fmt(manifest.modelBrier)} vs ${fmt(manifest.marketBrier)}, " +
                    "log-loss ${fmt(manifest.modelLogLoss)} vs ${fmt(manifest.marketLogLoss)}). " +
                    "Falling back to market-only."
            )
        }
        return ModelActivationDecision(
            activate = true,
            manifest = manifest,
            reason = "Activated — holdout beats market " +
                "(Brier ${fmt(manifest.modelBrier)} < ${fmt(manifest.marketBrier)}, " +
                "log-loss ${fmt(manifest.modelLogLoss)} < ${fmt(manifest.marketLogLoss)}, " +
                "markets=${manifest.nMarkets}, rows=${manifest.nRows}, holdout=${manifest.nHoldout})."
        )
    }

    fun decideMissingManifest(): ModelActivationDecision =
        ModelActivationDecision(
            activate = false,
            reason = "No manifest with sample counts — falling back to market-only."
        )

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)
}
