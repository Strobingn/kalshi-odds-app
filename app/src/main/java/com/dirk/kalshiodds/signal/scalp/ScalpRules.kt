package com.dirk.kalshiodds.signal.scalp

/**
 * One scalp rule. It only returns an intent. The lab applies size, fees,
 * and the cash it actually has. Nothing here can place a Kalshi order.
 */
class ScalpAlgo(
    val id: String,
    val name: String,
    val rule: String,
    val decide: (ScalpAccount, ScalpTape) -> ScalpIntent
)

data class ScalpTape(
    val ticker: String,
    val yesBid: Double,
    val yesAsk: Double,
    val noBid: Double,
    val noAsk: Double,
    val elapsedMs: Long,
    val tteMs: Long,
    /** Oldest first, including the current bid. */
    val yesBids: List<Double>,
    val noBids: List<Double>,
    val yesHigh: Double,
    val yesLow: Double,
    val noHigh: Double,
    val noLow: Double
)

sealed class ScalpIntent {
    data class Buy(val side: String, val fraction: Double) : ScalpIntent()
    /** [force] sells even when the round trip is still a loss. */
    data class Sell(val force: Boolean = false) : ScalpIntent()
    data object Hold : ScalpIntent()
}

object ScalpCatalog {
    /** Each paper book. Not live cash. A book may trade through zero. */
    const val START_CASH = 20_000.0

    val ALL: List<ScalpAlgo> = listOf(
        algo(
            "all_in_cheap",
            "Cheap trail",
            "10% of cash on the cheaper ask under 40¢. Holds the rise. Sells when the bid drops 5¢ off the high."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.40) ScalpIntent.Buy(side, 0.10) else ScalpIntent.Hold
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "no_cap",
            "Under 50",
            "Was buying up to 70¢. Now 10% of cash, cheaper ask under 50¢ only. Holds the rise, sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.50) ScalpIntent.Buy(side, 0.10) else ScalpIntent.Hold
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "penny_press",
            "Penny trail",
            "Was a lottery at 80%. Now 8% of cash at 20¢ or less. Holds the rise, sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesAsk <= 0.20 -> ScalpIntent.Buy("YES", 0.08)
                    t.noAsk <= 0.20 -> ScalpIntent.Buy("NO", 0.08)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "tight_4c",
            "Tight trail",
            "Was settling losers. Now 10% of cash under 45¢. Holds the rise, sells a 4¢ drop off the high."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.45) ScalpIntent.Buy(side, 0.10) else ScalpIntent.Hold
            } else {
                trail(pos, t, 0.04)
            }
        },
        algo(
            "momentum",
            "Momentum",
            "12% of cash after four rising bids under 50¢. Holds the rise. Sells a 5¢ drop off the high."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    rising(t.yesBids, 4) && t.yesAsk < 0.50 -> ScalpIntent.Buy("YES", 0.12)
                    rising(t.noBids, 4) && t.noAsk < 0.50 -> ScalpIntent.Buy("NO", 0.12)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "fade_spike",
            "Fade trail",
            "Was 50% on a dump. Now 12% of cash when an ask is 8¢ under the high and under 50¢. Sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesHigh - t.yesAsk >= 0.08 && t.yesAsk in 0.05..0.50 -> ScalpIntent.Buy("YES", 0.12)
                    t.noHigh - t.noAsk >= 0.08 && t.noAsk in 0.05..0.50 -> ScalpIntent.Buy("NO", 0.12)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "first_5",
            "First 5 min",
            "12% of cash in the first 5 minutes, cheaper ask under 50¢. Holds the rise, sells a 5¢ drop, or dumps a loser at minute 5."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.elapsedMs < FIVE_MIN) {
                    val side = cheaper(t)
                    if (ask(t, side) < 0.50) ScalpIntent.Buy(side, 0.12) else ScalpIntent.Hold
                } else {
                    ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                when {
                    t.elapsedMs >= FIVE_MIN && b + 1e-9 < pos.entry -> ScalpIntent.Sell(force = true)
                    else -> trail(pos, t, 0.05)
                }
            }
        },
        algo(
            "min_5_7",
            "Minutes 5–7",
            "12% of cash between minute 5 and 7, cheaper ask under 50¢. Holds the rise, sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.elapsedMs in FIVE_MIN until SEVEN_MIN) {
                    val side = cheaper(t)
                    if (ask(t, side) < 0.50) ScalpIntent.Buy(side, 0.12) else ScalpIntent.Hold
                } else {
                    ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "late_squeeze",
            "Early cheap",
            "Was 100% on a late favorite. Now 10% of cash on a cheaper ask under 40¢ in the first 7 minutes. Sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.elapsedMs < SEVEN_MIN) {
                    val side = cheaper(t)
                    if (ask(t, side) < 0.40) ScalpIntent.Buy(side, 0.10) else ScalpIntent.Hold
                } else {
                    ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "gap_side",
            "Wider side",
            "12% of cash on the cheaper side when the asks differ by 8¢ and that side is under 50¢. Sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val gap = kotlin.math.abs(t.yesAsk - t.noAsk)
                val side = cheaper(t)
                if (gap >= 0.08 && ask(t, side) < 0.50) ScalpIntent.Buy(side, 0.12) else ScalpIntent.Hold
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "breakout_50",
            "Breakout 50",
            "12% of cash when YES crosses up through 50¢. Holds the rise, sells a 5¢ drop, or cuts back under 48¢."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val prev = t.yesBids.dropLast(1).lastOrNull()
                if (prev != null && prev < 0.50 && t.yesBid >= 0.50 && t.yesAsk < 0.62) {
                    ScalpIntent.Buy("YES", 0.12)
                } else {
                    ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                when {
                    b < 0.48 -> ScalpIntent.Sell(force = true)
                    else -> trail(pos, t, 0.05)
                }
            }
        },
        algo(
            "dump_catch",
            "Bounce",
            "10% of cash on a 10¢ dump that is ticking back up, under 50¢. Holds the rise, sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesHigh - t.yesAsk >= 0.10 && t.yesAsk in 0.05..0.50 && bouncing(t.yesBids) -> ScalpIntent.Buy("YES", 0.10)
                    t.noHigh - t.noAsk >= 0.10 && t.noAsk in 0.05..0.50 && bouncing(t.noBids) -> ScalpIntent.Buy("NO", 0.10)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "slow_trail",
            "Slow trail",
            "10% of cash on a cheaper ask under 45¢. Holds the rise. Sells when the bid drops 5¢ off the high."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.45) ScalpIntent.Buy(side, 0.10) else ScalpIntent.Hold
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "all_in_under_50",
            "Trail 5¢",
            "10% of cash on the cheaper ask under 50¢. Holds while the price rises. Sells once the bid drops 5¢ off the high."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.50) ScalpIntent.Buy(side, 0.10) else ScalpIntent.Hold
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "tight_spread",
            "Tight spread",
            "Was settling. Now 10% of cash when the spread is 2¢ or less and the ask is under 50¢. Sells a 4¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val yesSpread = t.yesAsk - t.yesBid
                val noSpread = t.noAsk - t.noBid
                when {
                    yesSpread in 0.0..0.02 && t.yesAsk < 0.50 -> ScalpIntent.Buy("YES", 0.10)
                    noSpread in 0.0..0.02 && t.noAsk < 0.50 -> ScalpIntent.Buy("NO", 0.10)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.04)
            }
        },
        algo(
            "dip_buy",
            "Dip buy",
            "10% of cash when an ask is 6¢ under the window high, under 40¢, and the bid is ticking up. Sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesHigh - t.yesAsk >= 0.06 && t.yesAsk < 0.40 && bouncing(t.yesBids) -> ScalpIntent.Buy("YES", 0.10)
                    t.noHigh - t.noAsk >= 0.06 && t.noAsk < 0.40 && bouncing(t.noBids) -> ScalpIntent.Buy("NO", 0.10)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "wide_gap",
            "Wide gap",
            "10% of cash on the cheaper side when the two asks differ by 15¢ or more and that side is under 35¢. Sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val gap = kotlin.math.abs(t.yesAsk - t.noAsk)
                val side = cheaper(t)
                if (gap >= 0.15 && ask(t, side) < 0.35) ScalpIntent.Buy(side, 0.10) else ScalpIntent.Hold
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "burst",
            "Burst",
            "10% of cash after three prints each up at least 2¢, ask under 45¢. Holds the burst, sells a 4¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    stepped(t.yesBids, 3, 0.02) && t.yesAsk < 0.45 -> ScalpIntent.Buy("YES", 0.10)
                    stepped(t.noBids, 3, 0.02) && t.noAsk < 0.45 -> ScalpIntent.Buy("NO", 0.10)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.04)
            }
        },
        algo(
            "reclaim",
            "Reclaim",
            "10% of cash when the bid has reclaimed 4¢ off the window low and is still under 45¢. Sells a 5¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesBid - t.yesLow >= 0.04 && t.yesAsk < 0.45 -> ScalpIntent.Buy("YES", 0.10)
                    t.noBid - t.noLow >= 0.04 && t.noAsk < 0.45 -> ScalpIntent.Buy("NO", 0.10)
                    else -> ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.05)
            }
        },
        algo(
            "deep_value",
            "Deep value",
            "8% of cash on a cheaper ask at 25¢ or less in the first 10 minutes. Wider trail: sells a 6¢ drop."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.elapsedMs < TEN_MIN) {
                    val side = cheaper(t)
                    if (ask(t, side) <= 0.25) ScalpIntent.Buy(side, 0.08) else ScalpIntent.Hold
                } else {
                    ScalpIntent.Hold
                }
            } else {
                trail(pos, t, 0.06)
            }
        }
    )

    fun merge(saved: List<ScalpAccount>): List<ScalpAccount> {
        val byId = saved.associateBy { it.id }
        return ALL.map { algo ->
            val prior = byId[algo.id]
            if (prior == null) {
                ScalpAccount(id = algo.id, name = algo.name, rule = algo.rule)
            } else {
                prior.copy(name = algo.name, rule = algo.rule).let { kept ->
                    when {
                        kept.settleLosses == 0 && kept.lastNote.contains("SETTLE LOSS") -> kept.copy(
                            settleLosses = 1,
                            sizeScale = 0.75,
                            learnedExit = 0.04,
                            lesson = "Already lost by waiting for settlement. Next bet is smaller and it sells the pop."
                        )
                        kept.lesson.isEmpty() && kept.lastNote.contains("SETTLE WIN") -> kept.copy(
                            lesson = "Settled a winner. Treated as luck. Next time it sells the pop and trades again."
                        )
                        else -> kept
                    }
                }
            }
        }
    }

    private fun algo(
        id: String,
        name: String,
        rule: String,
        decide: (ScalpAccount, ScalpTape) -> ScalpIntent
    ) = ScalpAlgo(id, name, rule, decide)

    private fun cheaper(t: ScalpTape): String = if (t.yesAsk <= t.noAsk) "YES" else "NO"

    /**
     * Hold the rise. Sell when the bid is [drop] under the high of this trade.
     * A red trade that has flattened by minute 7 is cut so it does not settle.
     * Anything still open in the last 90 seconds is closed.
     */
    private fun trail(pos: ScalpPosition, t: ScalpTape, drop: Double): ScalpIntent {
        val b = bid(t, pos.side)
        return when {
            pos.peakBid - b + 1e-9 >= drop -> ScalpIntent.Sell(force = true)
            t.elapsedMs >= SEVEN_MIN && b + 1e-9 < pos.peakBid -> ScalpIntent.Sell(force = true)
            t.elapsedMs >= SEVEN_MIN && b + 1e-9 < pos.entry -> ScalpIntent.Sell(force = true)
            t.tteMs in 1..90_000L -> ScalpIntent.Sell(force = true)
            else -> ScalpIntent.Hold
        }
    }

    private fun ask(t: ScalpTape, side: String): Double = if (side == "NO") t.noAsk else t.yesAsk

    internal fun bid(t: ScalpTape, side: String): Double = if (side == "NO") t.noBid else t.yesBid

    private fun bouncing(xs: List<Double>): Boolean {
        val prev = xs.dropLast(1).lastOrNull() ?: return false
        return xs.last() > prev + 0.004
    }

    private fun rising(xs: List<Double>, n: Int): Boolean {
        if (xs.size < n) return false
        return xs.takeLast(n).zipWithNext().all { (prev, next) -> next > prev + 0.004 }
    }

    private fun stepped(xs: List<Double>, n: Int, step: Double): Boolean {
        if (xs.size < n) return false
        return xs.takeLast(n).zipWithNext().all { (prev, next) -> next + 1e-9 >= prev + step }
    }

    private const val FIVE_MIN = 5L * 60L * 1000L
    private const val SEVEN_MIN = 7L * 60L * 1000L
    private const val TEN_MIN = 10L * 60L * 1000L
}
