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
        val byReason: Map<String, Int> = emptyMap(),
        val regime: String = "",
        val evidence: String = ""
    )

    /**
     * One counted reason. [latest] is the reason currently on screen.
     * Remote sync collapses these to one row per window and scalper.
     */
    data class SyncRow(
        val windowKey: String,
        val code: String,
        val reason: String,
        val count: Int,
        val latest: Boolean = false,
        val regime: String = "",
        val evidence: String = ""
    )

    private val hits = HashMap<String, Hit>()

    fun block(code: String, windowKey: String, reason: String) {
        if (code.isBlank() || reason.isBlank()) return
        synchronized(hits) {
            val prev = hits[code]
            val sameWindow = prev != null && prev.windowKey == windowKey
            val prior = if (sameWindow) prev.byReason else emptyMap()
            val n = (prior[reason] ?: 0) + 1
            val show = if (sameWindow && prev.reason == reason) prev.count + 1 else n
            val regime = VolRegime.current(windowKey)
            hits[code] = Hit(
                windowKey, reason, show, blocked = true, byReason = prior + (reason to n),
                regime = regime, evidence = if (sameWindow) prev?.evidence.orEmpty() else ""
            )
        }
    }

    /**
     * Count a reason on the gate row without treating it as the latest block.
     * Log-only Fibonacci uses this so the flag is on the sync payload while the clip still opens.
     */
    fun flag(code: String, windowKey: String, reason: String) {
        if (code.isBlank() || reason.isBlank()) return
        synchronized(hits) {
            val prev = hits[code]
            val sameWindow = prev != null && prev.windowKey == windowKey
            val prior = if (sameWindow) prev.byReason else emptyMap()
            val n = (prior[reason] ?: 0) + 1
            val showReason = if (sameWindow && prev.blocked) prev.reason else prev?.reason ?: ""
            val showCount = if (sameWindow && prev.blocked) prev.count else prev?.count ?: 0
            hits[code] = Hit(
                windowKey,
                showReason,
                showCount,
                blocked = sameWindow && prev.blocked,
                byReason = prior + (reason to n),
                regime = VolRegime.current(windowKey),
                evidence = if (sameWindow) prev?.evidence.orEmpty() else ""
            )
        }
    }

    /** The latest evaluation opened a clip. Earlier block counts stay for the sync payload. */
    fun entered(code: String, windowKey: String) {
        synchronized(hits) {
            val prev = hits[code]
            val prior = if (prev != null && prev.windowKey == windowKey) prev.byReason else emptyMap()
            hits[code] = Hit(
                windowKey, prev?.reason ?: "", prev?.count ?: 0, blocked = false, byReason = prior,
                regime = VolRegime.current(windowKey),
                evidence = if (prev != null && prev.windowKey == windowKey) prev.evidence else ""
            )
        }
    }

    /** Latest evidence card for this scalper. Does not change the counted block reason. */
    fun noteContext(code: String, windowKey: String, regime: String, evidence: String) {
        if (code.isBlank()) return
        synchronized(hits) {
            val prev = hits[code] ?: return
            if (prev.windowKey != windowKey) return
            val reg = when (regime) {
                VolRegime.CALM, VolRegime.NORMAL, VolRegime.CHOPPY -> regime
                else -> prev.regime
            }
            hits[code] = prev.copy(regime = reg, evidence = evidence.take(2_000))
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
        val base = "No entry · ${h.reason} · ${h.count} this window"
        val extra = ArrayList<String>(2)
        if (h.regime == VolRegime.CALM || h.regime == VolRegime.NORMAL || h.regime == VolRegime.CHOPPY) {
            extra += "regime ${h.regime}"
        }
        if (h.evidence.isNotBlank()) extra += h.evidence
        return if (extra.isEmpty()) base else base + "\n" + extra.joinToString("\n")
    }

    fun snapshot(codes: Collection<String>): List<SyncRow> = synchronized(hits) {
        codes.mapNotNull { code ->
            val h = hits[code] ?: return@mapNotNull null
            if (h.byReason.isEmpty()) return@mapNotNull null
            h.byReason.map { (reason, n) ->
                val latest = reason == h.reason
                SyncRow(
                    h.windowKey, code, reason, n, latest = latest,
                    regime = if (latest) h.regime else "",
                    evidence = if (latest) h.evidence.take(160) else ""
                )
            }
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
