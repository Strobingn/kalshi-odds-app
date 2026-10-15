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

object ScalpSettings {
    /** Sell when the bid is this much above the entry. */
    var takeProfit: Double = 0.08
    /** Sell when the bid is this much below the entry. */
    var stopLoss: Double = 0.05
    /** Max ask to buy. */
    var maxAsk: Double = 0.50
    /** Fraction of cash per entry. */
    var fraction: Double = 0.25
    /** Minutes left above which the price is treated as range-bound. */
    var rangeMinutes: Int = 10
    /** Minutes left below which the price is treated as a rapid shift. */
    var shiftMinutes: Int = 5
    /** Bottom of the range to buy when time is long. */
    var rangeLow: Double = 0.30
    /** Top of the range to buy when time is long. */
    var rangeHigh: Double = 0.75
}

object ScalpCatalog {
    /** Each paper book. Not live cash. A book may trade through zero. */
    const val START_CASH = 20_000.0

    /**
     * The Simple dip books that were green. Buy a dip under a cap, sell at a
     * fixed profit, stop out 5¢ under the entry.
     */
    val ALL: List<ScalpAlgo> = listOf(
        dip("late_squeeze", "Open rise → 68", 0.0, 0.68, 0.55, 0.25, "Buy the rising side near 50¢. Sell at 68¢."),
        dip("all_in_cheap", "Open rise → 70", 0.0, 0.70, 0.55, 0.25, "Buy the rising side near 50¢. Sell at 70¢."),
        dip("all_in_under_50", "Open rise → 68 30%", 0.0, 0.68, 0.55, 0.30, "Buy the rising side near 50¢. 30%. Sell at 68¢."),
        dip("first_5", "Open rise → 70 20%", 0.0, 0.70, 0.55, 0.20, "Buy the rising side near 50¢. 20%. Sell at 70¢."),
        dip("no_cap", "Open rise → 65", 0.0, 0.65, 0.55, 0.25, "Buy the rising side near 50¢. Sell at 65¢."),
        dip("slow_trail", "Open rise → 72", 0.0, 0.72, 0.55, 0.25, "Buy the rising side near 50¢. Sell at 72¢."),
        dip("tight_spread", "Open rise → 68 15%", 0.0, 0.68, 0.55, 0.15, "Buy the rising side near 50¢. 15%. Sell at 68¢."),
        dip("tight_4c", "Open rise → 70 30%", 0.0, 0.70, 0.55, 0.30, "Buy the rising side near 50¢. 30%. Sell at 70¢."),
        dip("penny_press", "Open rise → 68 40", 0.0, 0.68, 0.40, 0.20, "Buy the rising side under 40¢. Sell at 68¢."),
        dip("deep_value", "Open rise → 70 45", 0.0, 0.70, 0.45, 0.20, "Buy the rising side under 45¢. Sell at 70¢."),
        dip("dip_buy", "Open rise → 68 15%b", 0.0, 0.68, 0.55, 0.15, "Buy the rising side near 50¢. 15%. Sell at 68¢."),
        dip("wide_gap", "Open rise → 70 15%", 0.0, 0.70, 0.55, 0.15, "Buy the rising side near 50¢. 15%. Sell at 70¢."),
        dip("burst", "Open rise → 68 30%b", 0.0, 0.68, 0.55, 0.30, "Buy the rising side near 50¢. 30%. Sell at 68¢."),
        dip("reclaim", "Open rise → 70 30%b", 0.0, 0.70, 0.55, 0.30, "Buy the rising side near 50¢. 30%. Sell at 70¢."),
        dip("min_5_7", "Open rise → 68 35", 0.0, 0.68, 0.35, 0.20, "Buy the rising side under 35¢. Sell at 68¢."),
        dip("dump_catch", "Open rise → 70 25%", 0.0, 0.70, 0.55, 0.25, "Buy the rising side near 50¢. 25%. Sell at 70¢."),
        dip("fade_spike", "Open rise → 68 25%", 0.0, 0.68, 0.55, 0.25, "Buy the rising side near 50¢. 25%. Sell at 68¢."),
        dip("gap_side", "Open rise → 70 20%b", 0.0, 0.70, 0.55, 0.20, "Buy the rising side near 50¢. 20%. Sell at 70¢."),
        dip("breakout_50", "Open rise → 68 20%", 0.0, 0.68, 0.55, 0.20, "Buy the rising side near 50¢. 20%. Sell at 68¢."),
        dip("momentum", "Open rise → 70 20%c", 0.0, 0.70, 0.55, 0.20, "Buy the rising side near 50¢. 20%. Sell at 70¢.")
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

    /**
     * Buy the cheaper side when it is [dip] under its high and under [maxAsk].
     * Sell when the bid is [up] above the entry. Stop 5¢ under the entry.
     */
    private fun dip(id: String, name: String, dip: Double, up: Double, maxAsk: Double, fraction: Double, rule: String) =
        algo(id, name, rule) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                val aPx = ask(t, side)
                val mins = t.tteMs / 60_000.0
                val window = mins in 9.0..11.0
                val cheap = aPx <= 0.40
                val deep = aPx <= 0.20
                if ((window && cheap) || deep) ScalpIntent.Buy(side, ScalpSettings.fraction) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                when {
                    b + 1e-9 >= 0.68 -> ScalpIntent.Sell(force = true)
                    b + 1e-9 <= pos.entry - ScalpSettings.stopLoss -> ScalpIntent.Sell(force = true)
                    t.tteMs in 1..60_000L -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        }

    /**
     * Buy under [maxAsk]. Hold until the bid is [arm] above the entry, then
     * sell when it falls [trail] off the peak. Closes in the last minute.
     */
    private fun trail(id: String, name: String, arm: Double, trail: Double, maxAsk: Double, fraction: Double, rule: String) =
        algo(id, name, rule) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                if (ask(t, side) < maxAsk) ScalpIntent.Buy(side, fraction) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                val armed = pos.peakBid + 1e-9 >= pos.entry + arm
                when {
                    armed && pos.peakBid - b + 1e-9 >= trail -> ScalpIntent.Sell(force = true)
                    b + 1e-9 <= pos.entry - 0.05 -> ScalpIntent.Sell(force = true)
                    t.tteMs in 1..60_000L -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        }

    private fun rising(t: ScalpTape): String? {
        val yesUp = t.yesBids.size >= 3 && t.yesBids.last() > t.yesBids[t.yesBids.size - 3]
        val noUp = t.noBids.size >= 3 && t.noBids.last() > t.noBids[t.noBids.size - 3]
        return when {
            yesUp && !noUp -> "YES"
            noUp && !yesUp -> "NO"
            else -> null
        }
    }

    private fun falling(t: ScalpTape, side: String): Boolean {
        val bids = if (side == "NO") t.noBids else t.yesBids
        if (bids.size < 3) return false
        return bids.last() + 1e-9 < bids[bids.size - 2] && bids[bids.size - 2] + 1e-9 < bids[bids.size - 3]
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
