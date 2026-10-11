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
            "Cheap 22",
            "Was 100% under 40¢. Now 22%. Sells 8¢ up, 4¢ off the high, or at minute 7 even if it's red."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.40) ScalpIntent.Buy(side, 0.22) else ScalpIntent.Hold
            } else {
                val bid = bid(t, pos.side)
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    bid >= pos.entry + 0.08 -> ScalpIntent.Sell()
                    pos.peakBid >= pos.entry + 0.03 && pos.peakBid - bid >= 0.04 -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "no_cap",
            "Under 70",
            "Was 100% under 95¢. Now 18% and only under 70¢. Sells 5¢ up, or dumps a loser after minute 7."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.70) ScalpIntent.Buy(side, 0.18) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                when {
                    t.elapsedMs >= SEVEN_MIN && b + 1e-9 < pos.entry -> ScalpIntent.Sell(force = true)
                    t.tteMs in 1..THREE_MIN -> ScalpIntent.Sell(force = true)
                    b >= pos.entry + 0.05 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "penny_press",
            "Penny 12",
            "Was 80% waiting on a 10¢ pop. Now 12% at 12¢ or less. Sells 4¢ up, or at minute 7."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesAsk <= 0.12 -> ScalpIntent.Buy("YES", 0.12)
                    t.noAsk <= 0.12 -> ScalpIntent.Buy("NO", 0.12)
                    else -> ScalpIntent.Hold
                }
            } else {
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    bid(t, pos.side) >= pos.entry + 0.04 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "tight_4c",
            "Tight 4¢",
            "20% of cash on a cheaper ask from 15¢ to 80¢. Sells 4¢ up, or at minute 7 if it never came."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                val px = ask(t, side)
                if (px in 0.15..0.80) ScalpIntent.Buy(side, 0.20) else ScalpIntent.Hold
            } else {
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    bid(t, pos.side) >= pos.entry + 0.04 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "momentum",
            "Momentum",
            "40% of cash after four rising bids under 80¢. Sells 3¢ off a high that cleared 4¢, or at minute 7."
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
                if (pos.peakBid >= pos.entry + 0.04 && pos.peakBid - b >= 0.03) {
                    ScalpIntent.Sell(force = true)
                } else if (t.elapsedMs >= SEVEN_MIN) {
                    ScalpIntent.Sell(force = true)
                } else {
                    ScalpIntent.Hold
                }
            }
        },
        algo(
            "fade_spike",
            "Fade the spike",
            "50% of cash when an ask is 8¢ under the window high. Sells 6¢ up, or at minute 7."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesHigh - t.yesAsk >= 0.08 && t.yesAsk > 0.04 -> ScalpIntent.Buy("YES", 0.50)
                    t.noHigh - t.noAsk >= 0.08 && t.noAsk > 0.04 -> ScalpIntent.Buy("NO", 0.50)
                    else -> ScalpIntent.Hold
                }
            } else {
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    bid(t, pos.side) >= pos.entry + 0.06 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
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
                    t.elapsedMs >= FIVE_MIN -> ScalpIntent.Sell(force = true)
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
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    pos.peakBid - b >= 0.03 && pos.peakBid > pos.entry -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "late_squeeze",
            "Late small",
            "Was 100% on a 55–92¢ favorite. Now 12%, and only 55–68¢, with a 6¢ cut. Out in the last 45 seconds."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                if (t.tteMs in 1..FOUR_MIN) {
                    when {
                        t.yesAsk in 0.55..0.68 && t.yesAsk >= t.noAsk -> ScalpIntent.Buy("YES", 0.12)
                        t.noAsk in 0.55..0.68 -> ScalpIntent.Buy("NO", 0.12)
                        else -> ScalpIntent.Hold
                    }
                } else {
                    ScalpIntent.Hold
                }
            } else {
                val b = bid(t, pos.side)
                when {
                    t.tteMs <= 45_000L -> ScalpIntent.Sell(force = true)
                    b >= pos.entry + 0.03 -> ScalpIntent.Sell()
                    pos.entry - b >= 0.06 -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "gap_side",
            "Wider side",
            "50% of cash on the cheaper side when the two asks differ by 8¢ or more. Sells 6¢ up, or at minute 7."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val gap = kotlin.math.abs(t.yesAsk - t.noAsk)
                val side = cheaper(t)
                if (gap >= 0.08 && ask(t, side) < 0.90) ScalpIntent.Buy(side, 0.50) else ScalpIntent.Hold
            } else {
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    bid(t, pos.side) >= pos.entry + 0.06 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
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
            "Bounce",
            "Was 100% on a 10¢ dump. Now 20%, and only if the bid is ticking back up. Sells 5¢ up, or at minute 7."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                when {
                    t.yesHigh - t.yesAsk >= 0.10 && t.yesAsk > 0.04 && bouncing(t.yesBids) -> ScalpIntent.Buy("YES", 0.20)
                    t.noHigh - t.noAsk >= 0.10 && t.noAsk > 0.04 && bouncing(t.noBids) -> ScalpIntent.Buy("NO", 0.20)
                    else -> ScalpIntent.Hold
                }
            } else {
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    bid(t, pos.side) >= pos.entry + 0.05 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "slow_trail",
            "Slow trail",
            "35% of cash on a cheaper ask under 45¢. Sells 2¢ off a high that cleared 5¢, or at minute 7."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.45) ScalpIntent.Buy(side, 0.35) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                if (pos.peakBid >= pos.entry + 0.05 && pos.peakBid - b >= 0.02) {
                    ScalpIntent.Sell(force = true)
                } else if (t.elapsedMs >= SEVEN_MIN) {
                    ScalpIntent.Sell(force = true)
                } else {
                    ScalpIntent.Hold
                }
            }
        },
        algo(
            "all_in_under_50",
            "Under 50",
            "Was 100% under 50¢. Now 22%. Sells 6¢ up, 3¢ off the high, or at minute 7 even if it's red."
        ) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < 0.50) ScalpIntent.Buy(side, 0.22) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    b >= pos.entry + 0.06 -> ScalpIntent.Sell()
                    pos.peakBid >= pos.entry + 0.02 && pos.peakBid - b >= 0.03 -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        },
        algo(
            "tight_spread",
            "Tight spread",
            "25% of cash when the spread is 2¢ or less and the ask is under 70¢. Sells 3¢ up, or at minute 7."
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
            } else {
                when {
                    t.elapsedMs >= SEVEN_MIN -> ScalpIntent.Sell(force = true)
                    bid(t, pos.side) >= pos.entry + 0.03 -> ScalpIntent.Sell()
                    else -> ScalpIntent.Hold
                }
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

    private const val FIVE_MIN = 5L * 60L * 1000L
    private const val SEVEN_MIN = 7L * 60L * 1000L
    private const val FOUR_MIN = 4L * 60L * 1000L
    private const val THREE_MIN = 3L * 60L * 1000L
}
