package com.dirk.kalshiodds.signal.latefav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * One paper entry the [LateFavoriteRule] would have taken. Never an order.
 * [pnlWorseUsd] is the same bet sized at ask + 1¢ (slippage stress).
 */
@Serializable
data class LateFavoriteEntry(
    val ticker: String,
    val entryAtMs: Long,
    val tteSeconds: Long,
    val z: Double,
    val side: String,
    val ask: Double,
    val contracts: Int,
    val costUsd: Double,
    val feeUsd: Double,
    val worseAsk: Double,
    val worseContracts: Int = 0,
    val worseCostUsd: Double = 0.0,
    /** "yes" | "no" | "void" once the market settles. */
    val result: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null,
    val pnlWorseUsd: Double? = null,
    val settledAtMs: Long? = null
) {
    val settled: Boolean get() = result != null
}

/**
 * All-time totals, kept separately so capping [LateFavoriteState.entries]
 * never loses the running record.
 */
@Serializable
data class LateFavoriteTotals(
    val wins: Int = 0,
    val losses: Int = 0,
    val voids: Int = 0,
    val pnlUsd: Double = 0.0,
    val pnlWorseUsd: Double = 0.0,
    val costUsd: Double = 0.0,
    val askSum: Double = 0.0
) {
    /** Win/loss bets (voids excluded). */
    val settledBets: Int get() = wins + losses
    val perBetUsd: Double? get() = if (settledBets > 0) pnlUsd / settledBets else null
    val perBetWorseUsd: Double? get() = if (settledBets > 0) pnlWorseUsd / settledBets else null
    val winRate: Double? get() = if (settledBets > 0) wins.toDouble() / settledBets else null
    val avgAsk: Double? get() = if (settledBets > 0) askSum / settledBets else null
}

@Serializable
data class LateFavoriteState(
    /** Newest first, capped at [LateFavoriteLedger.MAX_ENTRIES]. */
    val entries: List<LateFavoriteEntry> = emptyList(),
    val totals: LateFavoriteTotals = LateFavoriteTotals(),
    val startedAtMs: Long? = null
) {
    val openEntries: List<LateFavoriteEntry> get() = entries.filter { !it.settled }
    val openCount: Int get() = entries.count { !it.settled }
}

/**
 * Persisted paper ledger for the late-favorite tracker. Pure logic; storage
 * is the injected [persist] callback ([LateFavoriteStore]). No Kalshi keys,
 * no trade client, no order path.
 */
class LateFavoriteLedger(
    initial: LateFavoriteState = LateFavoriteState(),
    private val persist: (LateFavoriteState) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<LateFavoriteState> = _state.asStateFlow()

    /** Uppercased tickers that already have an entry: the per-tick one-per-market check. */
    @Volatile
    private var entered: Set<String> = initial.entries.map { it.ticker.uppercase() }.toSet()

    fun snapshot(): LateFavoriteState = _state.value

    fun hasEntry(ticker: String): Boolean = ticker.uppercase() in entered

    fun openTickers(): Set<String> =
        _state.value.entries.filter { !it.settled }.map { it.ticker }.toSet()

    /** Log [decision] as a paper entry. Null when the market already has one. */
    fun record(decision: LateFavoriteRule.Decision): LateFavoriteEntry? {
        synchronized(lock) {
            val key = decision.ticker.uppercase()
            if (key in entered) return null
            val cur = _state.value
            val row = LateFavoriteEntry(
                ticker = decision.ticker,
                entryAtMs = decision.nowMs,
                tteSeconds = decision.tteSeconds,
                z = decision.z,
                side = decision.side,
                ask = decision.ask,
                contracts = decision.sized.contracts,
                costUsd = decision.sized.costUsd,
                feeUsd = decision.sized.feeUsd,
                worseAsk = decision.worseAsk,
                worseContracts = decision.worse?.contracts ?: 0,
                worseCostUsd = decision.worse?.costUsd ?: 0.0
            )
            val kept = (listOf(row) + cur.entries).take(MAX_ENTRIES)
            entered = kept.map { it.ticker.uppercase() }.toSet() + key
            publish(cur.copy(entries = kept, startedAtMs = cur.startedAtMs ?: decision.nowMs))
            return row
        }
    }

    /**
     * Settle open entries for [ticker] with Kalshi's result ("yes" / "no" /
     * "void"). Returns the entries settled by this call.
     */
    fun settle(ticker: String, result: String): List<LateFavoriteEntry> {
        synchronized(lock) {
            val outcome = result.lowercase().trim()
            if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
            val cur = _state.value
            if (cur.entries.none { !it.settled && it.ticker.equals(ticker, ignoreCase = true) }) {
                return emptyList()
            }
            val now = nowMs()
            val settled = mutableListOf<LateFavoriteEntry>()
            var totals = cur.totals
            val next = cur.entries.map { e ->
                if (e.settled || !e.ticker.equals(ticker, ignoreCase = true)) return@map e
                val won = when (outcome) {
                    "void" -> null
                    else -> (outcome == "yes") == e.side.equals("YES", ignoreCase = true)
                }
                val pnl = LateFavoriteRule.pnl(
                    LateFavoriteRule.Sized(e.contracts, e.costUsd, e.feeUsd),
                    won
                ) ?: 0.0
                val worse = if (e.worseContracts > 0) {
                    LateFavoriteRule.pnl(LateFavoriteRule.Sized(e.worseContracts, e.worseCostUsd, 0.0), won) ?: 0.0
                } else {
                    // No contract fits at ask + 1¢: the stress case skips the bet.
                    0.0
                }
                val done = e.copy(
                    result = outcome,
                    won = won,
                    pnlUsd = pnl,
                    pnlWorseUsd = worse,
                    settledAtMs = now
                )
                totals = when (won) {
                    null -> totals.copy(voids = totals.voids + 1)
                    else -> totals.copy(
                        wins = totals.wins + (if (won == true) 1 else 0),
                        losses = totals.losses + (if (won == true) 0 else 1),
                        pnlUsd = totals.pnlUsd + pnl,
                        pnlWorseUsd = totals.pnlWorseUsd + worse,
                        costUsd = totals.costUsd + e.costUsd,
                        askSum = totals.askSum + e.ask
                    )
                }
                settled += done
                done
            }
            publish(cur.copy(entries = next, totals = totals))
            return settled
        }
    }

    /** Settle from prediction-log outcomes (same source PaperBook uses). */
    fun settleFromLog(entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>) {
        val open = openTickers()
        if (open.isEmpty()) return
        entries.forEach { e ->
            val o = e.outcome ?: return@forEach
            if (open.any { it.equals(e.ticker, ignoreCase = true) }) settle(e.ticker, o)
        }
    }

    /** Clear the ledger and totals. Paper only — nothing to unwind. */
    fun reset() {
        synchronized(lock) {
            entered = emptySet()
            publish(LateFavoriteState())
        }
    }

    private fun publish(next: LateFavoriteState) {
        _state.value = next
        runCatching { persist(next) }
    }

    companion object {
        /**
         * Rows kept for display / export. ~96 windows a day → ~31 days;
         * [LateFavoriteTotals] keeps the all-time record past the cap.
         */
        const val MAX_ENTRIES = 3000
    }
}
