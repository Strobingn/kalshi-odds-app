package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.util.Locale

/**
 * Kalshi Scalper 1.0.14 freeze (paper only). Losing strategies stop opening NEW paper trades (their signals are kept as
 * bench shadows) but nothing is removed: every frozen strategy stays on the Scalp tab and scorecards with its history and
 * a "FROZEN - losing" badge. Manual wins: Force trade (override ACTIVE) unfreezes, Force bench stays benched.
 * Kept separate from [ScalpBench] auto-bench; [ScalpInsights.recompute] applies it on top of the bench decisions.
 */
object ScalpFreeze {
    const val BADGE = "FROZEN - losing"
    /** Auto rule: newest [WINDOW] settled real trades net below [MAX_NET_USD] AND below [MAX_PER_TRADE_USD] a trade. */
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

    /** Why [code] is frozen, or null. */
    fun reason(code: String, real: List<ScalpTrade>): String? {
        if (code in DEFAULT_FROZEN) return "$BADGE · 1.0.14 default (lost money Oct 17–19) · Force trade unfreezes"
        val (net, n) = autoFreeze(real) ?: return null
        return String.format(Locale.US, "%s · last %d trades %+.2f USD (%+.2f/trade) · Force trade unfreezes", BADGE, n, net, net / n)
    }

    /** Freeze on top of bench decisions. A manual override (Force trade / Force bench) is never changed. */
    fun apply(decisions: Map<String, BenchDecision>, real: Map<String, List<ScalpTrade>>, overrides: Map<String, String>): Map<String, BenchDecision> {
        val out = LinkedHashMap(decisions)
        for (code in DEFAULT_FROZEN + real.keys) {
            if (overrides[code] == "ACTIVE" || overrides[code] == "BENCHED") continue
            val rows = real[code].orEmpty()
            val why = reason(code, rows) ?: continue
            val d = out[code]
            out[code] = d?.copy(status = BenchStatus.BENCHED, reason = why)
                ?: BenchDecision(code, BenchStatus.BENCHED, rows.size, rows.sumOf { it.netUsd ?: 0.0 }, 0.0, why)
        }
        return out
    }
}
