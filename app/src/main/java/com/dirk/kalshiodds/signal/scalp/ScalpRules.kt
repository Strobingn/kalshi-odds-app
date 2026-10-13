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

    /**
     * Every book does the same job. Buy the cheap side. Hold while it rises.
     * Sell the first real drop off the high. They only differ in how cheap,
     * how much of a drop, and how big the bet is.
     */
    val ALL: List<ScalpAlgo> = listOf(
        cheap("all_in_cheap", "Cheap 40", 0.40, 0.05, 0.10, "10% under 40¢. Sells a 5¢ drop off the high."),
        cheap("no_cap", "Under 50", 0.50, 0.05, 0.10, "10% under 50¢. Sells a 5¢ drop off the high."),
        cheap("all_in_under_50", "Trail 5¢", 0.50, 0.05, 0.10, "10% under 50¢. Holds the rise. Sells a 5¢ drop."),
        cheap("slow_trail", "Slow trail", 0.45, 0.05, 0.10, "10% under 45¢. Holds the rise. Sells a 5¢ drop."),
        cheap("tight_spread", "Tight 3¢", 0.40, 0.03, 0.10, "10% under 40¢. Sells a 3¢ drop off the high."),
        cheap("tight_4c", "Tight 4¢", 0.45, 0.04, 0.10, "10% under 45¢. Sells a 4¢ drop off the high."),
        cheap("penny_press", "Penny 25", 0.25, 0.04, 0.08, "8% at 25¢ or less. Sells a 4¢ drop."),
        cheap("deep_value", "Deep 20", 0.20, 0.04, 0.08, "8% at 20¢ or less. Sells a 4¢ drop."),
        cheap("dip_buy", "Dip 35", 0.35, 0.04, 0.10, "10% under 35¢. Sells a 4¢ drop."),
        cheap("wide_gap", "Wide 30", 0.30, 0.05, 0.10, "10% under 30¢. Sells a 5¢ drop."),
        cheap("burst", "Quick 2¢", 0.40, 0.02, 0.08, "8% under 40¢. Sells the first 2¢ drop."),
        cheap("reclaim", "Reclaim 4¢", 0.45, 0.04, 0.10, "10% under 45¢. Sells a 4¢ drop."),
        windowed("first_5", "First 5", 0.50, 0.04, 0.10, 0L, FIVE_MIN, "10% under 50¢ in the first 5 minutes. Sells a 4¢ drop, or a loser at minute 5."),
        windowed("min_5_7", "Minutes 5–7", 0.45, 0.04, 0.10, FIVE_MIN, SEVEN_MIN, "10% under 45¢ between minute 5 and 7. Sells a 4¢ drop."),
        windowed("late_squeeze", "Early cheap", 0.50, 0.03, 0.12, 0L, SEVEN_MIN, "12% under 50¢ in the first 7 minutes. Sells a 3¢ drop and takes the next one."),
        cheap("gap_side", "Gap 40", 0.40, 0.04, 0.10, "10% under 40¢. Sells a 4¢ drop."),
        cheap("breakout_50", "Break 45", 0.45, 0.04, 0.10, "10% under 45¢. Sells a 4¢ drop."),
        cheap("dump_catch", "Bounce 35", 0.35, 0.04, 0.10, "10% under 35¢. Sells a 4¢ drop."),
        cheap("momentum", "Ride 50", 0.50, 0.04, 0.12, "12% under 50¢. Sells a 4¢ drop off the high."),
        cheap("fade_spike", "Fade 30", 0.30, 0.03, 0.10, "10% under 30¢. Sells a 3¢ drop.")
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

    private fun cheap(id: String, name: String, maxAsk: Double, drop: Double, fraction: Double, rule: String) =
        algo(id, name, rule) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < maxAsk) ScalpIntent.Buy(side, fraction) else ScalpIntent.Hold
            } else {
                dropExit(pos, t, drop)
            }
        }

    private fun windowed(
        id: String,
        name: String,
        maxAsk: Double,
        drop: Double,
        fraction: Double,
        fromMs: Long,
        untilMs: Long,
        rule: String
    ) = algo(id, name, rule) { a, t ->
        val pos = a.position
        if (pos == null) {
            if (t.elapsedMs in fromMs until untilMs) {
                val side = cheaper(t)
                if (ask(t, side) < maxAsk) ScalpIntent.Buy(side, fraction) else ScalpIntent.Hold
            } else {
                ScalpIntent.Hold
            }
        } else {
            val b = bid(t, pos.side)
            when {
                id == "first_5" && t.elapsedMs >= FIVE_MIN && b + 1e-9 < pos.entry -> ScalpIntent.Sell(force = true)
                else -> dropExit(pos, t, drop)
            }
        }
    }

    /**
     * Hold the rise. Sell when the bid is [drop] under the high of this trade.
     * A trade that never rose and is 3¢ under the entry is cut.
     * Anything still open in the last 90 seconds is closed.
     */
    private fun dropExit(pos: ScalpPosition, t: ScalpTape, drop: Double): ScalpIntent {
        val b = bid(t, pos.side)
        val rose = pos.peakBid + 1e-9 >= pos.entry + 0.02
        return when {
            rose && pos.peakBid - b + 1e-9 >= drop -> ScalpIntent.Sell(force = true)
            !rose && b + 1e-9 <= pos.entry - 0.03 -> ScalpIntent.Sell(force = true)
            t.tteMs in 1..90_000L -> ScalpIntent.Sell(force = true)
            else -> ScalpIntent.Hold
        }
    }

    private fun algo(
        id: String,
        name: String,
        rule: String,
        decide: (ScalpAccount, ScalpTape) -> ScalpIntent
    ) = ScalpAlgo(id, name, rule, decide)

    private fun cheaper(t: ScalpTape): String = if (t.yesAsk <= t.noAsk) "YES" else "NO"

    internal fun bid(t: ScalpTape, side: String): Double = if (side == "NO") t.noBid else t.yesBid

    private fun ask(t: ScalpTape, side: String): Double = if (side == "NO") t.noAsk else t.yesAsk

    private const val FIVE_MIN = 5L * 60L * 1000L
    private const val SEVEN_MIN = 7L * 60L * 1000L
}
