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
     * The Dip books are the winners. Buy a dip under a cap, sell at a fixed
     * profit. The Early books are the old idea, kept so they can be compared.
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
        dip("burst", "Dip 5→8 30%b", 0.05, 0.08, 0.52, 0.30, "Buy a 5¢ dip under 52¢. 30%. Sell when the bid is 8¢ up."),
        dip("reclaim", "Dip 5→15 30%", 0.05, 0.15, 0.52, 0.30, "Buy a 5¢ dip under 52¢. 30%. Sell when the bid is 15¢ up."),
        dip("min_5_7", "Dip 5→8 35", 0.05, 0.08, 0.35, 0.20, "Buy a 5¢ dip under 35¢. 20%. Sell when the bid is 8¢ up."),
        dip("dump_catch", "Dip 5→8 25%b", 0.05, 0.08, 0.52, 0.25, "Buy a 5¢ dip under 52¢. 25%. Sell when the bid is 8¢ up."),
        windowed("fade_spike", "Early cheap", 0.50, 0.03, 0.20, 0L, SEVEN_MIN, "20% under 50¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("gap_side", "Early 40", 0.40, 0.03, 0.20, 0L, SEVEN_MIN, "20% under 40¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("breakout_50", "Early 50", 0.50, 0.05, 0.20, 0L, SEVEN_MIN, "20% under 50¢ in the first 7 minutes. Sells a 5¢ drop."),
        windowed("momentum", "Early 45", 0.45, 0.04, 0.20, 0L, SEVEN_MIN, "20% under 45¢ in the first 7 minutes. Sells a 4¢ drop.")
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
     * Sell when the bid is [up] above the entry.
     */
    private fun dip(id: String, name: String, dip: Double, up: Double, maxAsk: Double, fraction: Double, rule: String) =
        algo(id, name, rule) { a, t ->
            val pos = a.position
            if (pos == null) {
                val side = cheaper(t)
                val aPx = ask(t, side)
                val high = if (side == "NO") t.noHigh else t.yesHigh
                if (aPx < maxAsk && high - aPx + 1e-9 >= dip) ScalpIntent.Buy(side, fraction) else ScalpIntent.Hold
            } else {
                val b = bid(t, pos.side)
                when {
                    b + 1e-9 >= pos.entry + up -> ScalpIntent.Sell(force = true)
                    t.tteMs in 1..90_000L -> ScalpIntent.Sell(force = true)
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
