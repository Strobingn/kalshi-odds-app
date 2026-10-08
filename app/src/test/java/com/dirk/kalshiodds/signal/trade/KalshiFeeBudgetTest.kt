package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiFeeBudgetTest {

    @Test
    fun contractsWithinBudgetNeverExceedsStake() {
        // Naive floor(5.00 / 0.50) = 10 contracts, but 10 × $0.50 + fee > $5.
        val naive = KalshiFee.contractsForStake(5.0, 0.50)
        val budgeted = KalshiFee.contractsWithinBudget(5.0, 0.50)
        assertTrue(budgeted <= naive)
        assertTrue(KalshiFee.totalCost(budgeted, 0.50) <= 5.0 + 1e-9)
        // And the next contract up would bust the stake.
        assertTrue(KalshiFee.totalCost(budgeted + 1, 0.50) > 5.0)
    }

    @Test
    fun contractsWithinBudgetAtCheapPriceDropsForFee() {
        // 25 × $0.20 spends the whole $5 stake, leaving nothing for the
        // fee, so the budget-aware count drops until position + fee ≤ $5.
        assertEquals(23, KalshiFee.contractsWithinBudget(5.0, 0.20))
    }

    @Test
    fun contractsWithinBudgetZeroWhenStakeTooSmall() {
        assertEquals(0, KalshiFee.contractsWithinBudget(0.005, 0.50))
        assertEquals(0, KalshiFee.contractsWithinBudget(0.0, 0.50))
    }

    @Test
    fun budgetPerContractIsFiniteWhenAffordable() {
        val fee = KalshiFee.budgetPerContract(0.50, 0.07, 5.0)
        assertTrue(fee.isFinite())
        assertTrue(fee > 0.0)
        // Amortized fee is near the model fee 0.07 × 0.5 × 0.5 = 1.75¢.
        assertEquals(0.0175, fee, 0.003)
    }

    @Test
    fun budgetPerContractInfiniteWhenUnaffordable() {
        assertEquals(Double.POSITIVE_INFINITY, KalshiFee.budgetPerContract(0.50, 0.07, 0.10))
    }
}
