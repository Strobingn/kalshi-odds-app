package com.dirk.kalshiodds.data.api

import java.util.concurrent.ConcurrentHashMap

/**
 * 0.3.44 singleton guard: one polling loop per Kalshi data type per process. A loop must hold the lease for its
 * type before each iteration's REST work; a second loop of the same type (another ViewModel, the service metadata
 * loop, a WorkManager restart) skips its turn instead of doubling the request rate. Leases expire if the holder
 * stops renewing (cancelled job / dead owner), so a crashed loop never blocks the type forever.
 */
object KalshiPollLoops {
    enum class Type { MARKETS, D3, POSITIONS }

    const val LEASE_MS = 30_000L

    private data class Lease(val owner: String, val untilMs: Long)
    private val leases = ConcurrentHashMap<Type, Lease>()

    /** True when [owner] holds (or just took) the lease for [type]. Renews on success. */
    fun tryRun(type: Type, owner: String, nowMs: Long = System.currentTimeMillis(), leaseMs: Long = LEASE_MS): Boolean {
        while (true) {
            val cur = leases[type]
            if (cur != null && cur.owner != owner && cur.untilMs > nowMs) return false
            val next = Lease(owner, nowMs + leaseMs)
            val ok = if (cur == null) leases.putIfAbsent(type, next) == null else leases.replace(type, cur, next)
            if (ok) return true
        }
    }

    fun release(type: Type, owner: String) {
        leases[type]?.let { if (it.owner == owner) leases.remove(type, it) }
    }

    fun holder(type: Type, nowMs: Long = System.currentTimeMillis()): String? =
        leases[type]?.takeIf { it.untilMs > nowMs }?.owner

    fun lines(nowMs: Long = System.currentTimeMillis()): List<String> =
        Type.values().map { t -> "loop ${t.name.lowercase()}: ${holder(t, nowMs) ?: "idle"}" }

    internal fun resetForTest() = leases.clear()
}

/**
 * 0.3.44 per-market resnapshot cooldown + dedupe for orderbook seq gaps. A ticker gets at most one resnapshot
 * request per [cooldownMs]; tickers already inside the cooldown are dropped from the request, so a repeating (real or
 * false) gap can never turn into a snapshot storm.
 */
class ResnapshotGate(private val cooldownMs: Long = COOLDOWN_MS) {
    private val lastAt = HashMap<String, Long>()
    @Volatile var suppressed: Long = 0L
        private set
    @Volatile var requested: Long = 0L
        private set

    @Synchronized
    fun allow(tickers: Collection<String>, nowMs: Long): List<String> {
        val out = ArrayList<String>()
        for (t in tickers.map { it.uppercase() }.distinct()) {
            val last = lastAt[t]
            if (last != null && nowMs - last < cooldownMs) { suppressed++; continue }
            lastAt[t] = nowMs
            out += t
        }
        requested += out.size
        return out
    }

    @Synchronized fun reset() = lastAt.clear()

    companion object { const val COOLDOWN_MS = 30_000L }
}
