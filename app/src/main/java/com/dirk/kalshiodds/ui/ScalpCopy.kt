package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.scalp.BankrollPoint
import com.dirk.kalshiodds.signal.scalp.ExitReason
import com.dirk.kalshiodds.signal.scalp.ScalpStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Scalp track-record display copy — formatting only, never changes a trade.
 * Money comes in integer cents from [com.dirk.kalshiodds.signal.scalp.ScalpStats]
 * and is formatted to dollars here at display time.
 */
object ScalpCopy {
    const val TITLE = "Scalp"
    const val SCREEN_TITLE = "Scalp track record"
    const val SCREEN_SUBTITLE =
        "Paper scalper ledger · net of Kalshi taker fees, both legs · times UTC"
    const val TOTAL_NET = "TOTAL NET P&L"
    const val TRADES = "Trades"
    const val WON = "Won"
    const val LOST = "Lost"
    const val WIN_LOSS = "Win / Loss"
    const val AVG_WIN = "Avg win"
    const val AVG_LOSS = "Avg loss"
    const val BIGGEST_WIN = "Biggest win"
    const val BIGGEST_LOSS = "Biggest loss"
    const val TOTAL_FEES = "Total fees paid"
    const val WIN_RATE = "Win rate"
    const val BANKROLL_TITLE = "Paper bankroll"
    const val BY_STRATEGY = "By strategy"
    const val BY_COIN = "By coin"
    const val BY_HOUR = "By hour (UTC)"
    const val ALL_TRADES = "All trades"
    const val OPEN_POSITIONS = "Open positions"
    const val EMPTY =
        "No scalp trades yet. Enable the scalper in Settings and keep Live signals running."
    const val LOADING_FAILED = "Couldn't read the scalp ledger"
    const val FEES_NOTE = "Fees are the taker fees the ledger stored per leg."
    const val EM_DASH = "—"

    /** `+$1.23` / `−$0.45` (U+2212 minus, matching the app's copy style). */
    fun signedUsd(cents: Long): String {
        val sign = if (cents < 0) "−" else "+"
        return String.format(Locale.US, "%s$%.2f", sign, abs(cents) / 100.0)
    }

    /** Plain `$1.23` for unsigned magnitudes (fees, bankroll level). */
    fun usd(cents: Long): String = String.format(Locale.US, "$%.2f", cents / 100.0)

    fun winRateLabel(fraction: Double): String =
        String.format(Locale.US, "%.0f%%", fraction * 100.0)

    /** e.g. `3W / 1L` — the header's win/loss counts. */
    fun winLossLabel(wins: Int, losses: Int): String = "${wins}W / ${losses}L"

    /**
     * Recent-direction indicator for the Home card: newest-first W/L marks
     * of the last [max] closed trades — "W L W W L" (newest on the left).
     */
    fun recentStreak(newestFirstWins: List<Boolean>, max: Int = 5): String =
        newestFirstWins.take(max).joinToString(" ") { if (it) "W" else "L" }

    /** `DIP_HUNT` → "Dip Hunt" via the enum's own label. */
    fun strategyLabel(strategy: ScalpStrategy): String = strategy.label

    fun reasonLabel(reason: ExitReason): String = when (reason) {
        ExitReason.TARGET -> "TARGET"
        ExitReason.STOP -> "STOP"
        ExitReason.TIMEOUT -> "TIMEOUT"
        ExitReason.MOMENTUM_FADE -> "MOMENTUM_FADE"
    }

    private val hhMmUtc = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun hhMmUtc(atMs: Long): String = hhMmUtc.get().format(Date(atMs))

    /** `12:04 → 12:11` entry-to-exit span in UTC. */
    fun tradeTimeSpan(entryMs: Long, exitMs: Long): String =
        "${hhMmUtc(entryMs)} → ${hhMmUtc(exitMs)}"

    /**
     * Home-card headline: net P&L + trade count, e.g.
     * `+$4.20 net · 12 trades`.
     */
    fun cardLine(netCents: Long, tradeCount: Int): String =
        "${signedUsd(netCents)} net · $tradeCount trades"

    /** Bankroll axis label, e.g. `$100.00`. */
    fun bankrollLabel(cents: Long): String = usd(cents)

    fun bankrollRange(points: List<BankrollPoint>): Pair<Long, Long>? {
        if (points.isEmpty()) return null
        val values = points.map { it.bankrollCents }
        return values.minOrNull()!! to values.maxOrNull()!!
    }
}
