package com.dirk.kalshiodds.decision

import org.json.JSONObject

/**
 * Favourite-longshot residual model, exported by `ml/train_longshot.py` as
 * app-evaluable JSON (schema version [SCHEMA_VERSION]).
 *
 * Buckets cover the price paid for a side (0–1). residual = realized win
 * rate − mean price paid. `net_ci_lo` is the market-clustered bootstrap
 * 95% lower bound of net P&L per contract after the 7% taker fee (rounded
 * up). A longshot bucket is validated only with ≥ min_n settled markets
 * and net_ci_lo > 0. Anything else → "NO BET — longshot not validated".
 */
data class LongshotResidualModel(
    val schemaVersion: Int,
    val version: String,
    val minN: Int,
    val synthetic: Boolean,
    val buckets: List<Bucket>
) {
    data class Bucket(
        val lo: Double,
        val hi: Double,
        val n: Int,
        val priceMean: Double,
        val winRate: Double,
        val residual: Double,
        val netPerContract: Double,
        val netCiLo: Double,
        val netCiHi: Double
    ) {
        fun contains(price: Double): Boolean = price >= lo - 1e-12 && price < hi - 1e-12
    }

    fun bucketFor(price: Double?): Bucket? {
        val p = price?.takeIf { it.isFinite() } ?: return null
        return buckets.firstOrNull { it.contains(p) } ?: buckets.lastOrNull { p >= it.lo && p <= it.hi }
    }

    fun residual(price: Double?): Double? = bucketFor(price)?.residual

    /** Validated: real data, enough markets, fee-aware CI lower bound above zero. */
    fun validated(price: Double?): Boolean {
        if (synthetic || schemaVersion != LongshotResidual.SCHEMA_VERSION) return false
        val b = bucketFor(price) ?: return false
        return b.n >= minN && b.netCiLo > 0.0
    }

    companion object {
        val EMPTY = LongshotResidualModel(LongshotResidual.SCHEMA_VERSION, "none", 300, true, emptyList())
    }
}

object LongshotResidual {
    const val SCHEMA_VERSION = 1
    const val ASSET = "longshot_residual.json"

    /** Bundled model; EMPTY (nothing validated → longshots are NO BET) if missing or malformed. */
    fun loadAsset(context: android.content.Context): LongshotResidualModel =
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()?.let { parse(it) } ?: LongshotResidualModel.EMPTY

    fun parse(json: String): LongshotResidualModel? = runCatching {
        val o = JSONObject(json)
        val schema = o.optInt("schema_version", -1)
        if (schema != SCHEMA_VERSION) return null
        val arr = o.optJSONArray("buckets")
        val buckets = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val b = arr!!.optJSONObject(i) ?: return@mapNotNull null
            LongshotResidualModel.Bucket(
                lo = b.optDouble("lo"),
                hi = b.optDouble("hi"),
                n = b.optInt("n"),
                priceMean = b.optDouble("price_mean", Double.NaN),
                winRate = b.optDouble("win_rate", Double.NaN),
                residual = b.optDouble("residual", 0.0),
                netPerContract = b.optDouble("net_per_contract", Double.NaN),
                netCiLo = b.optDouble("net_ci_lo", Double.NEGATIVE_INFINITY),
                netCiHi = b.optDouble("net_ci_hi", Double.NaN)
            ).takeIf { it.lo.isFinite() && it.hi.isFinite() && it.hi > it.lo }
        }.sortedBy { it.lo }
        LongshotResidualModel(
            schemaVersion = schema,
            version = o.optString("version").ifBlank { "unknown" },
            minN = o.optInt("min_n", 300).coerceAtLeast(1),
            synthetic = o.optBoolean("synthetic", true),
            buckets = buckets
        )
    }.getOrNull()
}
