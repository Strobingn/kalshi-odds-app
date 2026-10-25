package com.dirk.kalshiodds.decision

import java.util.Locale

/**
 * Paper-only brake for one side of the current 15-minute market.
 *
 * Entry still requires a fresh price / fair-gap signal, and a winning side
 * may re-enter without a count cap. This only stops new clips on a side whose
 * own window P&L (closed net plus open mark-to-bid) is through the floor, or
 * whose latest closes are a run of losers. The other side is not affected.
 * A new ticker has no rows, so the next window starts clear.
 */
object WindowLossGate {
    /** Dollars of window loss on one side. Zero turns this leg off. */
    const val DEFAULT_LOSS_USD = 8.0

    /** Leading losing closes on one side. Zero turns this leg off. */
    const val DEFAULT_ADVERSE_FILLS = 3

    data class Verdict(val pnlUsd: Double, val adverseStreak: Int, val reason: String?)

    fun sideName(side: String): String =
        if (side.equals("NO", true) || side.equals("DOWN", true)) "DOWN" else "UP"

    fun sameSide(tradeSide: String, side: String): Boolean = sideName(tradeSide) == sideName(side)

    fun evaluate(
        trades: List<ScalpTrade>,
        ticker: String,
        side: String,
        bid: Double?,
        lossFloorUsd: Double = DEFAULT_LOSS_USD,
        adverseFills: Int = DEFAULT_ADVERSE_FILLS,
        primaryOnly: Boolean = true
    ): Verdict {
        var realized = 0.0
        var unrealized = 0.0
        val closed = ArrayList<ScalpTrade>()
        for (t in trades) {
            if (ticker.isNotEmpty() && !t.ticker.equals(ticker, true)) continue
            if (!sameSide(t.side, side)) continue
            if (primaryOnly && !t.isPrimary) continue
            if (t.state == ScalpState.CLOSED) {
                val net = t.netUsd?.takeIf { it.isFinite() } ?: continue
                realized += net
                closed += t
            } else {
                unrealized += t.unrealizedUsd(bid)?.takeIf { it.isFinite() } ?: 0.0
            }
        }
        val pnl = realized + unrealized
        val streak = adverseStreak(closed)
        val floorHit = lossFloorUsd > 0.0 && pnl <= -lossFloorUsd + 1e-9
        val streakHit = adverseFills > 0 && streak >= adverseFills
        val reason = when {
            !floorHit && !streakHit -> null
            pnl <= -0.005 -> String.format(Locale.US, "%s paused: window −$%.2f", sideName(side), -pnl)
            else -> String.format(Locale.US, "%s paused: %d losing fills", sideName(side), streak)
        }
        return Verdict(pnl, streak, reason)
    }

    /** Newest close first. A scratch or a winner ends the run. */
    fun adverseStreak(closed: List<ScalpTrade>): Int {
        val ordered = closed.sortedWith(
            compareByDescending<ScalpTrade> { it.closedAtMs ?: it.entryAtMs ?: it.signalAtMs }
                .thenByDescending { it.id }
        )
        var n = 0
        for (t in ordered) {
            val net = t.netUsd?.takeIf { it.isFinite() } ?: continue
            if (net < -1e-9) n++ else break
        }
        return n
    }
}
