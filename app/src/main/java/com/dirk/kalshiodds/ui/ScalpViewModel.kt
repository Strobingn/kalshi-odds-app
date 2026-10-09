package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.scalp.ClosedTrade
import com.dirk.kalshiodds.signal.scalp.OpenPositionMark
import com.dirk.kalshiodds.signal.scalp.ScalpPosition
import com.dirk.kalshiodds.signal.scalp.ScalpStats
import com.dirk.kalshiodds.signal.scalp.ScalpStatsMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScalpScreenState(
    val loading: Boolean = true,
    val stats: ScalpStats? = null,
    /** Closed round trips, newest exit first — the ALL TRADES list. */
    val trades: List<ClosedTrade> = emptyList(),
    val openPositions: List<ScalpPosition> = emptyList(),
    /** Error text when the ledger read blew up; null otherwise. */
    val error: String? = null
)

/**
 * Feeds [ScalpScreen]. Reads the scalper ledger (`scalpLedger` in
 * [com.dirk.kalshiodds.AppContainer] — [com.dirk.kalshiodds.signal.scalp.ScalpPositionStore]
 * behind the [com.dirk.kalshiodds.signal.scalp.ScalpLedger] contract) on
 * [Dispatchers.IO]; the reads are served from the store's in-memory cache,
 * but the poll keeps the track record live as trades stream in.
 */
class ScalpViewModel(app: Application) : AndroidViewModel(app) {
    private val container = KalshiOddsApp.from(app).container

    private val _state = MutableStateFlow(ScalpScreenState())
    val state: StateFlow<ScalpScreenState> = _state.asStateFlow()

    @Volatile
    private var refreshing = false

    init {
        // Simple poll (2.5 s) while this ViewModel lives — trades stream in
        // live while the Live signals service runs. viewModelScope cancels
        // the loop on clear; the IO reads are cheap cache snapshots.
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(2_500)
            }
        }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { load() }
                }
                result.onSuccess { _state.value = it }
                    .onFailure { e ->
                        _state.value = _state.value.copy(
                            loading = false,
                            error = e.message ?: "Failed to load scalp ledger"
                        )
                    }
            } finally {
                refreshing = false
            }
        }
    }

    private fun load(): ScalpScreenState {
        val rows = container.scalpLedger.recentTrades(500)
        val paired = ScalpStatsMath.pairRows(rows)
        val now = System.currentTimeMillis()
        val open = container.scalpLedger.openPositions()
        // Mark an open position to the live book mid so the bankroll endpoint
        // reflects unrealized P&L; the book may be absent (engine idle).
        val openMark = open.firstOrNull()?.let { pos ->
            val mid = runCatching {
                container.scoring.book.orderBook(pos.ticker)?.mid01()
            }.getOrNull()
            mid?.let { OpenPositionMark(pos, kotlin.math.round(it * 100.0).toInt()) }
        }
        return ScalpScreenState(
            loading = false,
            stats = ScalpStatsMath.compute(paired, open = openMark, nowMs = now),
            trades = paired.sortedByDescending { it.exitTimeMs },
            openPositions = open,
            error = null
        )
    }
}
