package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.data.dto.MarketPositionDto
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import kotlin.math.abs

/**
 * Open Kalshi market position plus live marks for the home-screen card.
 *
 * [position_fp] is signed: positive = YES contracts, negative = NO
 * (GET /portfolio/positions).
 */
/** One resting Kalshi order from GET /portfolio/orders. Not a paper fill. */
data class RestingOrder(
    val orderId: String,
    val ticker: String,
    val side: String,
    val remaining: Double,
    val filled: Double,
    val price: Double?,
    val status: String
)

data class LivePosition(
    val ticker: String,
    val side: String,
    val contracts: Double,
    val exposureUsd: Double,
    val avgCost: Double?,
    val realizedPnlUsd: Double? = null,
    val bestBid: Double? = null,
    val unrealizedPnlUsd: Double? = null,
    val closeTimeEpochMs: Long? = null,
    val title: String? = null,
    val displaySide: String = if (side == "NO") "NO / DOWN" else "YES / UP"
)

object PositionParser {
    fun parseMarket(dto: MarketPositionDto): LivePosition? {
        val raw = dto.positionFp?.trim()?.toDoubleOrNull() ?: return null
        if (!raw.isFinite() || abs(raw) < 0.01) return null
        val side = if (raw < 0) "NO" else "YES"
        val contracts = abs(raw)
        val exposure = dto.marketExposureDollars?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0
        val avg = if (contracts > 0.0 && exposure > 0.0) exposure / contracts else null
        val realized = dto.realizedPnlDollars?.trim()?.toDoubleOrNull()
        return LivePosition(
            ticker = dto.ticker,
            side = side,
            contracts = contracts,
            exposureUsd = exposure,
            avgCost = KalshiPrice.usable(avg) ?: avg?.takeIf { it.isFinite() && it > 0.0 },
            realizedPnlUsd = realized?.takeIf { it.isFinite() }
        )
    }

    fun parseAll(rows: List<MarketPositionDto>): List<LivePosition> =
        rows.mapNotNull { parseMarket(it) }

    fun decorate(position: LivePosition, market: MarketUiModel?, bid: Double?): LivePosition {
        val avg = position.avgCost
        val uPnl = if (bid != null && avg != null) (bid - avg) * position.contracts else null
        return position.copy(
            bestBid = bid,
            unrealizedPnlUsd = uPnl,
            closeTimeEpochMs = market?.closeTimeEpochMs,
            title = market?.title
        )
    }

    fun heldContracts(position: LivePosition): Int =
        kotlin.math.floor(position.contracts + 1e-9).toInt().coerceAtLeast(0)
}
