package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.ScalpControlSpec.Key as K
import kotlin.math.floor

/** Manual paper execution shared by all algorithm families; never calls an order API. */
object ScalpManual {
    fun fresh(q: ScalpRule.Quote): Boolean = !q.bookGap && q.nowMs - q.bookAtMs in 0..3000L
    fun entryAllowed(t: ScalpTrade, q: ScalpRule.Quote, c: ScalpControlSpec.Config): Boolean {
        val ask = q.ask(t.side) ?: return false
        val bid = q.bid(t.side) ?: return false
        return c.enabled && fresh(q) && ask.isFinite() && bid.isFinite() && ask > bid &&
            ask >= c[K.MIN_ASK] / 100 - 1e-9 && ask <= c[K.MAX_ASK] / 100 + 1e-9 &&
            ask - bid <= c[K.SPREAD] / 100 + 1e-9 && q.tauS in c[K.MIN_LEFT]..c[K.MAX_LEFT] &&
            (c[K.EXIT_BY] <= 0 || q.tauS > c[K.EXIT_BY])
    }
    private fun maker(t: ScalpTrade): Boolean = !ScalpV2.isV2(t.variantId) &&
        (t.isMaker || ShockFav.byVariantId(t.variantId)?.let { it.kind == ShockFav.Kind.FADE_MAKER || it.o50 == ShockFav.O50.MAKER } == true)
    private data class Fill(val n: Int, val dollars: Double, val fee: Double)
    private fun walk(levels: List<Pair<Double, Double>>, want: Int, buy: Boolean, limit: Double): Fill {
        var n = 0; var usd = 0.0; var fee = 0.0
        val sorted = if (buy) levels.sortedBy { it.first } else levels.sortedByDescending { it.first }
        for ((px, size) in sorted) {
            if (!px.isFinite() || !size.isFinite() || px <= 0 || px >= 1 || size <= 0) continue
            if (n >= want || (buy && px > limit + 1e-9)) break
            val take = minOf(want - n, floor(size.coerceAtMost(Int.MAX_VALUE.toDouble())).toInt())
            if (take <= 0) continue
            n += take; usd += px * take; fee += ScalpRule.orderFee(take, px)
        }
        return Fill(n, usd, fee)
    }
    fun step(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        val c = ScalpControlSpec.frozen(t) ?: return null
        if (q.nowMs >= q.closeMs) return null // actual settlement handles any remainder
        if (t.state == ScalpState.PENDING_ENTRY) {
            fun miss(why: String) = t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; manual no fill: $why")
            val age = q.nowMs - t.signalAtMs
            if (age > c[K.TTL] || (c[K.EXIT_BY] > 0 && q.tauS <= c[K.EXIT_BY])) return miss("expired or exit window")
            if (age < c[K.DELAY] || q.bookAtMs <= t.signalAtMs || !fresh(q)) return null
            if (!entryAllowed(t, q, c)) return null
            val want = c[K.CLIP].toInt()
            val fill = if (maker(t)) {
                val limit = t.restingLimit ?: q.bid(t.side) ?: return null
                val ask = q.ask(t.side) ?: return null
                if (limit < c[K.MIN_ASK] / 100 || limit > c[K.MAX_ASK] / 100 || ask >= limit - 1e-9) return null
                val n = minOf(want, floor((q.askSize(t.side) ?: 0.0).coerceIn(0.0, want.toDouble())).toInt())
                Fill(n, n * limit, com.dirk.kalshiodds.signal.trade.KalshiFee.makerFee(n, limit,
                    com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(t.ticker)))
            } else walk(q.askLadder(t.side), want, true, minOf(c[K.MAX_ASK] / 100, t.signalAsk + c[K.SLIP] / 100))
            if (fill.n <= 0) return null
            if (fill.n - fill.dollars - fill.fee + 1e-9 < c[K.WIN_PROFIT]) return null
            return t.copy(state = ScalpState.OPEN, contracts = fill.n, entryPrice = fill.dollars / fill.n,
                entryFeeUsd = fill.fee, entryAtMs = q.nowMs, restingLimit = null,
                note = "${t.note}; manual depth fill ${fill.n}/$want")
        }
        if (t.state != ScalpState.OPEN && t.state != ScalpState.PENDING_EXIT) return null
        val entry = t.entryPrice ?: return null
        val bid = q.bid(t.side)?.takeIf { it.isFinite() && it > 0 && it < 1 }
        val closeReason = if (c[K.EXIT_BY] > 0 && q.tauS <= c[K.EXIT_BY]) "manual exit before close" else null
        val held = q.nowMs - (t.entryAtMs ?: q.nowMs)
        val holdReason = if (c[K.HOLD] > 0 && held >= c[K.HOLD] * 1000) "manual maximum hold" else null
        val usable = fresh(q) && bid != null
        val pk = if (usable) maxOf(bid!!, ScalpV2.savedNumber(t.note, "ctlpeak", 0.0)) else ScalpV2.savedNumber(t.note, "ctlpeak", 0.0)
        val net = if (usable) t.unrealizedUsd(bid) else null
        val armed = ScalpV2.savedNumber(t.note, "ctlarmed", 0.0) == 1.0 ||
            (net != null && net / t.contracts.coerceAtLeast(1) >= c[K.ARM] / 100 - 1e-9)
        val priceReason = if (!usable) null else when {
            bid!! <= (entry - c[K.STOP] / 100).coerceAtLeast(.01) + 1e-9 -> "manual stop"
            c[K.TARGET] > 0 && net != null && net / t.contracts.coerceAtLeast(1) >= c[K.TARGET] / 100 - 1e-9 -> "manual net profit target"
            c[K.TRAIL] > 0 && armed && bid <= pk - c[K.TRAIL] / 100 + 1e-9 -> "manual profit trail"
            c[K.TURN] > 0 && (if (ScalpV2.baseOf(t.variantId).startsWith("cfr")) q.cfFair(t.side) else q.fair(t.side))?.let { it <= entry - c[K.TURN] / 100 } == true -> "manual fair turned down"
            c[K.CONVERGENCE] > 0 && ShockFav.byVariantId(t.variantId)?.kind == ShockFav.Kind.SHOCK_FAV &&
                net != null && net > 0 && q.fair(t.side)?.let { bid >= it - c[K.CONVERGENCE] / 100 } == true -> "manual fair catch-up"
            else -> null
        }
        val reason = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT } ?: priceReason ?: closeReason ?: holdReason
        val note = ScalpV2.tag(ScalpV2.tag(t.note, "ctlpeak", pk.toString()), "ctlarmed", if (armed) "1" else "0")
        val marked = t.copy(note = note)
        if (reason == null) return marked.takeIf { it != t }
        val base = if (t.state == ScalpState.OPEN) marked.copy(state = ScalpState.PENDING_EXIT,
            exitDecidedAtMs = q.nowMs, exitReason = reason) else marked
        val decided = base.exitDecidedAtMs ?: q.nowMs
        val last = ScalpV2.savedNumber(t.note, "ctlfill", 0.0).toLong()
        if (!usable || q.nowMs - decided < c[K.EXIT_DELAY] || q.bookAtMs <= maxOf(decided, last)) return base.takeIf { it != t }
        val fill = walk(q.bidLadder(t.side), t.remaining, false, 0.0)
        if (fill.n <= 0) return base.takeIf { it != t }
        val next = base.copy(soldContracts = base.soldContracts + fill.n,
            proceedsUsd = base.proceedsUsd + fill.dollars, exitFeeUsd = base.exitFeeUsd + fill.fee,
            note = ScalpV2.tag(note, "ctlfill", q.bookAtMs.toString()))
        return if (next.remaining > 0) next else next.copy(state = ScalpState.CLOSED, closedAtMs = q.nowMs,
            netUsd = next.proceedsUsd - next.exitFeeUsd - next.entryCostUsd)
    }
}
