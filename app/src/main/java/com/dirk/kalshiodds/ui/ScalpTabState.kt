package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.decision.PriceLadder
import com.dirk.kalshiodds.decision.ScalpModels

/** 0.3.50 Scalp tab state (computed off the main thread by [OddsViewModel]). Paper only. */
data class ScalpTabState(
    val coin: String? = null,
    val leaderAllTime: List<ScalpModels.Row> = emptyList(),
    val leaderWindow: List<ScalpModels.Row> = emptyList(),
    val leaderByCoin: Map<String, List<ScalpModels.Row>> = emptyMap(),
    /** 0.3.51: per-coin six-model board for today (ET). */
    val leaderTodayByCoin: Map<String, List<ScalpModels.Row>> = emptyMap(),
    /** 0.3.52 algorithm cards (one per model, fixed order). */
    val algoAllTime: Map<ScalpModels.Model, ScalpModels.Row> = emptyMap(),
    val algoToday: Map<ScalpModels.Model, ScalpModels.Row> = emptyMap(),
    /** model → coin → all-time / today row. */
    val algoByCoin: Map<ScalpModels.Model, Map<String, ScalpModels.Row>> = emptyMap(),
    val algoTodayByCoin: Map<ScalpModels.Model, Map<String, ScalpModels.Row>> = emptyMap(),
    val windows: List<ScalpModels.WindowRow> = emptyList(),
    val currentWindowKey: String? = null,
    val sliceUsd: Double = 0.0,
    val markets: List<ScalpTabMarket> = emptyList(),
    val selectedTicker: String? = null,
    val side: String = "YES",
    val ladder: List<PriceLadder.Row> = emptyList(),
    val heldContracts: Int = 0,
    val heldEntry: Double? = null,
    val openOrders: List<PriceLadder.Order> = emptyList(),
    val bookAgeMs: Long? = null,
    val secondsLeft: Long? = null,
    val updatedAtMs: Long = 0L
)

data class ScalpTabMarket(val ticker: String, val coin: String, val closeMs: Long?, val label: String)

/** 0.3.52 one algorithm's own page. */
data class ScalpAlgoDetail(
    val model: ScalpModels.Model? = null,
    val allTime: ScalpModels.Row? = null,
    val today: ScalpModels.Row? = null,
    val byCoin: Map<String, ScalpModels.Row> = emptyMap(),
    val trades: List<com.dirk.kalshiodds.decision.ScalpTrade> = emptyList(),
    val equity: List<Double> = emptyList()
)
