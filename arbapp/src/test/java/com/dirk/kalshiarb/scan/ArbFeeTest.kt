package com.dirk.kalshiarb.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors shared with Dip Hunter's KalshiFeeAndWinTargetTest / LiveOrderSizerTest and pipeline.py. */
class ArbFeeTest {

    private fun fee(c: Long, dollars: Double): Long {
        val p = Math.round(dollars * 10_000).toInt()
        return ArbFee.debitCents(c, p) - Math.round(c * dollars * 100)
    }

    @Test
    fun nineAtFiftyDebitsFourSixtySix() {
        assertEquals(466L, ArbFee.debitCents(9, 5_000))
        assertEquals(1_600L, ArbFee.feeE4(9, 5_000))
    }

    @Test
    fun orderLevelFeeVectors() {
        assertEquals(2L, fee(1, 0.50))
        assertEquals(18L, fee(10, 0.50))
        assertEquals(175L, fee(100, 0.50))
        assertEquals(2L, fee(1, 0.20))
        assertEquals(28L, fee(25, 0.20))
        assertEquals(112L, fee(100, 0.20))
        assertEquals(1L, fee(1, 0.01))
        assertEquals(7L, fee(100, 0.01))
        assertEquals(1L, fee(1, 0.05))
        assertEquals(34L, fee(100, 0.05))
        assertEquals(1L, fee(1, 0.99))
        assertEquals(7L, fee(100, 0.99))
    }

    @Test
    fun exactCentIsNotBumped() {
        // 0.07 × 100 × 0.2 × 0.8 = 1.12 exactly; binary floats would make it 1.13.
        assertEquals(2_112L, ArbFee.debitCents(100, 2_000))
    }

    @Test
    fun sweepAcrossLevelsIsOneOrder() {
        val fills = listOf(Level(4_500, 10), Level(4_600, 5))
        // position 6.80, fees 0.17325 + 0.08694 → 7.06019 → $7.07
        assertEquals(707L, ArbFee.debitCents(fills))
        assertEquals(2_700L, ArbFee.feeE4(fills))
    }

    @Test
    fun emptyOrderCostsNothing() {
        assertEquals(0L, ArbFee.debitCents(emptyList()))
        assertEquals(0L, ArbFee.debitCents(0, 5_000))
    }
}
