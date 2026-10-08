package com.dirk.kalshiodds.signal.sizing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetExpectedValueMakerTest {

    @Test
    fun takerEvSubtractsFeeAndHalfSpread() {
        // Fair 55¢, mid 50¢, 2¢ spread → pay 51¢, gross +4¢, minus ~1.8¢ fee.
        val r = NetExpectedValue.compute(
            fairYes = 0.55, mid = 0.50, spreadDollars = 0.02, stakeUsd = 5.0
        )
        assertEquals("YES", r.side)
        assertEquals(0.51, r.contractPrice, 1e-9)
        assertTrue(r.feePerContract > 0.0)
        assertTrue(r.netEv > 0.0)
        assertTrue(r.netEv < r.grossEv)
    }

    @Test
    fun makerEvEarnsSpreadAndPaysLowerFee() {
        // Same market as a resting bid: pay 49¢, KXBTC15M maker fee is 0,
        // discounted by 50% fill probability and 1¢ adverse selection.
        val taker = NetExpectedValue.compute(
            fairYes = 0.55, mid = 0.50, spreadDollars = 0.02, stakeUsd = 5.0
        )
        val maker = NetExpectedValue.computeMaker(
            fairYes = 0.55, mid = 0.50, spreadDollars = 0.02,
            fillProbability = 0.5, stakeUsd = 5.0
        )
        assertEquals(0.49, maker.contractPrice, 1e-9)
        assertTrue(maker.feePerContract < taker.feePerContract)
        // EV is scaled by fill probability.
        assertTrue(maker.netEv <= 0.5 * (0.55 - 0.01 - 0.49) + 1e-9)
    }

    @Test
    fun bestOfReturnsTakerAndMaker() {
        val (taker, maker) = NetExpectedValue.bestOf(
            fairYes = 0.60, mid = 0.50, spreadDollars = 0.04, fillProbability = 0.6
        )
        assertEquals("YES", taker.side)
        assertEquals("YES", maker.side)
        assertTrue(maker.contractPrice < taker.contractPrice)
    }

    @Test
    fun unaffordableStakeYieldsNegativeEv() {
        // A 10¢ stake cannot buy even one 50¢ contract: fee is +Infinity.
        val r = NetExpectedValue.compute(
            fairYes = 0.90, mid = 0.50, spreadDollars = 0.0, stakeUsd = 0.10
        )
        assertTrue(r.netEv < 0.0)
    }
}
