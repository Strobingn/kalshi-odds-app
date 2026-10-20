package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ChartGalleryTest {
    @Test fun sideGroupsKeepUpAndDownAndDropEmpty() {
        val up = trade("a", "YES", "v|v2-fair|P", 1.5)
        val down = trade("b", "NO", "v|v2-fair|P", -0.4)
        val other = trade("c", "YES", "v|v2-dip|P", 0.2)
        val skip = trade("d", "NO", "v|v2-dip|P", 9.0).copy(state = ScalpState.NO_FILL, entryPrice = null, contracts = 0)
        val groups = ChartGallery.sideGroups(listOf(up, down, other, skip))
        assertEquals(listOf("v2 · Fair-gap", "v2 · Dip-hunter"), groups.map { it.label })
        val fair = groups[0]
        assertEquals(1, fair.upEntries)
        assertEquals(1, fair.downEntries)
        assertEquals(1.5, fair.upNet, 1e-9)
        assertEquals(-0.4, fair.downNet, 1e-9)
        assertEquals(1, groups[1].upEntries)
        assertEquals(0, groups[1].downEntries)
        assertTrue(ChartGallery.sideGroups(emptyList()).isEmpty())
        assertTrue(ChartGallery.sideGroups(listOf(skip)).isEmpty())
    }

    @Test fun winRateHeatIsHourByCoin() {
        val day = ZonedDateTime.of(2026, 10, 18, 15, 30, 0, 0, ChartShapes.ET).toInstant().toEpochMilli()
        val btcWin = trade("a", "YES", "v|v2-fair|P", 1.0).copy(entryAtMs = day, ticker = "KXBTC15M-26OCT181530-30")
        val btcLoss = btcWin.copy(id = "b", side = "NO", netUsd = -0.2)
        val ethLoss = btcWin.copy(id = "c", ticker = "KXETH15M-26OCT181530-30", netUsd = -0.5)
        val open = btcWin.copy(id = "d", state = ScalpState.OPEN, netUsd = null)
        val grid = ChartGallery.winRateByHourCoin(listOf(btcWin, btcLoss, ethLoss, open))!!
        assertEquals(listOf("BTC", "ETH"), grid.rows)
        assertEquals(24, grid.columns.size)
        assertEquals("3p", grid.columns[15])
        assertEquals(0.5, grid.cells[0][15].rate!!, 0.0)
        assertEquals(2, grid.cells[0][15].n)
        assertEquals(0.0, grid.cells[1][15].rate!!, 0.0)
        assertNull(grid.cells[0][0].rate)
        assertEquals(0, grid.cells[0][0].n)
        assertNull(ChartGallery.winRateByHourCoin(emptyList()))
        assertNull(ChartGallery.winRateByHourCoin(listOf(open)))
    }

    private fun trade(id: String, side: String, rule: String, net: Double) = ScalpTrade(
        id = id,
        ticker = "KXBTC15M-26OCT181500-00",
        side = side,
        state = ScalpState.CLOSED,
        signalAtMs = 1_000,
        signalAsk = 0.40,
        fairAtSignal = 0.5,
        contracts = 10,
        entryPrice = 0.40,
        entryAtMs = 2_000,
        closedAtMs = 3_000,
        netUsd = net,
        ruleVersion = rule
    )
}
