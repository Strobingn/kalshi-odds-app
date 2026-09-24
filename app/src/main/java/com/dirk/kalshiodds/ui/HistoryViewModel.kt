package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.local.history.HistoryPager
import com.dirk.kalshiodds.data.local.history.SettingsRestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

data class HistoryLine(
    val id: String,
    val text: String,
    val restoreJson: String? = null
)

data class HistoryUiState(
    val tab: Int = 0,
    val lines: List<HistoryLine> = emptyList(),
    val summary: String = "Paged history — paper reset archives the old book.",
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val message: String? = null
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
                3 -> settingsLog(offset, page)
                else -> markets(offset, page)
            }
            _state.value = HistoryUiState(
                tab = tab,
                lines = if (offset == 0) lines else _state.value.lines + lines,
                summary = when (tab) {
                    0 -> "Live Approve + paper fills (archived runs kept)."
                    1 -> "AI / hunter signals vs market at the time."
                    2 -> "App sessions this install."
                    3 -> "Settings & stake changes. Restore reapplies hunter / win-target / bankroll."
                    else -> "Settled 15m windows with stored charts."
                },
                offset = offset,
                hasMore = lines.size >= page
            )
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore) return
        load(s.tab, s.offset + 40)
    }

    fun restoreSettings(json: String) {
        viewModelScope.launch {
            runCatching { container.preferences.restoreSnapshot(json) }
            _state.value = _state.value.copy(message = "Settings restored from that snapshot")
        }
    }

    private fun bets(offset: Int, page: Int): List<HistoryLine> {
        val tickets = container.resultsStore.recentTickets(offset + page)
        val paper = container.paper.book.snapshot()
        val live = tickets.map {
            HistoryLine(
                id = "live-${it.id}-${it.createdAtMs}",
                text = String.format(
                    Locale.US,
                    "%s  %s %s  $%.2f  %s  %s",
                    historyTime(it.createdAtMs),
                    it.ticker,
                    it.side,
                    it.stakeUsd,
                    if (it.approved) "live" else "ticket",
                    it.result
                )
            )
        }
        val fills = (paper.fills + paper.archived.flatMap { it.fills })
            .sortedByDescending { it.createdAtMs }
        val paperLines = fills.map {
            HistoryLine(
                id = "paper-${it.id}",
                text = String.format(
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
            )
        }
        return HistoryPager.page((live + paperLines).distinctBy { it.id }, offset, page).items
    }

    private fun signals(offset: Int, page: Int): List<HistoryLine> {
        val rows = container.resultsStore.recentSnapshots(offset + page).drop(offset)
        return rows.map {
            HistoryLine(
                id = "sig-${it.id}-${it.createdAtMs}",
                text = String.format(
                    Locale.US,
                    "%s  %s  %s  edge %+.1f pp  mkt %.0f  fair %.0f",
                    historyTime(it.createdAtMs),
                    it.ticker,
                    it.side,
                    it.edgePp,
                    it.marketPp,
                    it.fairPp
                )
            )
        }
    }

    private fun sessions(): List<HistoryLine> {
        val rows = container.archive.recentSessions(40)
        if (rows.isEmpty()) {
            return listOf(
                HistoryLine(
                    id = "sess-empty",
                    text = "Current process is the first recorded session. Later launches keep start/end here."
                )
            )
        }
        return rows.map {
            HistoryLine(
                id = "sess-${it.id}",
                text = String.format(
                    Locale.US,
                    "%s → %s  markets %d  signals %d  bets %d  %s",
                    historyTime(it.startedAtMs),
                    it.endedAtMs?.let { ms -> historyTime(ms) } ?: "open",
                    it.markets,
                    it.signals,
                    it.bets,
                    it.pnlUsd?.let { p -> String.format(Locale.US, "P&L $%.2f", p) } ?: ""
                )
            )
        }
    }

    private fun settingsLog(offset: Int, page: Int): List<HistoryLine> {
        val rows = container.archive.recentSettingsChanges(page, offset)
        if (rows.isEmpty() && offset == 0) {
            val snap = SettingsRestore.snapshot(container.preferences.let { runCatching { it }.getOrNull(); _liveSettings() })
            return listOf(
                HistoryLine(
                    id = "set-current",
                    text = "Current · ${SettingsRestore.label(_liveSettings())}",
                    restoreJson = snap
                )
            )
        }
        return rows.map {
            HistoryLine(
                id = "set-${it.id}-${it.createdAtMs}",
                text = "${historyTime(it.createdAtMs)}  ${it.key}: ${it.oldValue} → ${it.newValue}",
                restoreJson = it.snapshotJson
            )
        }
    }

    private fun _liveSettings() = runCatching {
        kotlinx.coroutines.runBlocking { container.preferences.hydrate() }
    }.getOrElse { com.dirk.kalshiodds.signal.config.SignalSettings() }

    private fun markets(offset: Int, page: Int): List<HistoryLine> =
        container.archive.recentSettled(null, offset + page).drop(offset).map {
            HistoryLine(
                id = "mkt-${it.ticker}",
                text = String.format(
                    Locale.US,
                    "%s  %s  %s  strike %s",
                    it.closeMs?.let { ms -> historyTime(ms) } ?: "—",
                    it.ticker,
                    it.result.uppercase(),
                    it.strikeUsd?.let { s -> String.format(Locale.US, "$%,.0f", s) } ?: "—"
                )
            )
        }
}
