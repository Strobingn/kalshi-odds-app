package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.history.HistoryAssembler
import com.dirk.kalshiodds.data.local.history.HistoryBet
import com.dirk.kalshiodds.data.local.history.HistoryPager
import com.dirk.kalshiodds.data.local.history.HistorySession
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.history.SettingsRestore
import com.dirk.kalshiodds.domain.MarketUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class HistoryUiState(
    val tab: Int = 0,
    val bets: List<HistoryBet> = emptyList(),
    val signals: List<HistoryAssembler.SignalLine> = emptyList(),
    val sessions: List<HistorySession> = emptyList(),
    val settings: List<SettingsChange> = emptyList(),
    val markets: List<SettledWindowRow> = emptyList(),
    val totals: HistoryAssembler.Totals = HistoryAssembler.Totals(),
    val pnl: List<Pair<Long, Double>> = emptyList(),
    val source: HistoryAssembler.SourceFilter = HistoryAssembler.SourceFilter.ALL,
    val coin: HistoryAssembler.CoinFilter = HistoryAssembler.CoinFilter.ALL,
    val date: HistoryAssembler.DateFilter = HistoryAssembler.DateFilter.ALL,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val message: String? = null,
    val currentSettingsJson: String? = null
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = KalshiOddsApp.from(app).container
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()
    private val chartCache = ConcurrentHashMap<String, MarketUiModel>()
    private val chartLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _chartMarket = MutableStateFlow<MarketUiModel?>(null)
    val chartMarket: StateFlow<MarketUiModel?> = _chartMarket.asStateFlow()

    init { load(0) }

    fun load(tab: Int, offset: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            val page = 40
            val cur = _state.value
            val next = when (tab) {
                0 -> betsPage(cur, offset, page)
                1 -> signalsPage(offset, page, cur)
                2 -> sessionsPage(offset, page, cur)
                3 -> settingsPage(offset, page, cur)
                else -> marketsPage(offset, page, cur)
            }
            _state.value = next
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore) return
        load(s.tab, s.offset + 40)
    }

    fun setSource(v: HistoryAssembler.SourceFilter) {
        _state.value = _state.value.copy(source = v)
        load(0, 0)
    }

    fun setCoin(v: HistoryAssembler.CoinFilter) {
        _state.value = _state.value.copy(coin = v)
        load(0, 0)
    }

    fun setDate(v: HistoryAssembler.DateFilter) {
        _state.value = _state.value.copy(date = v)
        load(0, 0)
    }

    fun restoreSettings(json: String) {
        viewModelScope.launch {
            runCatching { container.preferences.restoreSnapshot(json) }
            _state.value = _state.value.copy(message = "Settings restored from that snapshot")
        }
    }

    /** Placeholder immediately. Bids load on [Dispatchers.IO] and publish through [chartMarket]. */
    fun marketModel(ticker: String): MarketUiModel {
        chartCache[ticker]?.let { cached ->
            _chartMarket.value = cached
            return cached
        }
        return marketModel(
            com.dirk.kalshiodds.data.local.archive.SettledWindowRow(
                ticker = ticker,
                series = com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(ticker),
                result = ""
            )
        )
    }

    fun marketModel(row: SettledWindowRow): MarketUiModel {
        chartCache[row.ticker]?.let { cached ->
            _chartMarket.value = cached
            return cached
        }
        val placeholder = settledToMarket(row, emptyList())
        _chartMarket.value = placeholder
        if (chartLoading.add(row.ticker)) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val stored = runCatching { container.archive.settledWindow(row.ticker) }.getOrNull() ?: row
                    val bids = runCatching {
                        container.archive.bidHistory(stored.ticker, (stored.closeMs ?: 0L) - 3_600_000L, 240)
                    }.getOrElse { emptyList() }
                    val full = settledToMarket(stored, bids)
                    chartCache[row.ticker] = full
                    _chartMarket.value = full
                } finally {
                    chartLoading.remove(row.ticker)
                }
            }
        }
        return placeholder
    }

    private fun betsPage(cur: HistoryUiState, offset: Int, page: Int): HistoryUiState {
        val since = HistoryAssembler.sinceMs(cur.date, System.currentTimeMillis())
        val all = HistoryAssembler.bets(
            tickets = container.resultsStore.recentTickets(400),
            paper = container.paper.book.snapshot(),
            source = cur.source,
            coin = cur.coin,
            sinceMs = since
        )
        val sliced = HistoryPager.page(all, offset, page)
        val shown = if (offset == 0) sliced.items else cur.bets + sliced.items
        return cur.copy(
            tab = 0,
            bets = shown,
            totals = HistoryAssembler.totals(all),
            pnl = HistoryAssembler.cumulativePnl(all),
            offset = offset,
            hasMore = sliced.hasMore,
            message = null
        )
    }

    private fun signalsPage(offset: Int, page: Int, cur: HistoryUiState): HistoryUiState {
        val snaps = container.resultsStore.recentSnapshots((offset + page).coerceAtMost(200))
        val settled = container.archive.recentSettled(null, 400)
        val all = HistoryAssembler.signals(snaps, settled)
        val sliced = HistoryPager.page(all, offset, page)
        return cur.copy(
            tab = 1,
            signals = if (offset == 0) sliced.items else cur.signals + sliced.items,
            offset = offset,
            hasMore = sliced.hasMore
        )
    }

    private fun sessionsPage(offset: Int, page: Int, cur: HistoryUiState): HistoryUiState {
        val rows = container.archive.recentSessions(offset + page)
        val sliced = HistoryPager.page(rows, offset, page)
        return cur.copy(
            tab = 2,
            sessions = if (offset == 0) sliced.items else cur.sessions + sliced.items,
            offset = offset,
            hasMore = sliced.hasMore
        )
    }

    private suspend fun settingsPage(offset: Int, page: Int, cur: HistoryUiState): HistoryUiState {
        val rows = container.archive.recentSettingsChanges(page, offset)
        val live = runCatching { container.preferences.hydrate() }.getOrElse {
            com.dirk.kalshiodds.signal.config.SignalSettings()
        }
        return cur.copy(
            tab = 3,
            settings = if (offset == 0) rows else cur.settings + rows,
            currentSettingsJson = SettingsRestore.snapshot(live),
            offset = offset,
            hasMore = rows.size >= page
        )
    }

    private fun marketsPage(offset: Int, page: Int, cur: HistoryUiState): HistoryUiState {
        val rows = container.archive.recentSettled(null, offset + page)
        val sliced = HistoryPager.page(rows, offset, page)
        return cur.copy(
            tab = 4,
            markets = if (offset == 0) sliced.items else cur.markets + sliced.items,
            offset = offset,
            hasMore = sliced.hasMore
        )
    }
}

internal fun settledToMarket(row: SettledWindowRow, bids: List<BidPoint>): MarketUiModel {
    val yes = row.result.equals("yes", ignoreCase = true)
    return MarketUiModel(
        ticker = row.ticker,
        title = row.ticker,
        subtitle = "Settled ${row.result.uppercase()}",
        floorStrike = row.strikeUsd,
        yesBid = null,
        yesAsk = null,
        noBid = null,
        noAsk = null,
        lastPrice = null,
        yesProbabilityPercent = null,
        noProbabilityPercent = null,
        volume = null,
        volume24h = null,
        closeTimeLocal = row.closeMs?.let { historyTime(it) },
        closeTimeEpochMs = row.closeMs,
        status = "determined",
        seriesLabel = row.series,
        bidHistory = bids
    )
}
