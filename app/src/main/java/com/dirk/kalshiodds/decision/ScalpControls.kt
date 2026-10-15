package com.dirk.kalshiodds.decision

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Swarm-style controls for the Kashi V2 paper lane. Zero limits mean no user cap. */
@Serializable
data class ScalpControls(
    val enabled: Boolean = true, val killSwitch: Boolean = false, val adaptive: Boolean = true,
    val manual: Boolean = false, val maxAskCents: Int = 50, val stopCents: Int = 6,
    val takeProfitCents: Int = 0, val trailCents: Int = 2, val armCents: Int = 4,
    val contracts: Int = 10, val maxStakeUsd: Double = 0.0, val maxHoldSeconds: Int = 0,
    val maxTradesPerHour: Int = 0, val maxDailyLossUsd: Double = 0.0, val maxOpen: Int = 0,
    val windowSeconds: Int = 30, val dipCents: Double = 0.0,
    val disabledStrategies: Set<String> = emptySet()
) {
    fun normalized() = copy(maxAskCents = maxAskCents.coerceIn(1, 99), stopCents = stopCents.coerceIn(1, 99),
        takeProfitCents = takeProfitCents.coerceIn(0, 99), trailCents = trailCents.coerceIn(1, 20),
        armCents = armCents.coerceIn(1, 99), contracts = contracts.coerceIn(1, 1000),
        maxStakeUsd = maxStakeUsd.takeIf { it.isFinite() }?.coerceIn(0.0, 100_000.0) ?: 0.0,
        maxHoldSeconds = maxHoldSeconds.coerceIn(0, 3600), maxTradesPerHour = maxTradesPerHour.coerceIn(0, 10000),
        maxDailyLossUsd = maxDailyLossUsd.takeIf { it.isFinite() }?.coerceIn(0.0, 100_000.0) ?: 0.0,
        maxOpen = maxOpen.coerceIn(0, 1000), windowSeconds = windowSeconds.coerceIn(10, 600),
        dipCents = dipCents.takeIf { it.isFinite() }?.coerceIn(0.0, 30.0) ?: 0.0)
    fun permits(modelCode: String) = enabled && !killSwitch && modelCode !in disabledStrategies
    fun plan(base: ScalpV2.Plan): ScalpV2.Plan = if (!manual) base else base.copy(
        spec = base.spec.copy(maxAsk = maxAskCents / 100.0, trail = trailCents / 100.0, clip = contracts),
        stop = stopCents / 100.0, arm = armCents / 100.0, adjusted = false, note = "v2ai=manual; user parameters")
    fun entryBlock(modelCode: String, open: Int, entriesLastHour: Int, todayNet: Double): String? = when {
        !permits(modelCode) -> "V2 entries paused or strategy disabled"
        maxOpen > 0 && open >= maxOpen -> "V2 open-position limit"
        maxTradesPerHour > 0 && entriesLastHour >= maxTradesPerHour -> "V2 hourly entry limit"
        maxDailyLossUsd > 0 && todayNet <= -maxDailyLossUsd -> "V2 daily loss limit"
        else -> null
    }
    fun affordableContracts(price: Double, wanted: Int): Int {
        if (maxStakeUsd <= 0) return wanted
        var n = minOf(wanted, (maxStakeUsd / price).toInt())
        while (n > 0 && n * price + ScalpRule.orderFee(n, price) > maxStakeUsd + 1e-9) n--
        return n
    }
    fun walkBuy(levels: List<Pair<Double, Double>>, wanted: Int, limit: Double): Triple<Int, Double, Double> {
        var count = 0; var value = 0.0; var fees = 0.0
        for ((price, size) in levels.sortedBy { it.first }) {
            if (!price.isFinite() || price <= 0 || price > limit || !size.isFinite() || size < 1) continue
            var lo = 0
            var hi = minOf(wanted - count, kotlin.math.floor(size).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()).coerceAtLeast(0)
            while (lo < hi) {
                val mid = lo + (hi - lo + 1) / 2
                val cost = value + fees + mid * price + ScalpRule.orderFee(mid, price)
                if (maxStakeUsd == 0.0 || cost <= maxStakeUsd + 1e-9) lo = mid else hi = mid - 1
            }
            if (lo > 0) { count += lo; value += lo * price; fees += ScalpRule.orderFee(lo, price) }
            if (count >= wanted) break
        }
        return Triple(count, value, fees)
    }
    fun dipOk(prices: List<Double>, ask: Double): Boolean {
        if (dipCents <= 0) return true
        if (prices.size < 3) return false
        val ema = prices.drop(1).fold(prices.first()) { a, b -> a + (b - a) / 3.0 }
        return ema - ask >= dipCents / 100.0 - 1e-9
    }
}

@Serializable
data class ScalpTradeFeedback(val trades: Int = 0, val wins: Int = 0, val meanNetPerContract: Double = 0.0,
    val recentNetPerContract: Double = 0.0, val lastAtMs: Long = 0L) {
    fun observe(t: ScalpTrade): ScalpTradeFeedback {
        val net = (t.netUsd ?: return this) / t.contracts.coerceAtLeast(1)
        if (!net.isFinite()) return this
        return copy(trades = trades + 1, wins = wins + if (net > 0) 1 else 0,
            meanNetPerContract = meanNetPerContract + (net - meanNetPerContract) / (trades + 1),
            recentNetPerContract = if (trades == 0) net else recentNetPerContract * .9 + net * .1,
            lastAtMs = t.closedAtMs ?: t.signalAtMs)
    }
}

interface ScalpControlsRepository {
    val state: StateFlow<ScalpControls>
    fun save(value: ScalpControls)
    fun entries(now: Long): Int
    fun recordEntry(at: Long)
    fun feedback(code: String): ScalpTradeFeedback
    fun recordOutcome(code: String, trade: ScalpTrade)
}
class MemoryScalpControls(initial: ScalpControls = ScalpControls()) : ScalpControlsRepository {
    override val state = MutableStateFlow(initial.normalized())
    private val times = linkedMapOf<Long, Int>()
    private val lessons = mutableMapOf<String, ScalpTradeFeedback>()
    private val learned = mutableSetOf<String>()
    override fun feedback(code: String) = synchronized(lessons) { lessons[code] ?: ScalpTradeFeedback() }
    override fun recordOutcome(code: String, trade: ScalpTrade) = synchronized(lessons) {
        if (learned.add(trade.id)) lessons[code] = feedback(code).observe(trade)
        if (learned.size > 20000) learned.remove(learned.first())
    }
    override fun save(value: ScalpControls) { state.value = value.normalized() }
    override fun entries(now: Long): Int = synchronized(times) { times.filterKeys { it in (now / 1000 - 3600)..(now / 1000) }.values.sum() }
    override fun recordEntry(at: Long) = synchronized(times) {
        val sec = at / 1000
        times[sec] = (times[sec] ?: 0) + 1
        times.keys.removeAll { it < sec - 3600 }
    }
}
class SavedScalpControls(context: Context) : ScalpControlsRepository {
    private val prefs = context.applicationContext.getSharedPreferences("kashi_v2_controls", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    override val state = MutableStateFlow(read())
    private fun read() = runCatching { json.decodeFromString<ScalpControls>(prefs.getString("settings", "{}")!!).normalized() }.getOrDefault(ScalpControls())
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "settings") state.value = read() }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    override fun save(value: ScalpControls) {
        val clean = value.normalized()
        prefs.edit().putString("settings", json.encodeToString(clean)).apply()
        state.value = clean
    }
    private fun buckets() = prefs.getString("entry-buckets", "").orEmpty().split(',').mapNotNull { token ->
        val pair = token.split(':')
        val at = pair.getOrNull(0)?.toLongOrNull(); val n = pair.getOrNull(1)?.toIntOrNull()
        if (at != null && n != null && n > 0) at to n else null
    }.toMap().toMutableMap()
    @Synchronized override fun entries(now: Long): Int = buckets().filterKeys { it in (now / 1000 - 3600)..(now / 1000) }.values.sum()
    @Synchronized override fun recordEntry(at: Long) {
        val sec = at / 1000
        val times = buckets().filterKeys { it in (sec - 3600)..sec }.toMutableMap()
        times[sec] = (times[sec] ?: 0) + 1
        prefs.edit().putString("entry-buckets", times.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }
    override fun feedback(code: String): ScalpTradeFeedback = runCatching {
        json.decodeFromString<ScalpTradeFeedback>(prefs.getString("feedback:$code", "{}")!!)
    }.getOrDefault(ScalpTradeFeedback())
    @Synchronized override fun recordOutcome(code: String, trade: ScalpTrade) {
        val key = "learned:$code"
        val seen = prefs.getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }
        if (trade.id in seen) return
        prefs.edit().putString("feedback:$code", json.encodeToString(feedback(code).observe(trade)))
            .putString(key, (seen + trade.id).takeLast(256).joinToString(",")).apply()
    }
    fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}
