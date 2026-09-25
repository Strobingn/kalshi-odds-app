package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.market.MarketRollover

/**
 * Paints home from [MarketRollover.Event], not a stale REST snapshot.
 * Retrying series become empty lists so [HomeMarkets.coinCards] shows
 * "Next window loading".
 */
object HomeSnapshotMerge {
    fun apply(snapshot: MarketsSnapshot?, event: MarketRollover.Event, nowMs: Long): MarketsSnapshot {
        val base = snapshot ?: MarketsSnapshot(
            btc = emptyList(),
            eth = emptyList(),
            sol = emptyList(),
            fetchedAtEpochMs = nowMs,
            fromCache = false
        )
        return base.copy(
            btc = slot(base.btc, event, KalshiApi.SERIES_BTC),
            eth = slot(base.eth, event, KalshiApi.SERIES_ETH),
            sol = slot(base.sol, event, KalshiApi.SERIES_SOL),
            fetchedAtEpochMs = nowMs
        )
    }

    private fun slot(
        current: List<MarketUiModel>,
        event: MarketRollover.Event,
        series: String
    ): List<MarketUiModel> {
        val next = event.active[series]
        if (next != null) return listOf(next)
        if (series in event.retrying) return emptyList()
        return current
    }
}
