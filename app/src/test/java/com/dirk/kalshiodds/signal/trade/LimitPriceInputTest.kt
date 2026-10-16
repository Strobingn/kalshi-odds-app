package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitPriceInputTest {
    @Test
    fun centsOutside1To99AreRejected() {
        assertNull(LimitPriceInput.parse("0"))
        assertNull(LimitPriceInput.parse("100"))
        assertNull(LimitPriceInput.parse("1.5"))
        assertNull(LimitPriceInput.parse(""))
        assertEquals(1, LimitPriceInput.parse("1"))
        assertEquals(99, LimitPriceInput.parse("99"))
        assertNotNull(LimitPriceInput.validationError("0"))
        assertNull(LimitPriceInput.validationError("25"))
    }

    @Test
    fun typedPriceRecomputesStakeContractsFeeAndCap() {
        val base = sample(0.25)
        val priced = LimitPriceInput.apply(base, 40, SignalConstants.DEFAULT_FEE_RATE)
        val expected = LiveOrderSizer.size(0.40, LiveOrderSizer.LIVE_ALL_IN_CAP_USD, SignalConstants.DEFAULT_FEE_RATE)
        assertTrue(expected.ok)
        assertEquals(0.40, priced.limitPrice, 1e-9)
        assertEquals(expected.count, priced.contracts)
        assertEquals(expected.allInUsd, priced.allInUsd!!, 1e-6)
        assertEquals(expected.feeUsd, priced.feeUsd!!, 1e-6)
        assertEquals(expected.profitIfWinUsd, priced.profitIfWinUsd!!, 1e-6)
        assertTrue(priced.allInUsd!! <= LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-6)
        assertEquals(base.clientOrderId, priced.clientOrderId)
        assertEquals("25", LimitPriceInput.suggestedCents(0.25))
    }

    @Test
    fun paperTicketUsesTheSameCapMath() {
        val paper = sample(0.20).copy(kind = TicketKind.MANUAL)
        val priced = LimitPriceInput.apply(paper, 10, SignalConstants.DEFAULT_FEE_RATE)
        val expected = LiveOrderSizer.size(0.10)
        assertEquals(expected.count, priced.contracts)
        assertTrue(priced.allInUsd!! <= 10.0 + 1e-6)
    }

    @Test
    fun approveStaysDisabledWhileSubmitting() {
        assertTrue(ApproveControls.enabled(credentialsConfigured = true, canApprove = true, submitting = false))
        org.junit.Assert.assertFalse(
            ApproveControls.enabled(credentialsConfigured = true, canApprove = true, submitting = true)
        )
    }

    private fun sample(price: Double) = TradeTicket(
        id = "t1",
        ticker = "KXBTC15M-T",
        side = "YES",
        bookSide = "bid",
        stakeUsd = 5.0,
        limitPrice = price,
        yesLimitPrice = price,
        contracts = 10,
        estimatedFillUsd = 5.0,
        maxPayoutUsd = 10.0,
        estimatedAvgFill = price,
        sizingNote = "seed",
        kind = TicketKind.MANUAL,
        clientOrderId = "cid-keep"
    )
}
