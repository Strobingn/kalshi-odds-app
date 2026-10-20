package com.dirk.kalshiodds.chart

import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperEquityTest {

    @Test
    fun emptyAndOpenFillsDrawNothing() {
        assertTrue(PaperEquity.curve(emptyList(), 100.0).isEmpty())
        assertTrue(PaperEquity.curve(listOf(fill(1, settled = false, pnl = null)), 100.0).isEmpty())
        assertTrue(PaperEquity.curve(listOf(fill(1, settled = true, pnl = null)), 100.0).isEmpty())
        assertTrue(PaperEquity.curve(listOf(fill(1, settled = true, pnl = Double.NaN)), 100.0).isEmpty())
        assertTrue(PaperEquity.curve(listOf(fill(1, settled = true, pnl = 1.0)), Double.NaN).isEmpty())
    }

    @Test
    fun curveStartsAtBankrollAndAddsSettledPnlInTimeOrder() {
        val fills = listOf(
            fill(30, settled = true, pnl = -2.5),
            fill(10, settled = true, pnl = 4.0),
            fill(20, settled = false, pnl = 99.0),
            fill(20, settled = true, pnl = null)
        )
        val curve = PaperEquity.curve(fills, 100.0)
        assertEquals(3, curve.size)
        assertEquals(9L, curve[0].tMs)
        assertEquals(100.0, curve[0].equityUsd, 1e-9)
        assertEquals(0.0, curve[0].pnlUsd, 1e-9)
        assertEquals(10L, curve[1].tMs)
        assertEquals(104.0, curve[1].equityUsd, 1e-9)
        assertEquals(4.0, curve[1].pnlUsd, 1e-9)
        assertEquals(30L, curve[2].tMs)
        assertEquals(101.5, curve[2].equityUsd, 1e-9)
        assertEquals(1.5, curve[2].pnlUsd, 1e-9)
    }

    private fun fill(tMs: Long, settled: Boolean, pnl: Double?) = PaperFill(
        id = "f-$tMs-$settled",
        ticker = "KXBTC15M-TEST",
        side = "YES",
        stakeUsd = 5.0,
        contracts = 10,
        limitPrice = 0.4,
        source = "AI hunter",
        createdAtMs = tMs,
        settled = settled,
        won = pnl?.let { it > 0.0 },
        pnlUsd = pnl,
        note = "test"
    )
}
