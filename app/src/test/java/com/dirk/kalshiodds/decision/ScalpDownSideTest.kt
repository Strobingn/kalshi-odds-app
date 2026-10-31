package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.ui.ScalpWindowChartData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.29–1.0.32 (PRs #185, #187, #189, #191) did not drop the DOWN side.
 * A cheap NO ask still enters. A 5¢ UP print leaves DOWN above the 55¢ ceiling, which the chart says out loud.
 */
class ScalpDownSideTest {
    private val ticker = "KXBTC15M-26OCT091015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val now = close - 600_000L

    @Test fun cheapDownSideStillTriggersAndPlots() {
        val p = ScalpParams.CLASSIC.copy(minGap = 0.06)
        // YES bid 70¢ → DOWN ask 30¢. YES ask 71¢ is over the 55¢ ceiling, so only DOWN can enter.
        val down = ScalpRule.Quote(ticker, now, close, now, 0.70, 500.0, 0.71, 500.0, 90_000.0, 100_000.0, 3e-5)
        val (sig, why) = ScalpRule.entrySignal(down, p, listOf("YES", "NO"))
        assertEquals(why, "enter", why)
        assertEquals("NO", sig!!.side)
        assertEquals(0.30, sig.ask, 0.011)
        assertNull(ScalpParamSanity.entryBlock("NO", sig.ask, 10, 0.10, 20_000.0))
        assertNull(ScalpParamSanity.entryBlock("DOWN", sig.ask, 10, 0.10, 20_000.0))
        assertTrue(ScalpParamSanity.entryBlock("NO", 0.05, 10, 0.10, 20_000.0)!!.contains("floor"))

        val up = ScalpRule.Quote(ticker, now, close, now, 0.29, 500.0, 0.30, 500.0, 110_000.0, 100_000.0, 3e-5)
        val (upSig, upWhy) = ScalpRule.entrySignal(up, p, listOf("YES", "NO"))
        assertEquals(upWhy, "enter", upWhy)
        assertEquals("YES", upSig!!.side)

        val trade = ScalpTrade(
            "down", ticker, "NO", ScalpState.PENDING_ENTRY, now, sig.ask, sig.fair,
            ruleVersion = "${ScalpRule.VERSION}|fair|P"
        )
        val chart = ScalpWindowChartData(ticker, close, now, now, trades = listOf(trade))
        assertTrue(chart.markers("NO").any { it.kind == ScalpWindowChartData.Kind.SIGNAL })
        assertEquals(0, chart.markers("YES").size)
        assertNotNull(chart.allMarkers().single { it.side == "NO" })
    }

    @Test fun fiveCentUpPrintExplainsTheEmptyDownLane() {
        val up = ScalpTrade(
            "up", ticker, "YES", ScalpState.PENDING_ENTRY, now, 0.054, 0.20,
            ruleVersion = "${ScalpRule.VERSION}|fair|P"
        )
        val chart = ScalpWindowChartData(
            ticker, close, now, now,
            points = listOf(BidPoint(now, 5.4f, 94f)),
            trades = listOf(up)
        )
        assertTrue(chart.markers("YES").any { it.kind == ScalpWindowChartData.Kind.SIGNAL })
        assertEquals(0, chart.markers("NO").size)
        assertEquals("no DOWN trigger this window (ask above 75¢ ceiling)", chart.quietSideLine())
    }
}
