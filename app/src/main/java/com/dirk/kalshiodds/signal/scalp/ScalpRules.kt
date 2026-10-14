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
     * Every book is an Early cheap variation. Buy the cheaper side under a
     * cap in the first 7 minutes. Hold the rise. Sell a small drop off the
     * high. Early cheap itself is left alone.
     */
    val ALL: List<ScalpAlgo> = listOf(
        windowed("late_squeeze", "Early cheap", 0.50, 0.03, 0.12, 0L, SEVEN_MIN, "12% under 50¢ in the first 7 minutes. Sells a 3¢ drop and takes the next one."),
        windowed("all_in_cheap", "Early 40", 0.40, 0.03, 0.10, 0L, SEVEN_MIN, "10% under 40¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("no_cap", "Early 45", 0.45, 0.03, 0.10, 0L, SEVEN_MIN, "10% under 45¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("all_in_under_50", "Early 50", 0.50, 0.05, 0.10, 0L, SEVEN_MIN, "10% under 50¢ in the first 7 minutes. Sells a 5¢ drop."),
        windowed("slow_trail", "Early 35", 0.35, 0.03, 0.10, 0L, SEVEN_MIN, "10% under 35¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("tight_spread", "Early 30", 0.30, 0.03, 0.10, 0L, SEVEN_MIN, "10% under 30¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("tight_4c", "Early 25", 0.25, 0.03, 0.08, 0L, SEVEN_MIN, "8% under 25¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("penny_press", "Early 20", 0.20, 0.03, 0.08, 0L, SEVEN_MIN, "8% under 20¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("deep_value", "Early 55", 0.55, 0.03, 0.10, 0L, SEVEN_MIN, "10% under 55¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("dip_buy", "Early 2¢", 0.50, 0.02, 0.10, 0L, SEVEN_MIN, "10% under 50¢ in the first 7 minutes. Sells a 2¢ drop."),
        windowed("wide_gap", "Early 4¢", 0.50, 0.04, 0.10, 0L, SEVEN_MIN, "10% under 50¢ in the first 7 minutes. Sells a 4¢ drop."),
        windowed("burst", "Early big", 0.50, 0.03, 0.15, 0L, SEVEN_MIN, "15% under 50¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("reclaim", "Early small", 0.50, 0.03, 0.06, 0L, SEVEN_MIN, "6% under 50¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("first_5", "First 5", 0.50, 0.03, 0.10, 0L, FIVE_MIN, "10% under 50¢ in the first 5 minutes. Sells a 3¢ drop, or a loser at minute 5."),
        windowed("min_5_7", "Minutes 5–7", 0.50, 0.03, 0.10, FIVE_MIN, SEVEN_MIN, "10% under 50¢ between minute 5 and 7. Sells a 3¢ drop."),
        windowed("dump_catch", "Early 40b", 0.40, 0.04, 0.12, 0L, SEVEN_MIN, "12% under 40¢ in the first 7 minutes. Sells a 4¢ drop."),
        windowed("fade_spike", "Early any", 0.55, 0.03, 0.10, 0L, SEVEN_MIN, "10% under 55¢ in the first 7 minutes. Sells a 3¢ drop."),
        windowed("gap_side", "Early 45b", 0.45, 0.04, 0.10, 0L, SEVEN_MIN, "10% under 45¢ in the first 7 minutes. Sells a 4¢ drop."),
        windowed("breakout_50", "Early 50b", 0.50, 0.04, 0.12, 0L, SEVEN_MIN, "12% under 50¢ in the first 7 minutes. Sells a 4¢ drop."),
        windowed("momentum", "Early 35b", 0.35, 0.04, 0.12, 0L, SEVEN_MIN, "12% under 35¢ in the first 7 minutes. Sells a 4¢ drop.")
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
