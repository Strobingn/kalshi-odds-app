package com.dirk.kalshiodds.signal.exit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwingExitEngineTest {

    private fun state(
        entry: Double = 0.30,
        bid: Double = 0.30,
        fair: Double? = 0.55,
        openedAtMs: Long = 0L,
        tickMs: Long = 20_000L
    ) = SwingExitEngine.initial(
        side = "YES",
        entryPrice = entry,
        openedAtMs = openedAtMs,
        firstBid = bid,
        fairSide = fair,
        firstTickMs = tickMs
    )

    @Test
    fun risingMarketHolds() {
        // Buy low at 30¢; the move runs 35 → 42 → 50 → 55¢. Never sell
        // into strength.
        var s = state()
        var t = 20_000L
        for (bid in listOf(0.35, 0.42, 0.50, 0.55)) {
            t += 5_000L
            val d = SwingExitEngine.update(s, bid, 0.58, 10, t, null)
            assertEquals(SwingExitEngine.Action.HOLD, d.action)
            s = d.state
        }
        assertEquals(0.55, s.peakBid, 1e-9)
    }

    @Test
    fun trailingStopFiresAfterTurn() {
        // Peak 55¢, then the bid gives back exactly the 4¢ trail → sell.
        var s = state()
        s = SwingExitEngine.update(s, 0.55, 0.58, 10, 30_000L, null).state
        val d = SwingExitEngine.update(s, 0.51, 0.58, 10, 35_000L, null)
        assertEquals(SwingExitEngine.Action.SELL, d.action)
        assertEquals(SwingExitEngine.Reason.TRAILING_STOP, d.reason)
        // Locks in a real profit net of the exit fee: 51¢ − ~1.8¢ = ~49.2¢.
        assertEquals(0.492, d.sellNetPerContract, 0.002)
        assertTrue(d.lockedPnlPerContract > 0.15)
    }

    @Test
    fun smallPullbackDoesNotFire() {
        var s = state()
        s = SwingExitEngine.update(s, 0.55, 0.58, 10, 30_000L, null).state
        val d = SwingExitEngine.update(s, 0.525, 0.58, 10, 35_000L, null)
        assertEquals(SwingExitEngine.Action.HOLD, d.action)
        assertEquals(0.025, d.drawdownFromPeak, 1e-9)
    }

    @Test
    fun momentumFlipFiresBeforeBidCollapses() {
        // Fair value rolls over hard (60% → 55% in 2 s) and the bid is
        // already off the peak by 2¢. The trailing stop (4¢) has not fired
        // yet — momentum exits earlier.
        var s = state(bid = 0.55, fair = 0.60)
        val d = SwingExitEngine.update(s, 0.53, 0.55, 10, 22_000L, null)
        assertEquals(SwingExitEngine.Action.SELL, d.action)
        assertEquals(SwingExitEngine.Reason.MOMENTUM_FLIP, d.reason)
        assertTrue(d.state.slopePerSec <= -0.002)
    }

    @Test
    fun deepItmRidesToSettlement() {
        // 98% fair with the bid wobbling 5¢ off peak: trailing would fire,
        // but selling a near-certainty just donates the exit fee.
        var s = state(entry = 0.90, bid = 0.97, fair = 0.98)
        s = SwingExitEngine.update(s, 0.97, 0.98, 10, 30_000L, null).state
        // Even inside the final minute, deep ITM holds.
        val d = SwingExitEngine.update(s, 0.92, 0.98, 10, 40_000L, 70_000L)
        assertEquals(SwingExitEngine.Action.HOLD, d.action)
    }

    @Test
    fun timeGuardBanksProfitBeforePrint() {
        // 30¢ entry, bid 68¢ with 30 s to close and fair value only 70%:
        // bank it instead of sweating the 60-sample settlement average.
        val s = state()
        val close = 100_000L
        val d = SwingExitEngine.update(s, 0.68, 0.70, 10, close - 30_000L, close)
        assertEquals(SwingExitEngine.Action.SELL, d.action)
        assertEquals(SwingExitEngine.Reason.TIME_GUARD, d.reason)
    }

    @Test
    fun stopLossCutsImmediately() {
        // 9¢ underwater — below the 8¢ stop — inside the warm-up window.
        // The stop-loss never waits.
        val s = state()
        val d = SwingExitEngine.update(s, 0.21, 0.30, 10, 3_000L, null)
        assertEquals(SwingExitEngine.Action.SELL, d.action)
        assertEquals(SwingExitEngine.Reason.STOP_LOSS, d.reason)
    }

    @Test
    fun warmupBlocksTrailingButNotForever() {
        // Peak 40¢ at entry tick, give back 4¢ only 8 s in: too twitchy,
        // the warm-up gate holds it. Same give-back after 20 s: sell.
        val s = state(bid = 0.40, openedAtMs = 0L, tickMs = 0L)
        val early = SwingExitEngine.update(s, 0.36, 0.55, 10, 8_000L, null)
        assertEquals(SwingExitEngine.Action.HOLD, early.action)
        assertEquals(0.40, early.state.peakBid, 1e-9)
        val later = SwingExitEngine.update(early.state, 0.36, 0.55, 10, 20_000L, null)
        assertEquals(SwingExitEngine.Action.SELL, later.action)
        assertEquals(SwingExitEngine.Reason.TRAILING_STOP, later.reason)
    }

    @Test
    fun overpricedBidLocksGain() {
        // Model says the contract is worth 45¢; the book bids 55¢. Sell
        // the overpay: 55¢ − ~1.8¢ fee ≥ 45¢ + margin.
        val s = state()
        val d = SwingExitEngine.update(s, 0.55, 0.45, 10, 40_000L, null)
        assertEquals(SwingExitEngine.Action.SELL, d.action)
        assertEquals(SwingExitEngine.Reason.LOCK_OVERPRICED, d.reason)
    }

    @Test
    fun sellDecisionIsSticky() {
        var s = state()
        s = SwingExitEngine.update(s, 0.55, 0.58, 10, 30_000L, null).state
        val fired = SwingExitEngine.update(s, 0.51, 0.58, 10, 35_000L, null)
        assertEquals(SwingExitEngine.Action.SELL, fired.action)
        // A rebound does not un-fire the decision — no flicker.
        val after = SwingExitEngine.update(fired.state, 0.60, 0.62, 10, 40_000L, null)
        assertEquals(SwingExitEngine.Action.SELL, after.action)
        assertEquals(SwingExitEngine.Reason.TRAILING_STOP, after.reason)
    }

    @Test
    fun noBidNeverSells() {
        val s = state()
        val d = SwingExitEngine.update(s, null, 0.55, 10, 40_000L, null)
        assertEquals(SwingExitEngine.Action.HOLD, d.action)
        assertEquals("no exit bid", d.note)
    }
}
