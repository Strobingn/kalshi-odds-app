package com.dirk.kalshiodds.data.local.history

data class HistoryBet(
    val id: String,
    val createdAtMs: Long,
    val ticker: String,
    val side: String,
    val contracts: Int,
    val price: Double,
    val stakeUsd: Double,
    val source: String,
    val result: String,
    val pnlUsd: Double?,
    val live: Boolean
)

data class HistorySession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val markets: Int = 0,
    val signals: Int = 0,
    val bets: Int = 0,
    val pnlUsd: Double? = null
)

data class SettingsChange(
    val id: Long = 0,
    val createdAtMs: Long,
    val key: String,
    val oldValue: String,
    val newValue: String,
    val snapshotJson: String? = null
)

data class HistoryPage<T>(
    val items: List<T>,
    val offset: Int,
    val hasMore: Boolean
)
