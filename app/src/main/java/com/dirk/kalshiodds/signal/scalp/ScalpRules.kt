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

@kotlinx.serialization.Serializable
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
    /** Off skips new buys. An open bet still follows the stop. */
    var enabled: Boolean = true

    /** Buy only when this many minutes, or fewer, are left in the window. */
    var lastMinutes: Int = 2

    /** Lowest ask it will pay, in dollars. */
    var minAsk: Double = 0.15

    /** Highest ask it will pay, in dollars. */
    var maxAsk: Double = 0.85

    /** Fraction of the book's cash (or the $20k start) per entry. */
    var fraction: Double = 0.25

    /**
     * Sell when the bid is this far under the entry.
     * 0 holds to the close: no stop, no giveback sell, no last-minute flatten.
     */
    var stopLoss: Double = 0.05

    /** Don't open again until this many milliseconds after the last look. 0 looks every quote. */
    var cadenceMs: Long = 0L
}

object ScalpCatalog {
    /** Each paper book. Not live cash. A book may trade through zero. */
    const val START_CASH = 20_000.0

    /**
     * The Simple dip books that were green. Buy a dip under a cap, sell at a
     * fixed profit, stop out 5¢ under the entry.
     */
    val ALL: List<ScalpAlgo> = listOf(
        dip("late_squeeze", "Late fav 85", 0.0, 0.0, 0.85, 0.15, "Last 2 min. Buy the favourite at 85¢+."),
        dip("all_in_cheap", "Late fav 90", 0.0, 0.0, 0.90, 0.15, "Last 2 min. Buy the favourite at 90¢+."),
        dip("all_in_under_50", "Late fav 88", 0.0, 0.0, 0.88, 0.15, "Last 2 min. Buy the favourite at 88¢+."),
        dip("first_5", "Late fav 92", 0.0, 0.0, 0.92, 0.15, "Last 2 min. Buy the favourite at 92¢+."),
        dip("no_cap", "Late fav 95", 0.0, 0.0, 0.95, 0.15, "Last 2 min. Buy the favourite at 95¢+."),
        dip("slow_trail", "Late fav 87", 0.0, 0.0, 0.87, 0.15, "Last 2 min. Buy the favourite at 87¢+."),
        dip("tight_spread", "Late fav 85 10%", 0.0, 0.0, 0.85, 0.10, "Last 2 min. 10%. Buy the favourite at 85¢+."),
        dip("tight_4c", "Late fav 90 10%", 0.0, 0.0, 0.90, 0.10, "Last 2 min. 10%. Buy the favourite at 90¢+."),
        dip("penny_press", "Late fav 88 20%", 0.0, 0.0, 0.88, 0.20, "Last 2 min. 20%. Buy the favourite at 88¢+."),
        dip("deep_value", "Late fav 92 10%", 0.0, 0.0, 0.92, 0.10, "Last 2 min. 10%. Buy the favourite at 92¢+."),
        dip("dip_buy", "Late fav 95 10%", 0.0, 0.0, 0.95, 0.10, "Last 2 min. 10%. Buy the favourite at 95¢+."),
        dip("wide_gap", "Late fav 85 20%", 0.0, 0.0, 0.85, 0.20, "Last 2 min. 20%. Buy the favourite at 85¢+."),
        dip("burst", "Late fav 90 20%", 0.0, 0.0, 0.90, 0.20, "Last 2 min. 20%. Buy the favourite at 90¢+."),
        dip("reclaim", "Late fav 87 10%", 0.0, 0.0, 0.87, 0.10, "Last 2 min. 10%. Buy the favourite at 87¢+."),
        dip("min_5_7", "Late fav 88 10%", 0.0, 0.0, 0.88, 0.10, "Last 2 min. 10%. Buy the favourite at 88¢+."),
        dip("dump_catch", "Late fav 92 20%", 0.0, 0.0, 0.92, 0.20, "Last 2 min. 20%. Buy the favourite at 92¢+."),
        dip("fade_spike", "Late fav 95 20%", 0.0, 0.0, 0.95, 0.20, "Last 2 min. 20%. Buy the favourite at 95¢+."),
        dip("gap_side", "Late fav 85 15%", 0.0, 0.0, 0.85, 0.15, "Last 2 min. 15%. Buy the favourite at 85¢+."),
        dip("breakout_50", "Late fav 90 15%", 0.0, 0.0, 0.90, 0.15, "Last 2 min. 15%. Buy the favourite at 90¢+."),
        dip("momentum", "Late fav 88 15%", 0.0, 0.0, 0.88, 0.15, "Last 2 min. 15%. Buy the favourite at 88¢+.")
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
                if (!ScalpSettings.enabled) return@algo ScalpIntent.Hold
                val mins = t.tteMs / 60_000.0
                if (mins > ScalpSettings.lastMinutes) return@algo ScalpIntent.Hold
                val side = if (t.yesBid > t.noBid) "YES" else "NO"
                val aPx = ask(t, side)
                if (aPx + 1e-9 < ScalpSettings.minAsk || aPx > ScalpSettings.maxAsk + 1e-9) {
                    return@algo ScalpIntent.Hold
                }
                ScalpIntent.Buy(side, ScalpSettings.fraction)
            } else {
                val stop = ScalpSettings.stopLoss
                if (stop <= 1e-9) return@algo ScalpIntent.Hold
                val b = bid(t, pos.side)
                val mins = t.tteMs / 60_000.0
                val drop = pos.peakBid - b
                val recover = mins > 5.0 && drop < 0.08
                when {
                    recover -> ScalpIntent.Hold
                    drop + 1e-9 >= 0.03 -> ScalpIntent.Sell(force = true)
                    b + 1e-9 <= pos.entry - stop -> ScalpIntent.Sell(force = true)
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
