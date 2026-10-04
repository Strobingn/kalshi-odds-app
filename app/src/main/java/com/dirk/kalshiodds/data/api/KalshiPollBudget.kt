package com.dirk.kalshiodds.data.api

/**
 * Shared pacing for every Kalshi REST caller.
 *
 * Basic tier refills about 200 read tokens/s and a GET costs ~10 tokens
 * (~20 GET/s). We stay near 8 read requests/s with a small burst so a
 * phone session cannot empty that bucket. Writes use a separate budget
 * and are never retried.
 */
object KalshiPollBudget {
    const val READ_PER_SEC = 8.0
    const val READ_BURST = 12.0
    const val WRITE_PER_SEC = 2.0
    const val WRITE_BURST = 4.0

    /** Home market list while the screen is visible. */
    const val HOME_VISIBLE_MS = 2_000L

    /** Positions + balance. Not on the market-list cadence. */
    const val POSITIONS_MS = 20_000L

    /** One batched settlement query per series. */
    const val SETTLEMENT_MS = 30_000L

    /** Resting D3 paper bids. Quotes stay on their own phase interval. */
    const val D3_TRADES_MS = 20_000L

    /** Identical GETs (market list, order book) collapse inside this window. */
    const val GET_CACHE_MS = 2_000L

    /** Inline pacing longer than this becomes a 429 the UI can show. */
    const val MAX_INLINE_WAIT_MS = 1_500L
}
