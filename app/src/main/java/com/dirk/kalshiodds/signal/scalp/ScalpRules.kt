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
    const val START_CASH = 100.0

    val ALL: List<ScalpAlgo> = listOf(
        algo(
            "all_in_cheap",
            "All-in cheap",
            "100% of cash on the cheaper ask under 40¢, any minute. Sells 8¢ up, or 4¢ off the high."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.40) ScalpIntent.Buy(side, 1.0) else ScalpIntent.Hold
            } else {
                val bid = bid(t, pos.side)
                when {
                    bid >= pos.entry + 0.08 -> ScalpIntent.Sell()
                    pos.peakBid >= pos.entry + 0.03 && pos.peakBid - bid >= 0.04 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "no_cap",
            "No cap",
            "100% of cash on the cheaper ask under 95¢, any minute. Sells 5¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.95) ScalpIntent.Buy(side, 1.0) else ScalpIntent.Hold
            } else if (bid(t, pos.side) >= pos.entry + 0.05) {
                ScalpIntent.Sell()
            } else {
                ScalpIntent.Hold
            }
        },
        algo(
            "penny_press",
            "Penny press",
            "80% of cash when an ask is 12¢ or less. Sells 10¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesAsk <= 0.12 -> ScalpIntent.Buy("YES", 0.80)
                    t.noAsk <= 0.12 -> ScalpIntent.Buy("NO", 0.80)
                    else -> ScalpIntent.Hold
                }
            } else if (bid(t, pos.side) >= pos.entry + 0.10) {
                ScalpIntent.Sell()
            } else {
                ScalpIntent.Hold
            }
        },
        algo(
            "tight_4c",
            "Tight 4¢",
            "20% of cash on a cheaper ask from 15¢ to 80¢. Sells 4¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                val px = ask(t, side)
                if (px in 0.15..0.80) ScalpIntent.Buy(side, 0.20) else ScalpIntent.Hold
            } else if (bid(t, pos.side) >= pos.entry + 0.04) {
                ScalpIntent.Sell()
            } else {
                ScalpIntent.Hold
            }
        },
        algo(
            "momentum",
            "Momentum",
            "40% of cash after four rising bids under 80¢. Sells 3¢ off a high that cleared 4¢."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    rising(t.yesBids, 4) && t.yesAsk < 0.80 -> ScalpIntent.Buy("YES", 0.40)
                    rising(t.noBids, 4) && t.noAsk < 0.80 -> ScalpIntent.Buy("NO", 0.40)
                    else -> ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                if (pos.peakBid >= pos.entry + 0.04 && pos.peakBid - b >= 0.03) ScalpIntent.Sell() else ScalpIntent.Hold
            }
        },
        algo(
            "fade_spike",
            "Fade the spike",
            "50% of cash when an ask is 8¢ under the window high. Sells 6¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesHigh - t.yesAsk >= 0.08 && t.yesAsk > 0.04 -> ScalpIntent.Buy("YES", 0.50)
                    t.noHigh - t.noAsk >= 0.08 && t.noAsk > 0.04 -> ScalpIntent.Buy("NO", 0.50)
                    else -> ScalpIntent.Hold
                }
            } else if (bid(t, pos.side) >= pos.entry + 0.06) {
                ScalpIntent.Sell()
            } else {
                ScalpIntent.Hold
            }
        },
        algo(
            "first_5",
            "First 5 min",
            "70% of cash in the first 5 minutes if the cheaper ask is under 55¢. Sells at 5 minutes or 6¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.elapsedMs < FIVE_MIN) {
                    val side = cheaper(t)
                    if (ask(t, side) < 0.55) ScalpIntent.Buy(side, 0.70) else ScalpIntent.Hold
                } else {
                    ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                when {
                    t.elapsedMs >= FIVE_MIN -> ScalpIntent.Sell()
                    b >= pos.entry + 0.06 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "min_5_7",
            "Minutes 5–7",
            "60% of cash only between minute 5 and 7, cheaper ask under 65¢. Sells on a 3¢ dip or at minute 7."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.elapsedMs in FIVE_MIN until SEVEN_MIN) {
                    val side = cheaper(t)
                    if (ask(t, side) < 0.65) ScalpIntent.Buy(side, 0.60) else ScalpIntent.Hold
                } else {
                    ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell()
                    pos.peakBid - b >= 0.03 && pos.peakBid > pos.entry -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "late_squeeze",
            "Late squeeze",
            "100% of cash on a 55–92¢ favorite in the last 4 minutes. Sells 3¢ up, or cuts 2¢ against."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.tteMs in 1..FOUR_MIN) {
                    when {
                        t.yesAsk in 0.55..0.92 && t.yesAsk >= t.noAsk -> ScalpIntent.Buy("YES", 1.0)
                        t.noAsk in 0.55..0.92 -> ScalpIntent.Buy("NO", 1.0)
                        else -> ScalpIntent.Hold
                    }
                } else {
                    ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                when {
                    b >= pos.entry + 0.03 -> ScalpIntent.Sell()
                    pos.entry - b >= 0.02 -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "gap_side",
            "Wider side",
            "50% of cash on the cheaper side when the two asks differ by 8¢ or more. Sells 6¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val gap = kotlin.math.abs(t.yesAsk - t.noAsk)
                val side = cheaper(t)
                if (gap >= 0.08 && ask(t, side) < 0.90) ScalpIntent.Buy(side, 0.50) else ScalpIntent.Hold
            } else if (bid(t, pos.side) >= pos.entry + 0.06) {
                ScalpIntent.Sell()
            } else {
                ScalpIntent.Hold
            }
        },
        algo(
            "breakout_50",
            "Breakout 50",
            "50% of cash when YES crosses up through 50¢. Sells 5¢ up, or cuts back under 48¢."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val prev = t.yesBids.dropLast(1).lastOrNull()
                if (prev != null && prev < 0.50 && t.yesBid >= 0.50 && t.yesAsk < 0.70) {
                    ScalpIntent.Buy("YES", 0.50)
                } else {
                    ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                when {
                    b >= pos.entry + 0.05 -> ScalpIntent.Sell()
                    b < 0.48 -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "dump_catch",
            "Dump catch",
            "100% of cash when an ask is 10¢ under the window high. Sells 5¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesHigh - t.yesAsk >= 0.10 && t.yesAsk > 0.04 -> ScalpIntent.Buy("YES", 1.0)
                    t.noHigh - t.noAsk >= 0.10 && t.noAsk > 0.04 -> ScalpIntent.Buy("NO", 1.0)
                    else -> ScalpIntent.Hold
                }
            } else if (bid(t, pos.side) >= pos.entry + 0.05) {
                ScalpIntent.Sell()
            } else {
                ScalpIntent.Hold
            }
        },
        algo(
            "slow_trail",
            "Slow trail",
            "35% of cash on a cheaper ask under 45¢. Sells 2¢ off a high that cleared 5¢."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.45) ScalpIntent.Buy(side, 0.35) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                if (pos.peakBid >= pos.entry + 0.05 && pos.peakBid - b >= 0.02) ScalpIntent.Sell() else ScalpIntent.Hold
            }
        },
        algo(
            "all_in_under_50",
            "All-in under 50",
            "100% of cash on the cheaper ask under 50¢, any minute. Sells 6¢ up, or 3¢ off the high."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.50) ScalpIntent.Buy(side, 1.0) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                when {
                    b >= pos.entry + 0.06 -> ScalpIntent.Sell()
                    pos.peakBid >= pos.entry + 0.02 && pos.peakBid - b >= 0.03 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "tight_spread",
            "Tight spread",
            "25% of cash when the spread is 2¢ or less and the ask is under 70¢. Sells 3¢ up."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val yesSpread = t.yesAsk - t.yesBid
                val noSpread = t.noAsk - t.noBid
                when {
                    yesSpread in 0.0..0.02 && t.yesAsk < 0.70 -> ScalpIntent.Buy("YES", 0.25)
                    noSpread in 0.0..0.02 && t.noAsk < 0.70 -> ScalpIntent.Buy("NO", 0.25)
                    else -> ScalpIntent.Hold
                }
            } else if (bid(t, pos.side) >= pos.entry + 0.03) {
                ScalpIntent.Sell()
            } else {
                ScalpIntent.Hold
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
                prior.copy(name = algo.name, rule = algo.rule)
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

    private fun ask(t: ScalpTape, side: String): Double = if (side == "NO") t.noAsk else t.yesAsk

    internal fun bid(t: ScalpTape, side: String): Double = if (side == "NO") t.noBid else t.yesBid

    private fun rising(xs: List<Double>, n: Int): Boolean {
        if (xs.size < n) return false
        return xs.takeLast(n).zipWithNext().all { (prev, next) -> next > prev + 0.004 }
    }

    private const val FIVE_MIN = 5L * 60L * 1000L
    private const val SEVEN_MIN = 7L * 60L * 1000L
    private const val FOUR_MIN = 4L * 60L * 1000L
}
