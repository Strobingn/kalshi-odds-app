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

    /** 1.0.14 default: money losers in Dirk's Oct 17–19 paper history (Supabase) and v2 copies of those originals. */
    val DEFAULT_FROZEN: Set<String> = setOf(
        "momonotp", "momo", "momoh60", "sf59", "sf60", "sf56", "sf57", "sf58", "fcride", "r-hold", "cfr", "dip",
        "sf44", "sf52", "sf53", "sf54", "sf55", "v2-sf52", "v2-sf53", "v2-sf54", "v2-sf55",
        "v2-sf30", "v2-sf32", "v2-diph60", "v2-mkdip", "v2-dipnotp", "v2-momo", "v2-momonotp", "v2-momoh60",
        "v2-sf56", "v2-sf57", "v2-sf58", "v2-sf59", "v2-sf60"
    )

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
        if (code in DEFAULT_FROZEN) return "$BADGE · 1.0.14 default (lost money Oct 17–19) · Force trade unfreezes"
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
