package com.dirk.kalshiodds.signal.paper

import org.junit.Assert.assertEquals
import org.junit.Test

class ScalpAnalyticsTest {
    @Test fun timeAndPriceChartsExcludeOpenVoidAndLegacyRowsFromRealizedPnl() {
        val settled = fill(id = "settled", price = 0.45, tte = 600, pnl = 2.0, contracts = 4)
        val open = fill(id = "open", price = 0.45, tte = 600, pnl = null, contracts = 4, settled = false)
        val legacy = fill(id = "legacy", price = 0.05, tte = 600, pnl = 8.0, contracts = 4)
        val byPrice = ScalpAnalytics.byEntryPrice(listOf(settled, open, legacy))
        val middle = byPrice.first { it.key == "30-50" }
        assertEquals(1, middle.settled)
        assertEquals(1, middle.open)
        assertEquals(0.5, middle.avgNetPerContractUsd!!, 1e-9)
        assertEquals(0, byPrice.sumOf { it.settled } - 1)

        val byTime = ScalpAnalytics.byTimeRemaining(listOf(settled, open))
        assertEquals(1, byTime.first { it.key == "9-12" }.settled)
    }

    private fun fill(
        id: String,
        price: Double,
        tte: Long,
        pnl: Double?,
        contracts: Int,
        settled: Boolean = true
    ) = PaperFill(
        id = id,
        ticker = "KXBTC15M-TEST",
        side = "YES",
        stakeUsd = price * contracts,
        contracts = contracts,
        limitPrice = price,
        source = "AI autopilot scalp",
        createdAtMs = 1L,
        settled = settled,
        outcome = if (settled) "yes" else null,
        won = if (settled) true else null,
        pnlUsd = pnl,
        strategyTimeToCloseSec = tte,
        strategyEntryEdgePp = 6.0,
        note = "test"
    )
}
