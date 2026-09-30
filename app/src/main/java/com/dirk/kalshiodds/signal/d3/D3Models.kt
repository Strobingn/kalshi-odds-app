package com.dirk.kalshiodds.signal.d3

enum class D3Phase {
    WAITING,
    ACTIVE,
    CLOSED
}

data class D3Quote(
    val ticker: String,
    val eventTicker: String?,
    val title: String,
    val subtitle: String?,
    val strikeUsd: Double?,
    val yesBid: Double?,
    val yesAsk: Double?,
    val noBid: Double?,
    val noAsk: Double?,
    val yesBidSize: Double? = null,
    val yesAskSize: Double? = null,
    val noBidSize: Double? = null,
    val noAskSize: Double? = null,
    val closeTimeEpochMs: Long?,
    val status: String? = null
)

data class D3Signal(
    val ticker: String,
    val eventTicker: String?,
    val strikeUsd: Double?,
    val subtitle: String?,
    val side: String,
    val displaySide: String,
    val favAsk: Double,
    val favBid: Double,
    val spread: Double,
    val bidPrice: Double,
    val sizeAhead: Double,
    val depthContracts: Int?,
    val contracts: Int,
    val stakeUsd: Double,
    val feeUsd: Double,
    val allInUsd: Double,
    val winRate: Double,
    val qualifiedAtMs: Long
) {
    val isYes: Boolean get() = !side.equals("NO", true)
}

data class D3TradePrint(
    val ticker: String,
    val yesPrice: Double,
    val count: Double,
    val createdAtMs: Long
)

data class D3RestingBid(
    val ticker: String,
    val side: String,
    val bidPrice: Double,
    val sizeAhead: Double,
    val contracts: Int,
    val stakeUsd: Double,
    val feeUsd: Double,
    val placedAtMs: Long,
    val volumeAtBid: Double = 0.0
)

data class D3Pick(
    val id: String,
    val ticker: String,
    val eventTicker: String? = null,
    val strikeUsd: Double? = null,
    val subtitle: String? = null,
    val side: String,
    val bidPrice: Double,
    val contracts: Int,
    val stakeUsd: Double,
    val feeUsd: Double,
    val createdAtMs: Long,
    val filled: Boolean = false,
    val filledAtMs: Long? = null,
    val cancelledAtMs: Long? = null,
    val cancelReason: String? = null,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null
) {
    val displaySide: String get() = if (side.equals("NO", true)) "DOWN" else "UP"
    val dayKey: String get() = D3Window.dayKey(createdAtMs)
}

data class D3BookState(
    val picks: List<D3Pick> = emptyList()
)

data class D3Snapshot(
    val phase: D3Phase = D3Phase.WAITING,
    val eventTicker: String? = null,
    val closeTimeEpochMs: Long? = null,
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val startsInMs: Long? = null,
    val qualifying: List<D3Signal> = emptyList(),
    val resting: List<D3RestingBid> = emptyList(),
    val todayPicks: List<D3Pick> = emptyList(),
    val todayLine: String = D3Copy.NO_RESULT_YET,
    val feeType: String? = null,
    val makerFeeUsd: Double = 0.0
) {
    val liveTickers: Set<String>
        get() = (qualifying.map { it.ticker } + resting.map { it.ticker } + todayPicks.map { it.ticker }).toSet()

    companion object {
        val EMPTY = D3Snapshot()
    }
}
