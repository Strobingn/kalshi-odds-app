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
     * Trail books copy the v2 combo: buy under 50¢, wait until the gain
     * reaches [arm], then sell [trail] off the peak. The rest stay fixed-target.
     */
    val ALL: List<ScalpAlgo> = listOf(
        trail("late_squeeze", "Trail 8→2", 0.08, 0.02, 0.50, 0.30, "Buy under 50¢. 30%. After +8¢, sell 2¢ off the peak."),
        trail("all_in_cheap", "Trail 10→2", 0.10, 0.02, 0.50, 0.25, "Buy under 50¢. 25%. After +10¢, sell 2¢ off the peak."),
        trail("all_in_under_50", "Trail 15→2", 0.15, 0.02, 0.50, 0.25, "Buy under 50¢. 25%. After +15¢, sell 2¢ off the peak."),
        trail("first_5", "Trail 6→2", 0.06, 0.02, 0.50, 0.20, "Buy under 50¢. 20%. After +6¢, sell 2¢ off the peak."),
        trail("no_cap", "Trail 12→2", 0.12, 0.02, 0.50, 0.25, "Buy under 50¢. 25%. After +12¢, sell 2¢ off the peak."),
        trail("slow_trail", "Trail 8→3", 0.08, 0.03, 0.50, 0.25, "Buy under 50¢. 25%. After +8¢, sell 3¢ off the peak."),
        trail("tight_spread", "Trail 10→3", 0.10, 0.03, 0.50, 0.20, "Buy under 50¢. 20%. After +10¢, sell 3¢ off the peak."),
        trail("tight_4c", "Trail 5→2", 0.05, 0.02, 0.50, 0.20, "Buy under 50¢. 20%. After +5¢, sell 2¢ off the peak."),
        trail("penny_press", "Trail 8→2 40", 0.08, 0.02, 0.40, 0.20, "Buy under 40¢. 20%. After +8¢, sell 2¢ off the peak."),
        trail("deep_value", "Trail 10→2 45", 0.10, 0.02, 0.45, 0.20, "Buy under 45¢. 20%. After +10¢, sell 2¢ off the peak."),
        trail("dip_buy", "Trail 8→2 15%", 0.08, 0.02, 0.50, 0.15, "Buy under 50¢. 15%. After +8¢, sell 2¢ off the peak."),
        trail("wide_gap", "Trail 20→2", 0.20, 0.02, 0.50, 0.30, "Buy under 50¢. 30%. After +20¢, sell 2¢ off the peak."),
        trail("burst", "Trail 8→2 30%", 0.08, 0.02, 0.50, 0.30, "Buy under 50¢. 30%. After +8¢, sell 2¢ off the peak."),
        trail("reclaim", "Trail 15→2 30%", 0.15, 0.02, 0.50, 0.30, "Buy under 50¢. 30%. After +15¢, sell 2¢ off the peak."),
        trail("min_5_7", "Trail 8→2 35", 0.08, 0.02, 0.35, 0.20, "Buy under 35¢. 20%. After +8¢, sell 2¢ off the peak."),
        dip("dump_catch", "Under 50 → +8", 0.05, 0.08, 0.50, 0.25, "Buy under 50¢ anytime. 25%. Sell when the bid is 8¢ up."),
        dip("fade_spike", "Under 50 → +15", 0.05, 0.15, 0.50, 0.25, "Buy under 50¢ anytime. 25%. Sell when the bid is 15¢ up."),
        dip("gap_side", "Under 50 → +10", 0.05, 0.10, 0.50, 0.15, "Buy under 50¢ anytime. 15%. Sell when the bid is 10¢ up."),
        dip("breakout_50", "Under 50 → +8 20%", 0.05, 0.08, 0.50, 0.20, "Buy under 50¢ anytime. 20%. Sell when the bid is 8¢ up."),
        dip("momentum", "Under 50 → +12", 0.05, 0.12, 0.50, 0.20, "Buy under 50¢ anytime. 20%. Sell when the bid is 12¢ up.")
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
     * Buy the cheaper side under 50¢ at any time in the window. Sell when the
     * bid is up a fixed amount. Never hold to settlement.
     */
    private fun dip(id: String, name: String, dip: Double, up: Double, maxAsk: Double, fraction: Double, rule: String) =
        algo(id, name, rule) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                val aPx = ask(t, side)
                if (aPx < maxAsk) ScalpIntent.Buy(side, fraction) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                when {
                    b + 1e-9 >= pos.entry + up -> ScalpIntent.Sell(force = true)
                    b + 1e-9 <= pos.entry - 0.05 -> ScalpIntent.Sell(force = true)
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
