package com.dirk.kalshiodds.decision

/**
 * Paper-only Bitcoin scalp tuner. It never raises size and never widens the
 * entry band. A strategy with at least [MIN_TRADES] settled KXBTC15M trades
 * and a negative total is turned off. If any strategy is losing, the shared
 * ask cap drops 5¢ and the clip shrinks by one fifth. Winners are left alone.
 */
object BtcScalpTuner {
    const val MIN_TRADES = 8

    data class Trade(val ticker: String, val code: String, val netUsd: Double)

    data class Patch(
        val maxAskCents: Int? = null,
        val contracts: Int? = null,
        val stopLossPercent: Int? = null,
        val trailCents: Int? = null,
        val disable: Set<String> = emptySet(),
        val note: String
    ) {
        val changesAnything: Boolean
            get() = maxAskCents != null || contracts != null || stopLossPercent != null ||
                trailCents != null || disable.isNotEmpty()
    }

    fun propose(trades: List<Trade>, current: ScalpControls): Patch {
        val btc = trades.filter { BtcDirection.isBitcoin(it.ticker) && it.code.startsWith("v2-") && it.netUsd.isFinite() }
        val losers = btc.groupBy { it.code }
            .filter { (_, rows) -> rows.size >= MIN_TRADES && rows.sumOf { it.netUsd } < -1e-9 }
            .keys
        if (losers.isEmpty()) {
            return Patch(note = "No Bitcoin scalp with $MIN_TRADES settled trades is losing. Settings stay put.")
        }
        val ask = (current.maxAskCents - 5).coerceIn(20, current.maxAskCents)
        val contracts = (current.contracts * 4 / 5).coerceAtLeast(1).coerceAtMost(current.contracts)
        val stop = current.stopLossPercent.coerceAtMost(ScalpRisk.DEFAULT_PERCENT).coerceIn(1, current.stopLossPercent)
        return Patch(
            maxAskCents = ask.takeIf { it < current.maxAskCents },
            contracts = contracts.takeIf { it < current.contracts },
            stopLossPercent = stop.takeIf { it < current.stopLossPercent },
            trailCents = current.trailCents.coerceAtMost(2).takeIf { it < current.trailCents },
            disable = losers,
            note = "Turned off ${losers.size} losing Bitcoin scalp" +
                (if (losers.size == 1) "" else "s") +
                " and tightened size. Winners were not loosened."
        )
    }

    fun apply(current: ScalpControls, patch: Patch): ScalpControls {
        if (!patch.changesAnything) return current
        return current.copy(
            maxAskCents = patch.maxAskCents?.coerceIn(1, current.maxAskCents) ?: current.maxAskCents,
            contracts = patch.contracts?.coerceIn(1, current.contracts) ?: current.contracts,
            stopLossPercent = patch.stopLossPercent?.coerceIn(1, current.stopLossPercent) ?: current.stopLossPercent,
            trailCents = patch.trailCents?.coerceIn(1, current.trailCents) ?: current.trailCents,
            disabledStrategies = current.disabledStrategies + patch.disable.filter { it.startsWith("v2-") }
        ).normalized()
    }

    /** Accept only the knobs the chat is allowed to touch, and only in the tighter direction. */
    fun fromChat(current: ScalpControls, maxAskCents: Int?, contracts: Int?, stopLossPercent: Int?, trailCents: Int?, disable: Set<String>): Patch {
        val tighterAsk = maxAskCents?.takeIf { it in 1..current.maxAskCents && it < current.maxAskCents }
        val tighterClip = contracts?.takeIf { it in 1..current.contracts && it < current.contracts }
        val tighterStop = stopLossPercent?.takeIf { it in 1..current.stopLossPercent && it < current.stopLossPercent }
        val tighterTrail = trailCents?.takeIf { it in 1..current.trailCents && it < current.trailCents }
        val off = disable.filter { it.startsWith("v2-") }.toSet()
        val patch = Patch(tighterAsk, tighterClip, tighterStop, tighterTrail, off, "Chat tightened Bitcoin scalp settings.")
        return if (patch.changesAnything) patch else Patch(note = "Chat asked for a looser or unknown setting. Nothing changed.")
    }
}
