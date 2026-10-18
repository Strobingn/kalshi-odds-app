package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import org.junit.Assert.*
import org.junit.Test

class ScalpNetRealityTest {
    private val eps = 1e-9
    @Test fun grossIsNetPlusFees() {
        val r = ScalpNetReality.of(listOf(settled("a", 1.00, 1, fees = 0.36), settled("b", -0.50, 2, fees = 0.30)), 0.0)
        assertEquals(0.50, r.netAfterFeesUsd, eps); assertEquals(0.66, r.feesUsd, eps); assertEquals(1.16, r.grossUsd, eps)
        assertEquals(r.netAfterFeesUsd, r.netAfterSlippageUsd, eps); assertEquals(2, r.trades)
    }
    @Test fun slippageCountsExitLegOnlyWhenSold() {
        val sold = ScalpNetReality.of(listOf(settled("a", 1.0, 1, contracts = 10, sold = 10)), 1.0)
        assertEquals(0.20, sold.slippageUsd, eps); assertEquals(0.80, sold.netAfterSlippageUsd, eps)
        val expired = ScalpNetReality.of(listOf(settled("b", 1.0, 1, contracts = 10, sold = 0)), 1.0)
        assertEquals(0.10, expired.slippageUsd, eps)
    }
    @Test fun countsPartialFills() {
        val r = ScalpNetReality.of(listOf(settled("a", 1.0, 1, contracts = 10, sold = 4), settled("b", 1.0, 2), settled("c", 1.0, 3, sold = 0)))
        assertEquals(1, r.partialFills)
    }
    @Test fun ignoresOpenNaNAndBadSlip() {
        val r = ScalpNetReality.of(listOf(settled("a", 1.0, 1, state = ScalpState.OPEN), settled("b", Double.NaN, 2), settled("c", 2.0, 3)), Double.NaN)
        assertEquals(1, r.trades); assertEquals(0.0, r.slippageUsd, eps); assertEquals(2.0, r.netAfterSlippageUsd, eps)
        assertEquals(0.0, ScalpNetReality.of(listOf(settled("c", 2.0, 3)), -3.0).slippageUsd, eps)
    }
    @Test fun emptyIsZero() {
        val r = ScalpNetReality.of(emptyList())
        assertEquals(0, r.trades); assertEquals(0.0, r.grossUsd, eps); assertEquals(0.0, r.netAfterSlippageUsd, eps)
    }
    @Test fun estimateFromAggregates() {
        val r = ScalpNetReality.estimate(netAfterFeesUsd = 5.0, feesUsd = 3.0, trades = 10, contractsPerTrade = 10, slipCentsPerContract = 1.0)
        assertEquals(8.0, r.grossUsd, eps); assertEquals(2.0, r.slippageUsd, eps); assertEquals(3.0, r.netAfterSlippageUsd, eps)
    }
    @Test fun formats() {
        assertEquals("+$1.23", ScalpNetReality.fmt(1.234)); assertEquals("\u2212$0.40", ScalpNetReality.fmt(-0.4)); assertEquals("+$0.00", ScalpNetReality.fmt(-0.001))
    }
}
