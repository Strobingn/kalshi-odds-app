package com.dirk.kalshiodds.signal.scalp

/**
 * Narrow ledger contract the scalper engine needs — [ScalpPositionStore]
 * is the SQLite-backed implementation, tests substitute an in-memory fake.
 * All methods are synchronous; implementations must never throw into the
 * engine (SQLite failures are swallowed inside the store).
 */
interface ScalpLedger {
    /** Restore the in-memory cache from disk (call once at engine start). */
    fun hydrateFromDisk()

    fun openPosition(): ScalpPosition?

    fun openPositions(): List<ScalpPosition>

    /** ENTER rows in `[nowMs − windowMs, nowMs]`. */
    fun entriesSince(nowMs: Long, windowMs: Long): Int

    /** Realized P&L cents of today's EXIT rows (negative when losing). */
    fun realizedPnlCentsToday(nowMs: Long): Long

    /** Persist a fresh open position; returns the stored row. */
    fun recordEnter(position: ScalpPosition, entryFeeCents: Int, clientOrderId: String?): ScalpPosition

    /** Persist an exit and mark the position closed. */
    fun recordExit(
        positionId: String,
        exitPriceCents: Int,
        exitTimeMs: Long,
        reason: ExitReason,
        pnlCents: Int,
        exitFeeCents: Int
    )

    /** Attach live-order ids to an open position after a successful place. */
    fun attachOrderIds(positionId: String, clientOrderId: String, orderId: String?)
}
