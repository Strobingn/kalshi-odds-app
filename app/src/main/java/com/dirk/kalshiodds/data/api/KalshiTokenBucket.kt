package com.dirk.kalshiodds.data.api

import kotlin.math.ceil
import kotlin.math.min

/**
 * 0.3.45 Kalshi API tier (docs.kalshi.com/getting_started/rate_limits, checked 2026-10-09). Budgets are TOKENS per
 * second; most requests cost 10 tokens ([DEFAULT_COST]). Basic / Advanced read buckets hold 3 s of budget; Basic
 * write buckets hold 1 s. Real values come from GET /account/limits when a key is configured.
 */
data class KalshiTier(
    val name: String,
    val readRefill: Double,
    val readCapacity: Double,
    val writeRefill: Double,
    val writeCapacity: Double,
    /** "account/limits" when read from Kalshi, "default" when assumed. */
    val source: String = "default"
) {
    companion object {
        const val DEFAULT_COST = 10.0
        /** CF Benchmarks REST passthrough (if ever used) costs 50 tokens per request. */
        const val CFBENCHMARKS_COST = 50.0
        val BASIC = KalshiTier("Basic", 200.0, 600.0, 100.0, 100.0)
        val TABLE: Map<String, KalshiTier> = listOf(
            BASIC,
            KalshiTier("Advanced", 300.0, 900.0, 300.0, 900.0),
            KalshiTier("Expert", 600.0, 600.0, 600.0, 1_800.0),
            KalshiTier("Premier", 1_200.0, 1_200.0, 1_200.0, 3_600.0),
            KalshiTier("Paragon", 2_400.0, 2_400.0, 2_400.0, 7_200.0),
            KalshiTier("Prime", 4_800.0, 4_800.0, 4_800.0, 14_400.0),
            KalshiTier("Prestige", 12_000.0, 12_000.0, 9_600.0, 28_800.0)
        ).associateBy { it.name.lowercase() }

        fun named(name: String?): KalshiTier = TABLE[name?.trim()?.lowercase()] ?: BASIC
    }
}

/**
 * Process-wide token bucket in Kalshi token units. Reads and writes do not share tokens.
 *
 * 0.3.45 budget: sustained = [TARGET_SHARE] (90 %) of the tier's read refill; burst = 90 % of the bucket capacity,
 * capped at one second of the tier's refill (Basic: 180 tokens/s ≈ 18 reads/s, burst 200 tokens = 20 reads).
 * Adaptive: a REAL Kalshi 429 drops the share to [DROP_SHARE] (70 %) for [DROP_MS], then ramps back to 90 % over
 * [RAMP_MS]. Writes are never queued.
 */
class KalshiTokenBucket(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val randomUnit: () -> Double = { Math.random() },
    tier: KalshiTier = KalshiTier.BASIC,
    private val baseBackoffMs: Long = 250L,
    private val maxBackoffMs: Long = 8_000L
) {
    private val lock = Any()
    @Volatile var tier: KalshiTier = tier
        private set
    private var readTokens = burstTokens(tier)
    private var writeTokens = tier.writeCapacity * TARGET_SHARE
    private var lastReadMs = nowMs()
    private var lastWriteMs = nowMs()
    private var readHoldUntilMs = 0L
    private var writeHoldUntilMs = 0L
    private var readAttempt = 0
    private var droppedAtMs: Long? = null
    private val spent = java.util.ArrayDeque<Pair<Long, Double>>()
    @Volatile var real429s: Int = 0
        private set
    private val real429At = java.util.ArrayDeque<Long>()

    /** 0.3.49: real Kalshi 429s in the last hour (Home status line). */
    fun real429sLastHour(now: Long = nowMs()): Int = synchronized(lock) {
        while (real429At.isNotEmpty() && now - real429At.first() > 3_600_000L) real429At.removeFirst()
        real429At.size
    }

    fun configure(next: KalshiTier) = synchronized(lock) {
        tier = next
        readTokens = readTokens.coerceAtMost(burstTokens(next))
    }

    /** Current share of the tier's read refill (0.9 normally, 0.7 → 0.9 after a real 429). */
    fun share(now: Long = nowMs()): Double = synchronized(lock) { shareLocked(now) }

    private fun shareLocked(now: Long): Double {
        val d = droppedAtMs ?: return TARGET_SHARE
        val since = now - d
        return when {
            since < DROP_MS -> DROP_SHARE
            since < DROP_MS + RAMP_MS -> DROP_SHARE + (TARGET_SHARE - DROP_SHARE) * (since - DROP_MS) / RAMP_MS.toDouble()
            else -> { droppedAtMs = null; TARGET_SHARE }
        }
    }

    fun readTokensPerSec(now: Long = nowMs()): Double = tier.readRefill * share(now)
    fun readsPerSec(now: Long = nowMs()): Double = readTokensPerSec(now) / KalshiTier.DEFAULT_COST
    fun burstReads(): Double = burstTokens(tier) / KalshiTier.DEFAULT_COST

    /**
     * 0 when [cost] read tokens were consumed. Otherwise milliseconds to wait before trying again (no tokens taken).
     */
    fun reserveRead(cost: Double = KalshiTier.DEFAULT_COST): Long {
        synchronized(lock) {
            val now = nowMs()
            if (now < readHoldUntilMs) return (readHoldUntilMs - now).coerceAtLeast(1L)
            val rate = tier.readRefill * shareLocked(now)
            refillRead(now, rate)
            if (readTokens + 1e-9 >= cost) {
                readTokens -= cost
                spent.addLast(now to cost)
                while (spent.isNotEmpty() && now - spent.first().first > 60_000L) spent.removeFirst()
                return 0L
            }
            return ceil((cost - readTokens) / rate * 1_000.0).toLong().coerceAtLeast(1L)
        }
    }

    /** Tokens spent on reads in the last 60 s and the share of the tier's 60 s read budget that is. */
    fun usedLastMinute(now: Long = nowMs()): Pair<Double, Double> = synchronized(lock) {
        val used = spent.filter { now - it.first <= 60_000L }.sumOf { it.second }
        used to (used / (tier.readRefill * 60.0))
    }

    fun tryAcquireWrite(cost: Double = KalshiTier.DEFAULT_COST): Long? {
        synchronized(lock) {
            val now = nowMs()
            if (now < writeHoldUntilMs) return (writeHoldUntilMs - now).coerceAtLeast(1L)
            val rate = tier.writeRefill * TARGET_SHARE
            val elapsed = (now - lastWriteMs).coerceAtLeast(0L)
            writeTokens = (writeTokens + elapsed * rate / 1_000.0).coerceAtMost(tier.writeCapacity * TARGET_SHARE)
            lastWriteMs = now
            if (writeTokens + 1e-9 >= cost) {
                writeTokens -= cost
                return null
            }
            return ceil((cost - writeTokens) / rate * 1_000.0).toLong().coerceAtLeast(1L)
        }
    }

    /**
     * A REAL Kalshi 429 on a read. Kalshi sends no Retry-After and has no penalty window, so: short exponential hold
     * (250 ms → 8 s), and drop to 70 % of the tier for 60 s, then ramp back.
     */
    fun noteRead429(retryAfterMs: Long?): Long {
        synchronized(lock) {
            real429s++
            real429At.addLast(nowMs())
            droppedAtMs = nowMs()
            val wait = backoffDelayMs(readAttempt, retryAfterMs)
            readAttempt = (readAttempt + 1).coerceAtMost(8)
            readHoldUntilMs = maxOf(readHoldUntilMs, nowMs() + wait)
            readTokens = 0.0
            lastReadMs = nowMs()
            return wait
        }
    }

    fun noteReadOk() = synchronized(lock) { readAttempt = 0 }

    fun noteWrite429(retryAfterMs: Long?): Long {
        synchronized(lock) {
            val wait = backoffDelayMs(0, retryAfterMs)
            writeHoldUntilMs = maxOf(writeHoldUntilMs, nowMs() + wait)
            return wait
        }
    }

    fun readHoldRemainingMs(now: Long = nowMs()): Long =
        synchronized(lock) { (readHoldUntilMs - now).coerceAtLeast(0L) }

    fun backoffDelayMs(attemptIndex: Int, retryAfterMs: Long?, unit: Double = randomUnit()): Long {
        val shift = attemptIndex.coerceIn(0, 6)
        val capped = min(baseBackoffMs shl shift, maxBackoffMs)
        val computed = capped + (capped * JITTER_FRACTION * unit.coerceIn(0.0, 1.0)).toLong()
        val retry = retryAfterMs?.takeIf { it > 0L }
        return if (retry != null) maxOf(computed, retry) else computed
    }

    private fun refillRead(now: Long, rate: Double) {
        val elapsed = (now - lastReadMs).coerceAtLeast(0L)
        if (elapsed <= 0L) return
        readTokens = (readTokens + elapsed * rate / 1_000.0).coerceAtMost(burstTokens(tier))
        lastReadMs = now
    }

    companion object {
        const val JITTER_FRACTION = 0.20
        const val WRITE_DENIED = "Rate limited — wait and Approve again"
        const val TARGET_SHARE = 0.90
        const val DROP_SHARE = 0.70
        const val DROP_MS = 60_000L
        const val RAMP_MS = 30_000L

        fun burstTokens(t: KalshiTier): Double = min(t.readCapacity * TARGET_SHARE, t.readRefill)
    }
}

/**
 * 0.3.45 endpoint token costs. Default 10; GET /account/endpoint_costs (authoritative) overrides per path when a key
 * is configured. CF Benchmarks REST passthrough paths cost 50.
 */
object KalshiEndpointCosts {
    @Volatile private var overrides: Map<String, Double> = emptyMap()
    @Volatile var defaultCost: Double = KalshiTier.DEFAULT_COST

    fun apply(default: Double?, costs: Map<String, Double>) {
        default?.takeIf { it > 0 }?.let { defaultCost = it }
        overrides = costs.mapKeys { it.key.uppercase() }
    }

    fun costFor(method: String, path: String): Double {
        val p = path.substringAfter("/trade-api/v2").ifEmpty { path }
        overrides["${method.uppercase()} ${p.uppercase()}"]?.let { return it }
        if (p.contains("cfbenchmarks", ignoreCase = true)) return KalshiTier.CFBENCHMARKS_COST
        return defaultCost
    }
}
