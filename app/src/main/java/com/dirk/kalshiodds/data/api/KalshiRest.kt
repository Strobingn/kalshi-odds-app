package com.dirk.kalshiodds.data.api

/**
 * The single process-wide Kalshi REST budget. Every OkHttp client that
 * talks to Kalshi must use [gate].
 */
object KalshiRest {
    val bucket: KalshiTokenBucket = KalshiTokenBucket()
    val gate: KalshiHttpGate = KalshiHttpGate(bucket)

    /** 0.3.45: tier, budget and current use, for Data → Kalshi request counters. */
    fun budgetLines(now: Long = System.currentTimeMillis()): List<String> {
        val t = bucket.tier
        val (used, pct) = bucket.usedLastMinute(now)
        return listOf(
            String.format(java.util.Locale.US, "Tier: %s (%s) · read %.0f tokens/s, bucket %.0f · write %.0f/s",
                t.name, t.source, t.readRefill, t.readCapacity, t.writeRefill),
            String.format(java.util.Locale.US, "Budget: %.0f%% of tier = %.1f reads/s, burst %.0f reads%s",
                bucket.share(now) * 100, bucket.readsPerSec(now), bucket.burstReads(),
                if (bucket.share(now) < KalshiTokenBucket.TARGET_SHARE - 1e-9) " (dropped after a real Kalshi 429)" else ""),
            String.format(java.util.Locale.US, "Used last 60 s: %.0f tokens = %.1f%% of tier read budget · %.2f reads/s · real 429s %d",
                used, pct * 100, used / KalshiEndpointCosts.defaultCost / 60.0, bucket.real429s)
        )
    }

    /** Parse GET /account/limits JSON → tier (null when the shape is unexpected). */
    fun tierFromLimits(json: kotlinx.serialization.json.JsonObject): KalshiTier? = runCatching {
        fun num(o: kotlinx.serialization.json.JsonElement?, k: String) =
            (o as? kotlinx.serialization.json.JsonObject)?.get(k)?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toDouble() }
        val name = (json["usage_tier"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val base = KalshiTier.named(name)
        KalshiTier(
            name = name.replaceFirstChar { it.uppercase() },
            readRefill = num(json["read"], "refill_rate") ?: base.readRefill,
            readCapacity = num(json["read"], "bucket_capacity") ?: base.readCapacity,
            writeRefill = num(json["write"], "refill_rate") ?: base.writeRefill,
            writeCapacity = num(json["write"], "bucket_capacity") ?: base.writeCapacity,
            source = "account/limits"
        )
    }.getOrNull()

    fun costsFrom(json: kotlinx.serialization.json.JsonObject): Pair<Double?, Map<String, Double>> {
        val def = (json["default_cost"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        val arr = json["endpoint_costs"] as? kotlinx.serialization.json.JsonArray ?: return def to emptyMap()
        val map = arr.mapNotNull { e ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val m = (o["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val p = (o["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val c = (o["cost"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            "$m $p" to c
        }.toMap()
        return def to map
    }
}
