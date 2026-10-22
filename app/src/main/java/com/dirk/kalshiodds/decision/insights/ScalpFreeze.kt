package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade

/**
 * One paper freeze (1.0.15). A frozen strategy stops opening new paper trades and stays on the Scalp tab
 * with one [BADGE]. Nothing is removed.
 *
 * Frozen when any of these hold, unless Force trade is set:
 * - the 1.0.14 default loser list
 * - the deep-loss rule: newest [WINDOW] trades net below [MAX_NET_USD] and below [MAX_PER_TRADE_USD] each
 * - the AI rule: newest [WINDOW] trades net more than [AiBench.CLEAR_LOSS_USD] down
 *
 * A numeric freeze (not the default list) can come back when the last [WINDOW] shadow trades are net positive.
 * Force trade unfreezes. Force bench stays a force bench and is not rewritten.
 */
object ScalpFreeze {
    const val BADGE = "FROZEN - losing"
    /** Deep-loss rule: newest [WINDOW] settled real trades net below [MAX_NET_USD] AND below [MAX_PER_TRADE_USD] a trade. */
    const val WINDOW = 30
    const val MAX_NET_USD = -10.0
    const val MAX_PER_TRADE_USD = -0.25

    /**
     * 1.0.10 hid these. 1.0.21 shows them again and freezes them: history stays, no new paper trade
     * unless Force trade. mkdip's maker variant was still booking while hidden.
     */
    /** 10-21: former restores are removed from the registry, not frozen. */
    val RESTORED: Set<String> = emptySet()

    /** True when a restored scalper must not open a new paper trade. Force trade ("ACTIVE") wins. */
    fun blocksRestored(code: String?, override: String?): Boolean = code != null && code in RESTORED && override != "ACTIVE"

    /** No default freeze list. Losers are removed. A kept scalper can still auto-freeze on its own paper. */
    val DEFAULT_FROZEN: Set<String> = emptySet()

    fun isFrozen(d: BenchDecision?): Boolean = d != null && !d.trading && d.reason.startsWith(BADGE)

    /** (net, n) of the newest [window] settled real trades when the auto rule freezes, else null (warm-up or not losing enough). */
    fun autoFreeze(real: List<ScalpTrade>, window: Int = WINDOW): Pair<Double, Int>? {
        val last = real.filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
            .sortedByDescending { it.closedAtMs ?: 0L }.take(window.coerceAtLeast(1))
        if (last.size < window) return null
        val net = last.sumOf { it.netUsd!! }
        return if (net < MAX_NET_USD && net / last.size < MAX_PER_TRADE_USD) net to last.size else null
    }

    /**
     * Always null. A losing book is not a freeze. [autoFreeze] still describes the old
     * deep-loss numbers for tests; nothing applies them.
     */
    @Suppress("UNUSED_PARAMETER")
    fun reason(code: String, real: List<ScalpTrade>, shadow: List<ScalpTrade> = emptyList(), wasFrozen: Boolean = false): String? = null

    /**
     * Freeze on top of bench decisions. Force trade and Force bench are never rewritten.
     * [previous] is the last published decision, so a numeric freeze can wait for shadow improvement.
     */
    @Suppress("UNUSED_PARAMETER")
    fun apply(
        decisions: Map<String, BenchDecision>,
        real: Map<String, List<ScalpTrade>>,
        overrides: Map<String, String>,
        shadow: Map<String, List<ScalpTrade>> = emptyMap(),
        previous: Map<String, BenchDecision> = emptyMap()
    ): Map<String, BenchDecision> {
        // Automatic freeze is off for every scalper, including -v1/-v2/-v3.
        // Force bench stays whatever the caller already decided.
        return decisions
    }
}
