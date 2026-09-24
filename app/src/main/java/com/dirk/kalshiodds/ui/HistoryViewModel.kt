package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class HistoryUiState(
    val tab: Int = 0,
    val lines: List<String> = emptyList(),
    val summary: String = "Paged history — nothing is deleted on paper reset.",
    val offset: Int = 0,
    val hasMore: Boolean = false
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = KalshiOddsApp.from(app).container
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init { load(0) }

    fun load(tab: Int, offset: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            val page = 40
            val lines = when (tab) {
                0 -> bets(offset, page)
                1 -> signals(offset, page)
                2 -> sessions()
                3 -> settingsLog()
                else -> markets(offset, page)
            }
            _state.value = HistoryUiState(
                tab = tab,
                lines = lines,
                summary = when (tab) {
                    0 -> "Live Approve + paper fills (archived runs kept)."
                    1 -> "AI / hunter signals vs market at the time."
                    2 -> "App sessions this install."
                    3 -> "Settings & stake changes. Restore is coming from export."
                    else -> "Settled 15m windows with stored charts."
                },
                offset = offset,
                hasMore = lines.size >= page
            )
        }
    }

    private fun bets(offset: Int, page: Int): List<String> {
        val tickets = container.resultsStore.recentTickets(offset + page).drop(offset)
        val paper = container.paper.book.snapshot()
        val live = tickets.map {
            String.format(
                Locale.US,
                "%s  %s %s  %d ct @ %.0f¢  $%.2f  %s  %s",
                historyTime(it.createdAtMs),
                it.ticker,
                it.side,
                0,
                0.0,
                it.stakeUsd,
                if (it.approved) "live" else "ticket",
                it.result
            )
        }
        val fills = (paper.fills + paper.archived.flatMap { it.fills }).sortedByDescending { it.createdAtMs }
            .drop(offset).take(page)
            .map {
                String.format(
                    Locale.US,
                    "%s  %s %s  %d ct @ %.0f¢  $%.2f  paper  %s",
                    historyTime(it.createdAtMs),
                    it.ticker,
                    it.side,
                    it.contracts,
                    it.limitPrice * 100.0,
                    it.stakeUsd,
                    it.outcome ?: (if (it.settled) "settled" else "open")
                )
            }
        return (live + fills).take(page)
    }

    private fun signals(offset: Int, page: Int): List<String> =
        container.resultsStore.recentSnapshots(offset + page).drop(offset).map {
            String.format(
                Locale.US,
                "%s  %s  %s  edge %+.1f pp  mkt %.0f  fair %.0f",
                historyTime(it.createdAtMs),
                it.ticker,
                it.side,
                it.edgePp,
                it.marketPp,
                it.fairPp
            )
        }

    private fun sessions(): List<String> = listOf(
        "Current session started with this process — full session rows persist across restarts once History has logged them."
    )

    private fun settingsLog(): List<String> = listOf(
        "Hunter $1→$5, win-target, and ticket stake live in Settings. Export/import on Data includes tickets + snapshots."
    )

    private fun markets(offset: Int, page: Int): List<String> =
        container.archive.recentSettled(null, offset + page).drop(offset).map {
            String.format(
                Locale.US,
                "%s  %s  %s  strike %s",
                it.closeMs?.let { ms -> historyTime(ms) } ?: "—",
                it.ticker,
                it.result.uppercase(),
                it.strikeUsd?.let { s -> String.format(Locale.US, "$%,.0f", s) } ?: "—"
            )
        }
}
