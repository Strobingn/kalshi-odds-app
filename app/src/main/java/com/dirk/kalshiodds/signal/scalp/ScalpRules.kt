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
     * Every book is a Simple dip. Buy when the cheaper side has dipped from
     * its high and the ask is under a cap. Sell when the bid is up a fixed
     * amount from the entry. No trailing stop.
     */
    val ALL: List<ScalpAlgo> = listOf(
        dip("late_squeeze", "Dip 5→8 30%", 0.05, 0.08, 0.52, 0.30, "Buy a 5¢ dip under 52¢. 30%. Sell when the bid is 8¢ up."),
        dip("all_in_cheap", "Dip 5→8 25%", 0.05, 0.08, 0.52, 0.25, "Buy a 5¢ dip under 52¢. 25%. Sell when the bid is 8¢ up."),
        dip("all_in_under_50", "Dip 5→15 25%", 0.05, 0.15, 0.52, 0.25, "Buy a 5¢ dip under 52¢. 25%. Sell when the bid is 15¢ up."),
        dip("first_5", "Dip 5→8", 0.05, 0.08, 0.52, 0.20, "Buy a 5¢ dip under 52¢. 20%. Sell when the bid is 8¢ up."),
        dip("no_cap", "Dip 5→10", 0.05, 0.10, 0.52, 0.25, "Buy a 5¢ dip under 52¢. 25%. Sell when the bid is 10¢ up."),
        dip("slow_trail", "Dip 5→12", 0.05, 0.12, 0.52, 0.25, "Buy a 5¢ dip under 52¢. 25%. Sell when the bid is 12¢ up."),
        dip("tight_spread", "Dip 4→8", 0.04, 0.08, 0.50, 0.20, "Buy a 4¢ dip under 50¢. 20%. Sell when the bid is 8¢ up."),
        dip("tight_4c", "Dip 6→8", 0.06, 0.08, 0.50, 0.20, "Buy a 6¢ dip under 50¢. 20%. Sell when the bid is 8¢ up."),
        dip("penny_press", "Dip 5→8 40", 0.05, 0.08, 0.40, 0.20, "Buy a 5¢ dip under 40¢. 20%. Sell when the bid is 8¢ up."),
        dip("deep_value", "Dip 5→8 55", 0.05, 0.08, 0.55, 0.20, "Buy a 5¢ dip under 55¢. 20%. Sell when the bid is 8¢ up."),
        dip("dip_buy", "Dip 3→8", 0.03, 0.08, 0.52, 0.20, "Buy a 3¢ dip under 52¢. 20%. Sell when the bid is 8¢ up."),
        dip("wide_gap", "Dip 5→6", 0.05, 0.06, 0.52, 0.20, "Buy a 5¢ dip under 52¢. 20%. Sell when the bid is 6¢ up."),
        dip("burst", "Dip 5→8 30%", 0.05, 0.08, 0.52, 0.30, "Buy a 5¢ dip under 52¢. 30%. Sell when the bid is 8¢ up."),
        dip("reclaim", "Dip 5→15 30%", 0.05, 0.15, 0.52, 0.30, "Buy a 5¢ dip under 52¢. 30%. Sell when the bid is 15¢ up."),
        dip("min_5_7", "Dip 5→8 35", 0.05, 0.08, 0.35, 0.20, "Buy a 5¢ dip under 35¢. 20%. Sell when the bid is 8¢ up."),
        dip("dump_catch", "Dip 5→8 25%", 0.05, 0.08, 0.52, 0.25, "Buy a 5¢ dip under 52¢. 25%. Sell when the bid is 8¢ up."),
        dip("fade_spike", "Dip 5→15 25%", 0.05, 0.15, 0.52, 0.25, "Buy a 5¢ dip under 52¢. 25%. Sell when the bid is 15¢ up."),
        dip("gap_side", "Dip 5→8 15%", 0.05, 0.08, 0.52, 0.15, "Buy a 5¢ dip under 52¢. 15%. Sell when the bid is 8¢ up."),
        dip("breakout_50", "Dip 5→8 20%", 0.05, 0.08, 0.52, 0.20, "Buy a 5¢ dip under 52¢. 20%. Sell when the bid is 8¢ up."),
        dip("momentum", "Dip 5→8 20%b", 0.05, 0.08, 0.50, 0.20, "Buy a 5¢ dip under 50¢. 20%. Sell when the bid is 8¢ up.")
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
     * Buy the cheaper side when it is under 50¢ and rising. Sell when the
     * bid is up a fixed amount. Can take the next one in the same window.
     */
    private fun dip(id: String, name: String, dip: Double, up: Double, maxAsk: Double, fraction: Double, rule: String) =
        algo(id, name, rule) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                val aPx = ask(t, side)
                val rising = rising(t, side)
                if (aPx < 0.50 && rising) ScalpIntent.Buy(side, fraction) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                when {
                    b + 1e-9 >= pos.entry + up -> ScalpIntent.Sell(force = true)
                    t.tteMs in 1..90_000L -> ScalpIntent.Sell(force = true)
                    else -> ScalpIntent.Hold
                }
            }
        }

    private fun rising(t: ScalpTape, side: String): Boolean {
        val bids = if (side == "NO") t.noBids else t.yesBids
        if (bids.size < 2) return true
        return bids.last() + 1e-9 >= bids[bids.size - 2]
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
