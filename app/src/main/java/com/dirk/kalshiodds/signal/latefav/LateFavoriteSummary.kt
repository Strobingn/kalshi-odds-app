package com.dirk.kalshiodds.signal.latefav

import java.util.Locale

/**
 * Home-card copy for the late-favorite paper tracker. Pure so it is unit
 * tested; the composable only lays these strings out.
 */
data class LateFavoriteSummary(
    val title: String,
    val recordLine: String,
    val pnlLine: String,
    val worseLine: String,
    val openLine: String,
    val note: String
) {
    companion object {
        const val TITLE = "Late favorite · PAPER"
        const val NOTE = "Paper only. Unproven: needs ~2,000 settled windows. Loses ~19 wins per loss at 95¢."
        const val TARGET_SETTLED = 2000

        fun of(state: LateFavoriteState): LateFavoriteSummary {
            val t = state.totals
            val winPct = t.winRate?.let { String.format(Locale.US, " (%.1f%%)", it * 100.0) }.orEmpty()
            val voids = if (t.voids > 0) " · ${t.voids} void" else ""
            val record = "${t.wins}-${t.losses}$winPct · ${t.settledBets} / $TARGET_SETTLED settled$voids"
            val perBet = t.perBetUsd?.let { " · ${money(it)}/bet" }.orEmpty()
            val pnl = "P&L ${money(t.pnlUsd)}$perBet"
            val worsePerBet = t.perBetWorseUsd?.let { " · ${money(it)}/bet" }.orEmpty()
            val worse = "Worse fill (+1¢) ${money(t.pnlWorseUsd)}$worsePerBet"
            val open = state.openEntries
            val openLine = if (open.isEmpty()) {
                "Open: none"
            } else {
                val shown = open.take(3).joinToString(" · ") {
                    String.format(Locale.US, "%s %s @ %.0f¢", shortTicker(it.ticker), sideLabel(it.side), it.ask * 100.0)
                }
                val more = if (open.size > 3) " · +${open.size - 3}" else ""
                "Open: $shown$more"
            }
            return LateFavoriteSummary(TITLE, record, pnl, worse, openLine, NOTE)
        }

        fun money(v: Double): String {
            val sign = if (v < 0) "−" else "+"
            return String.format(Locale.US, "%s$%.2f", sign, kotlin.math.abs(v))
        }

        private fun sideLabel(side: String): String =
            if (side.equals("YES", ignoreCase = true)) "UP" else "DOWN"

        private fun shortTicker(ticker: String): String = ticker.substringAfter('-', ticker)
    }
}
