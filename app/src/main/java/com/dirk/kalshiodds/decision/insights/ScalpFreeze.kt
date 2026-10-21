package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.AiBench
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.util.Locale

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

    /** Why [code] is frozen, or null. [wasFrozen] lets a numeric freeze stay put until shadow paper improves. */
    fun reason(code: String, real: List<ScalpTrade>, shadow: List<ScalpTrade> = emptyList(), wasFrozen: Boolean = false): String? {
        if (code in DEFAULT_FROZEN) {
            val note = com.dirk.kalshiodds.decision.ScalpRetune.cardNote(code)
            if (note != null && note.startsWith("Frozen")) return "$BADGE · $note · Force trade unfreezes"
            return "$BADGE · 1.0.14 default (lost money Oct 17–19) · Force trade unfreezes"
        }
        val cleared = shadowClears(shadow)
        autoFreeze(real)?.let { (net, n) ->
            if (cleared) return null
            return String.format(Locale.US, "%s · last %d trades %+.2f USD (%+.2f/trade) · Force trade unfreezes", BADGE, n, net, net / n)
        }
        val nets = netsOf(real)
        val call = AiBench.decide(wasFrozen, nets, netsOf(shadow), WINDOW)
        if (call.unbench || cleared) return null
        if (call.bench || wasFrozen) {
            return String.format(Locale.US, "%s · paper net %+.2f over last %d trades · Force trade unfreezes", BADGE, call.netUsd, WINDOW)
        }
        return null
    }

    /**
     * Freeze on top of bench decisions. Force trade and Force bench are never rewritten.
     * [previous] is the last published decision, so a numeric freeze can wait for shadow improvement.
     */
    fun apply(
        decisions: Map<String, BenchDecision>,
        real: Map<String, List<ScalpTrade>>,
        overrides: Map<String, String>,
        shadow: Map<String, List<ScalpTrade>> = emptyMap(),
        previous: Map<String, BenchDecision> = emptyMap()
    ): Map<String, BenchDecision> {
        val out = LinkedHashMap(decisions)
        for (code in DEFAULT_FROZEN + real.keys + shadow.keys + previous.keys) {
            if (com.dirk.kalshiodds.decision.ScalpModels.modelByCode(code) == null) continue
            if (overrides[code] == "ACTIVE" || overrides[code] == "BENCHED") continue
            val rows = real[code].orEmpty()
            val was = isFrozen(previous[code]) && code !in DEFAULT_FROZEN
            val why = reason(code, rows, shadow[code].orEmpty(), was) ?: continue
            val d = out[code]
            out[code] = d?.copy(status = BenchStatus.BENCHED, reason = why)
                ?: BenchDecision(code, BenchStatus.BENCHED, rows.size, rows.sumOf { it.netUsd ?: 0.0 }, 0.0, why)
        }
        return out
    }

    private fun shadowClears(shadow: List<ScalpTrade>): Boolean {
        val nets = netsOf(shadow)
        return nets.size >= WINDOW && nets.takeLast(WINDOW).sum() > 0.0
    }

    private fun netsOf(rows: List<ScalpTrade>): List<Double> =
        rows.filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
            .sortedBy { it.closedAtMs ?: 0L }
            .map { it.netUsd!! }
}
