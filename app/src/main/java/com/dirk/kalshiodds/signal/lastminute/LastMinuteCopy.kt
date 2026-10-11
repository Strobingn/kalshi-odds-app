package com.dirk.kalshiodds.signal.lastminute

import java.util.Locale
import kotlin.math.roundToInt

/**
 * User-facing last-minute strings. Pure formatting — never a stub.
 * Live-book replay (163 windows, Sep 25–27) went 3-76 / −$664 at $10.
 * Full-history backtest is still positive, so the UI must not imply a
 * live edge. The only EV shown is the per-signal model number, labeled
 * [MODEL_EV_LABEL].
 */
object LastMinuteCopy {
    const val TITLE = "Last-minute play"
    const val UNPROVEN_SUBTITLE = "Unproven on live order books - tracking on paper"
    const val MODEL_EV_LABEL = "Model EV (unproven)"
    const val CONFIRM_UNPROVEN =
        "This strategy has not yet made money on live order books."
    const val NO_PLAY = "No play this window"
    const val SECTION = "Last-minute strategy"
    const val DEPTH_LIMITED = "Depth limited size"
    const val AI_BACKTEST = LastMinuteConstants.AI_MODEL_BACKTEST_LABEL

    fun waiting(startsInMs: Long?): String {
        val ms = startsInMs?.coerceAtLeast(0L) ?: 0L
        val totalSec = (ms / 1000L).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "Last-minute play: waiting (starts in %d:%02d)", m, s)
    }

    fun headline(snap: LastMinuteSnapshot?): String {
        if (snap?.phase == LastMinutePhase.FIRED && snap.fired != null) {
            val ok = com.dirk.kalshiodds.signal.flip.FlipCheck.allowsFired(
                snap.fired,
                snap.spotUsd,
                snap.strikeUsd,
                snap.fired.ask
            )
            if (!ok) return snap.flip?.noBetLine ?: TITLE
            return buyLine(snap.fired)
        }
        return when (snap?.phase) {
            LastMinutePhase.WAITING -> waiting(snap.startsInMs)
            LastMinutePhase.LIVE -> snap.flip?.noBetLine ?: TITLE
            LastMinutePhase.FIRED -> TITLE
            LastMinutePhase.NO_PLAY -> NO_PLAY
            null -> waiting(null)
        }
    }

    fun flipLine(snap: LastMinuteSnapshot?): String? = snap?.flip?.noBetLine

    fun buyLine(fired: LastMinuteFired): String {
        val cents = formatCents(fired.ask)
        return String.format(
            Locale.US,
            "BUY %s %s × %d = $%.2f",
            fired.displaySide,
            cents,
            fired.contracts,
            fired.costUsd
        )
    }

    fun modelEvLine(evPerDollar: Double?): String {
        val ev = evPerDollar?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "%.2f", it)
        } ?: "—"
        return "$MODEL_EV_LABEL $ev"
    }

    fun formatCents(price: Double): String {
        val c = price * 100.0
        return if (absAlmostWhole(c)) {
            String.format(Locale.US, "%.0f¢", c)
        } else {
            String.format(Locale.US, "%.2f¢", c)
        }
    }

    fun winChanceLine(label: String, quote: LastMinuteSideQuote?): String {
        val pct = quote?.winChance?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "%.1f%%", it * 100.0)
        } ?: "—"
        return "$label $pct"
    }

    fun sideLiveLine(quote: LastMinuteSideQuote?): String {
        val q = quote ?: return "—"
        val ask = q.ask?.let { formatCents(it) } ?: "—"
        val ev = modelEvLine(q.evPerDollar)
        val size = if (q.contracts > 0) {
            String.format(Locale.US, "%d ct · $%.2f", q.contracts, q.costUsd)
        } else {
            "0 ct"
        }
        val depth = if (q.depthLimited) " · $DEPTH_LIMITED" else ""
        return "${q.displaySide} ${String.format(Locale.US, "%.1f%%", q.winChance * 100.0)} · ask $ask · $ev · $size$depth"
    }

    fun spotSourceLine(snap: LastMinuteSnapshot?): String {
        val src = snap?.spotSource?.takeIf { it.isNotBlank() } ?: "none"
        val px = snap?.spotUsd?.let { String.format(Locale.US, "$%,.2f", it) } ?: "—"
        return "Spot $px · $src"
    }

    fun pickLine(pick: LastMinutePick): String {
        val result = when {
            !pick.settled -> "open"
            pick.won == true -> "WIN"
            pick.won == false -> "LOSS"
            else -> "void"
        }
        val pnl = pick.pnlUsd?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"
        return String.format(
            Locale.US,
            "%s %s · %s × %d · $%.2f fee $%.2f · p=%.1f%% · %s %s",
            pick.displaySide,
            pick.ticker,
            formatCents(pick.entryAsk),
            pick.contracts,
            pick.stakeUsd,
            pick.feeUsd,
            pick.winChance * 100.0,
            result,
            pnl
        )
    }

    fun recordLine(wins: Int, losses: Int, winRate: Double?, wonUsd: Double, lostUsd: Double, pnlUsd: Double): String {
        val pct = winRate?.let { "${(it * 100.0).roundToInt()}%" } ?: "—"
        return String.format(
            Locale.US,
            "%d-%d · %s · won $%.2f · lost $%.2f · net %+.2f",
            wins,
            losses,
            pct,
            wonUsd,
            lostUsd,
            pnlUsd
        )
    }

    fun wonUsdLine(usd: Double): String = String.format(Locale.US, "Won $%.2f", usd)

    fun lostUsdLine(usd: Double): String = String.format(Locale.US, "Lost $%.2f", usd)

    fun netPnlLine(usd: Double): String = String.format(Locale.US, "Net P&L %+.2f", usd)

    fun notificationTitle(fired: LastMinuteFired): String =
        "Last-minute play (paper): BUY ${fired.displaySide} at ${formatCents(fired.ask)}"

    fun notificationBody(fired: LastMinuteFired): String = notificationTitle(fired)

    private fun absAlmostWhole(c: Double): Boolean = kotlin.math.abs(c - c.roundToInt()) < 1e-6
}
