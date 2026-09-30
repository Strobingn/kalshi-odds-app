package com.dirk.kalshiodds.signal.d3

import java.util.Locale
import kotlin.math.roundToInt

/**
 * User-facing D3 strings. Evidence numbers are static research copy —
 * they are not computed from the live paper book.
 */
object D3Copy {
    const val TITLE = "BTC daily 5 PM favourite (D3)"
    const val SECTION = "D3 daily favourite"
    const val NO_RESULT_YET = "No paper result yet today"
    const val NO_STRIKES = "No qualifying strikes"
    const val CONFIRM_POST_ONLY =
        "Post-only limit bid. Approve + REAL MONEY still required — never auto-placed."

    const val EVIDENCE =
        "History 297 fills, 284-13, +$137.66 after fees. BTC 115-4, +$70.52. Forward test started 2026-09-30."

    const val SETTINGS_BODY =
        "D3 watches Kalshi KXBTCD (the Bitcoin event that closes at 17:00 America/New_York). " +
            "From 14:00 to 16:00 ET it rests a post-only limit bid on any strike whose favourite " +
            "side (YES / NO) has a best ask between 85¢ and 97¢. Bid is best bid + 1¢ when the " +
            "spread is ≥2¢, otherwise best bid. Unfilled bids cancel at 16:00 ET or if the " +
            "favourite leaves the band. At most one position per strike per day. " +
            "Maker fee is read from the series fee type (quadratic; maker multiplier defaults " +
            "to 0 unless Kalshi lists the series). Paper (AI paper autopilot) simulates the " +
            "resting bid and only counts a fill on a trade-through or after the size ahead of " +
            "us trades. Live orders stay Approve + REAL MONEY, $10 all-in, never automatic. " +
            EVIDENCE

    fun phaseLine(snap: D3Snapshot): String = when (snap.phase) {
        D3Phase.WAITING -> waiting(snap.startsInMs)
        D3Phase.ACTIVE -> "Window active (14:00–16:00 ET)"
        D3Phase.CLOSED -> "Window closed"
    }

    fun waiting(startsInMs: Long?): String {
        val ms = startsInMs?.coerceAtLeast(0L) ?: 0L
        val totalSec = (ms / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) {
            String.format(Locale.US, "Waiting (starts in %d:%02d)", h, m)
        } else {
            String.format(Locale.US, "Waiting (starts in %d:%02d)", m, totalSec % 60)
        }
    }

    fun formatCents(price: Double): String {
        val c = price * 100.0
        return if (kotlin.math.abs(c - c.roundToInt()) < 1e-6) {
            String.format(Locale.US, "%.0f¢", c)
        } else {
            String.format(Locale.US, "%.2f¢", c)
        }
    }

    fun strikeLine(signal: D3Signal): String {
        val strike = signal.subtitle?.takeIf { it.isNotBlank() }
            ?: signal.strikeUsd?.let { String.format(Locale.US, "$%,.0f", it) }
            ?: signal.ticker
        return String.format(
            Locale.US,
            "%s · %s · ask %s · bid %s",
            strike,
            signal.displaySide,
            formatCents(signal.favAsk),
            formatCents(signal.bidPrice)
        )
    }

    fun notificationTitle(signal: D3Signal): String =
        "D3 favourite: BID ${signal.displaySide} at ${formatCents(signal.bidPrice)}"

    fun notificationBody(signal: D3Signal): String = strikeLine(signal) + " · Approve + REAL MONEY required"

    fun pickLine(pick: D3Pick): String {
        val filled = if (pick.filled) "filled" else "not filled"
        val result = when {
            !pick.settled -> "open"
            pick.won == true -> "WIN"
            pick.won == false -> "LOSS"
            else -> "void"
        }
        val pnl = pick.pnlUsd?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"
        val strike = pick.subtitle?.takeIf { it.isNotBlank() }
            ?: pick.strikeUsd?.let { String.format(Locale.US, "$%,.0f", it) }
            ?: pick.ticker
        return String.format(
            Locale.US,
            "%s · %s · %s · bid %s · %s · $%.2f fee $%.2f · %s %s",
            D3Window.dateLabel(pick.createdAtMs),
            strike,
            pick.displaySide,
            formatCents(pick.bidPrice),
            filled,
            pick.stakeUsd,
            pick.feeUsd,
            result,
            pnl
        )
    }

    fun recordLine(
        wins: Int,
        losses: Int,
        wonUsd: Double,
        lostUsd: Double,
        pnlUsd: Double,
        fillRate: Double?,
        logged: Int
    ): String {
        val fill = fillRate?.let { String.format(Locale.US, "fill %.0f%%", it * 100.0) } ?: "fill —"
        return String.format(
            Locale.US,
            "%d-%d · won $%.2f · lost $%.2f · net %+.2f · %s · %d logged",
            wins,
            losses,
            wonUsd,
            lostUsd,
            pnlUsd,
            fill,
            logged
        )
    }

    fun wonUsdLine(usd: Double): String = String.format(Locale.US, "Won $%.2f", usd)
    fun lostUsdLine(usd: Double): String = String.format(Locale.US, "Lost $%.2f", usd)
    fun netPnlLine(usd: Double): String = String.format(Locale.US, "Net P&L %+.2f", usd)
    fun fillRateLine(rate: Double?): String =
        rate?.let { String.format(Locale.US, "Fill rate %.0f%%", it * 100.0) } ?: "Fill rate —"

    fun todayPaperLine(picks: List<D3Pick>): String {
        if (picks.isEmpty()) return NO_RESULT_YET
        val filled = picks.filter { it.filled }
        val settled = filled.filter { it.settled && it.won != null }
        val wins = settled.count { it.won == true }
        val losses = settled.count { it.won == false }
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val open = filled.count { !it.settled }
        return String.format(
            Locale.US,
            "Today paper %d-%d · %+.2f · %d filled · %d open",
            wins,
            losses,
            pnl,
            filled.size,
            open
        )
    }
}
