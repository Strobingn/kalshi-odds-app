package com.dirk.kalshiodds.decision

/**
 * Why a paper scalper did not open a clip on the latest tick, and how many times
 * that gate fired in the current 15-minute window. Paper diagnostics only.
 */
class ScalpGateLog {
    data class Hit(
        val windowKey: String,
        val reason: String,
        val count: Int,
        val blocked: Boolean,
        val byReason: Map<String, Int> = emptyMap()
    )

    data class SyncRow(val windowKey: String, val code: String, val reason: String, val count: Int)

    private val hits = HashMap<String, Hit>()

    fun block(code: String, windowKey: String, reason: String) {
        if (code.isBlank() || reason.isBlank()) return
        synchronized(hits) {
            val prev = hits[code]
            val sameWindow = prev != null && prev.windowKey == windowKey
            val prior = if (sameWindow) prev.byReason else emptyMap()
            val n = (prior[reason] ?: 0) + 1
            val show = if (sameWindow && prev.reason == reason) prev.count + 1 else n
            hits[code] = Hit(windowKey, reason, show, blocked = true, byReason = prior + (reason to n))
        }
    }

    /** The latest evaluation opened a clip. Earlier block counts stay for the sync payload. */
    fun entered(code: String, windowKey: String) {
        synchronized(hits) {
            val prev = hits[code]
            val prior = if (prev != null && prev.windowKey == windowKey) prev.byReason else emptyMap()
            hits[code] = Hit(windowKey, prev?.reason ?: "", prev?.count ?: 0, blocked = false, byReason = prior)
        }
    }

    fun noteOutage(windowKey: String, codes: Collection<String>) {
        codes.forEach { block(it, windowKey, OUTAGE) }
    }

    /**
     * Last blocking gate for [code] in [windowKey]. An outage recorded before the
     * next window id is known still shows, so a silent hour stays explainable.
     */
    fun line(code: String, windowKey: String? = null): String? = synchronized(hits) {
        val h = hits[code] ?: return null
        if (!h.blocked || h.reason.isBlank()) return null
        if (windowKey != null && h.windowKey != windowKey && h.reason != OUTAGE) return null
        return "No entry · ${h.reason} · ${h.count} this window"
    }

    fun snapshot(codes: Collection<String>): List<SyncRow> = synchronized(hits) {
        codes.mapNotNull { code ->
            val h = hits[code] ?: return@mapNotNull null
            if (h.byReason.isEmpty()) return@mapNotNull null
            h.byReason.map { (reason, n) -> SyncRow(h.windowKey, code, reason, n) }
        }.flatten()
    }

    companion object {
        const val OUTAGE = "Kalshi data stale or outage"
    }
}

/** Free paper cash the scalp book may commit. Bound after the paper ledger exists. */
class ScalpBankroll {
    @Volatile var read: () -> Double = { com.dirk.kalshiodds.signal.config.SignalConstants.PAPER_START_USD }
    val usd: Double get() = runCatching { read() }.getOrDefault(0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
}
