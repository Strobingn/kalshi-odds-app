package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.domain.MarketUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPanelsTest {
    @Test fun missingModelRowsStayHidden() {
        val bare = MarketUiModel(
            ticker = "KXBTC15M-26OCT181300-00", title = "BTC", subtitle = null, floorStrike = null,
            yesBid = null, yesAsk = null, noBid = null, noAsk = null, lastPrice = null,
            yesProbabilityPercent = null, noProbabilityPercent = null, volume = null, volume24h = null,
            closeTimeLocal = null, status = "active", seriesLabel = "Bitcoin"
        )
        assertTrue(SignalPanels.telemetry(bare).isEmpty())
        assertTrue(SignalPanels.drivers(bare).isEmpty())
        assertTrue(SignalPanels.ensemble(bare).isEmpty())
        val live = bare.copy(
            aiConfidence = 0.65,
            pFill = 0.31,
            featureDevs = mapOf("flow" to 2.4, "depth" to -0.8),
            gbmPp = 53.0,
            yesProbabilityPercent = 60.0
        )
        assertEquals(listOf("Confidence", "P(fill)"), SignalPanels.telemetry(live).map { it.label })
        assertEquals(listOf("flow", "depth"), SignalPanels.drivers(live).map { it.label })
        assertEquals(listOf("Mkt mid", "GBM"), SignalPanels.ensemble(live).map { it.label })
    }
}
