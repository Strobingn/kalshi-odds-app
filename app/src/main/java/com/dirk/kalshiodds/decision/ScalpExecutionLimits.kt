package com.dirk.kalshiodds.decision

import kotlin.math.floor

/** App-wide paper scalper rules, independent of family, hold timer, manual settings or V2 tuning. */
class ScalpExecutionLimits {
    fun buyAllowed(price: Double?) = price != null && price.isFinite() && price > 0 && price <= MAX_BUY + 1e-9

    fun step(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        if (t.state == ScalpState.PENDING_ENTRY && !buyAllowed(t.restingLimit ?: t.signalAsk))
            return t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; cancelled: 50c maximum buy")
        if (q.nowMs >= q.closeMs || !ScalpManual.fresh(q) || t.entryPrice == null || t.remaining <= 0 ||
            (t.state != ScalpState.OPEN && t.state != ScalpState.PENDING_EXIT)) return null
        val bid = q.bid(t.side)?.takeIf { it.isFinite() && it >= 0 && it < 1 } ?: return null
        if (!owns(t) && bid > (t.entryPrice - STOP).coerceAtLeast(0.0) + 1e-9) return null
        val base = if (owns(t)) t else t.copy(state = ScalpState.PENDING_EXIT, exitReason = REASON, exitDecidedAtMs = q.nowMs)
        val last = ScalpV2.savedNumber(base.note, "limitbook", 0.0).toLong()
        if (q.bookAtMs <= last) return base.takeIf { it != t }
        // Consume each displayed bid level once per fresh book; a stop trigger is not a guaranteed fill price.
        var n = 0; var usd = 0.0; var fee = 0.0
        for ((px, size) in q.bidLadder(t.side).sortedByDescending { it.first }) {
            if (!px.isFinite() || !size.isFinite() || px < 0 || px >= 1 || size <= 0) continue
            val take = minOf(t.remaining - n, floor(size.coerceAtMost(Int.MAX_VALUE.toDouble())).toInt())
            if (take > 0) { n += take; usd += take * px; fee += ScalpRule.orderFee(take, px) }
            if (n == t.remaining) break
        }
        if (n == 0) return base.takeIf { it != t }
        val next = base.copy(soldContracts = t.soldContracts + n, proceedsUsd = t.proceedsUsd + usd,
            exitFeeUsd = t.exitFeeUsd + fee, note = ScalpV2.tag(base.note, "limitbook", q.bookAtMs.toString()))
        return if (next.remaining > 0) next else next.copy(state = ScalpState.CLOSED, closedAtMs = q.nowMs,
            netUsd = next.proceedsUsd - next.exitFeeUsd - next.entryCostUsd)
    }

    companion object {
        const val MAX_BUY = .50
        const val STOP = .02
        const val REASON = "2c global stop"
        fun owns(t: ScalpTrade) = t.exitReason == REASON
    }
}
