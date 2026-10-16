package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FillModelTest {
    private fun taker(depth: Double, ageMs: Long = 0L) = FillModel.Quote(
        marketable = true, price = 0.60, size = 10.0, depth = depth, spread = 0.02,
        bookAgeMs = ageMs, secondsRemaining = 400.0
    )

    @Test
    fun takerFillDependsOnDepthAndFreshness() {
        assertEquals(1.0, FillModel.pFill(taker(depth = 50.0)), 1e-12)
        assertEquals(0.5, FillModel.pFill(taker(depth = 5.0)), 1e-12)
        assertEquals(0.0, FillModel.pFill(taker(depth = 50.0, ageMs = 61_000L)), 1e-12)
        assertTrue(FillModel.pFill(taker(depth = 50.0, ageMs = 20_000L)) < 1.0)
    }

    @Test
    fun makerFillFallsWithQueueAhead() {
        fun maker(queue: Double) = FillModel.Quote(
            marketable = false, price = 0.90, size = 10.0, depth = queue, spread = 0.02,
            bookAgeMs = 0L, secondsRemaining = 3600.0
        )
        assertTrue(FillModel.pFill(maker(0.0)) > FillModel.pFill(maker(200.0)))
    }

    @Test
    fun paperFillsAreCappedAtDisplayedDepth() {
        assertEquals(7, FillModel.cappedContracts(20, 7))
        assertEquals(20, FillModel.cappedContracts(20, 500))
        assertEquals(0, FillModel.cappedContracts(20, null))
        assertEquals(0, FillModel.cappedContracts(20, 0))
    }

    @Test
    fun feeIsSevenPercentRoundedUpOnEveryFill() {
        // 10 × 0.07 × 0.5 × 0.5 = 0.175 → $0.18
        assertEquals(0.018, FillModel.feePerContract(0.50, 10), 1e-12)
        assertEquals(0.18, KalshiFee.total(10, 0.50, KalshiFee.TAKER_COEFFICIENT), 1e-12)
        // 1 contract at 0.95: 0.07×0.95×0.05 = 0.003325 → $0.01
        assertEquals(0.01, FillModel.feePerContract(0.95, 1), 1e-12)
    }

    @Test
    fun expectedNetFormula() {
        val net = ExpectedNet.perContract(pFill = 0.8, pWin = 0.7, price = 0.6, feePerContract = 0.02, adversePerContract = 0.01)
        assertEquals(0.8 * (0.7 - 0.6 - 0.02 - 0.01), net, 1e-12)
        assertEquals(10 * net, ExpectedNet.of(0.8, 0.7, 10.0, 0.6, 0.02, 0.01), 1e-12)
        // Gross +5¢ but fees + adverse eat it.
        assertTrue(ExpectedNet.perContract(1.0, 0.65, 0.60, 0.02, 0.04) < 0.0)
    }

    @Test
    fun queueReplayConservativeVsOptimistic() {
        val prints = listOf(QueueReplay.Print(0.90, 30.0, 10L), QueueReplay.Print(0.90, 30.0, 20L))
        val cons = QueueReplay.judge(0.90, 10.0, 80.0, 0.91, 0L, prints, 0.89)
        assertFalse(cons.filled)
        val opt = QueueReplay.judge(0.90, 10.0, 80.0, 0.91, 0L, prints, 0.89, QueueReplay.Mode.OPTIMISTIC)
        assertTrue(opt.filled)
        assertEquals(20L, opt.timeToFillMs)
        val through = QueueReplay.judge(0.90, 10.0, 1_000.0, 0.91, 0L, listOf(QueueReplay.Print(0.89, 1.0, 5L)), null)
        assertTrue(through.filled)
        // Prints before the decision never count (no look-ahead into the past book either).
        val early = QueueReplay.judge(0.90, 10.0, 0.0, 0.91, 100L, listOf(QueueReplay.Print(0.80, 100.0, 50L)), null)
        assertFalse(early.filled)
    }
}
