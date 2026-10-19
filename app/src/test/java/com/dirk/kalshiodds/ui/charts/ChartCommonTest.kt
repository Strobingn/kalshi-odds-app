package com.dirk.kalshiodds.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartCommonTest {
    @Test fun moneyUsesRealMinusAndRoundsTinyToZero() {
        assertEquals("+$1.20", chartMoney(1.2))
        assertEquals("\u2212$2.84", chartMoney(-2.84))
        assertEquals("+$0.00", chartMoney(-0.004))
    }

    @Test fun pctRounds() {
        assertEquals("62%", chartPct(0.62))
        assertEquals("0%", chartPct(0.0))
    }

    @Test fun emptyTextIsNoDataYet() = assertEquals("No data yet", CHART_EMPTY_TEXT)
}
