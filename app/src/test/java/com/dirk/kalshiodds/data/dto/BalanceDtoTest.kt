package com.dirk.kalshiodds.data.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceDtoTest {
    @Test
    fun prefersBalanceDollars() {
        val row = GetBalanceResponse(balance = 12_500, balanceDollars = "125.50")
        assertEquals(125.50, row.cashUsd()!!, 1e-9)
    }

    @Test
    fun fallsBackToCents() {
        val row = GetBalanceResponse(balance = 4_200)
        assertEquals(42.0, row.cashUsd()!!, 1e-9)
    }

    @Test
    fun emptyIsNull() {
        assertNull(GetBalanceResponse().cashUsd())
    }
}
