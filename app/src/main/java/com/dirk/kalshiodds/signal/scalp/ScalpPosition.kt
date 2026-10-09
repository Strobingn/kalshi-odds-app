package com.dirk.kalshiodds.signal.scalp

enum class ScalpMode { PAPER, LIVE }

enum class ScalpPositionStatus { OPEN, CLOSED }

/**
 * One scalper position: a YES bought at the ask after a dip, held for a
 * bounce. Prices are Kalshi integer cents (1..99). P&L is tracked in cents
 * so no float money ever leaks into the ledger.
 */
data class ScalpPosition(
    val id: String,
    val ticker: String,
    /** Always "YES" — the scalper only buys YES at the ask. */
    val side: String = "YES",
    val entryPriceCents: Int,
    val contracts: Int,
    val entryTimeMs: Long,
    val mode: ScalpMode,
    val status: ScalpPositionStatus = ScalpPositionStatus.OPEN,
    val exitPriceCents: Int? = null,
    val exitTimeMs: Long? = null,
    val exitReason: ExitReason? = null,
    /** Realized P&L in cents: exit proceeds − entry cost − fees, both legs. */
    val pnlCents: Int? = null,
    /** Live-mode Kalshi order tracking (null in paper). */
    val clientOrderId: String? = null,
    val orderId: String? = null,
    /** Taker fee paid on the entry leg, in cents. */
    val entryFeeCents: Int = 0,
    /** Taker fee paid on the exit leg, in cents. */
    val exitFeeCents: Int = 0
)

/**
 * Flat ledger row for [ScalpPositionStore.trades] — one row per entry and
 * one per exit, mirroring how [com.dirk.kalshiodds.data.local.results.SqliteResultsStore]
 * keeps append-only history. `action` is "ENTER" or "EXIT".
 */
data class ScalpTradeRow(
    val id: Long,
    val positionId: String,
    val ticker: String,
    val action: String,
    val priceCents: Int,
    val contracts: Int,
    val feeCents: Int,
    /** EXIT rows only: realized P&L for the round trip, in cents. */
    val pnlCents: Int?,
    val reason: String?,
    val mode: String,
    val clientOrderId: String?,
    val createdAtMs: Long
)
