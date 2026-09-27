package com.dirk.kalshiodds.signal.lastminute

/**
 * Immutable last-minute view attached to a [com.dirk.kalshiodds.domain.MarketUiModel].
 * Formatting lives in [LastMinuteCopy]. Never places an order.
 */
enum class LastMinutePhase {
    WAITING,
    LIVE,
    FIRED,
    NO_PLAY
}

data class LastMinuteSideQuote(
    val side: String,
    val displaySide: String,
    val winChance: Double,
    val ask: Double?,
    val evPerDollar: Double?,
    val contracts: Int,
    val costUsd: Double,
    val feeUsd: Double,
    val profitIfWinUsd: Double,
    val qualifies: Boolean,
    val depthLimited: Boolean,
    val depthContracts: Int?
)

data class LastMinuteFired(
    val ticker: String,
    val side: String,
    val displaySide: String,
    val winChance: Double,
    val ask: Double,
    val evPerDollar: Double,
    val contracts: Int,
    val costUsd: Double,
    val feeUsd: Double,
    val profitIfWinUsd: Double,
    val depthLimited: Boolean,
    val depthContracts: Int?,
    val tauSec: Int,
    val x: Double,
    val obsMean: Double,
    val sigS: Double,
    val firedAtMs: Long
)

data class LastMinuteSnapshot(
    val phase: LastMinutePhase,
    val tauSec: Int?,
    val startsInMs: Long?,
    val pUp: Double? = null,
    val up: LastMinuteSideQuote? = null,
    val down: LastMinuteSideQuote? = null,
    val fired: LastMinuteFired? = null,
    val spotUsd: Double? = null,
    val strikeUsd: Double? = null,
    val spotSource: String = "none",
    val x: Double? = null,
    val obsMean: Double? = null,
    val sigS: Double? = null,
    val flip: com.dirk.kalshiodds.signal.flip.FlipCheck.Verdict? = null
)

data class LastMinutePick(
    val id: String,
    val ticker: String,
    val side: String,
    val entryAsk: Double,
    val contracts: Int,
    val stakeUsd: Double,
    val feeUsd: Double,
    val winChance: Double,
    val evPerDollar: Double,
    val depthLimited: Boolean,
    val createdAtMs: Long,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null
) {
    val displaySide: String get() = if (side.equals("NO", true)) "DOWN" else "UP"
}

data class LastMinuteBookState(
    val picks: List<LastMinutePick> = emptyList()
)
