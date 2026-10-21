package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ForecastScalp
import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.LeadForecaster
import com.dirk.kalshiodds.decision.ScalpBreakdown
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseGate0354Test {
    @Test fun sharedBankrollNoPerModelCap() {
        assertEquals(20_000.0, ScalpModels.sliceUsd(20_000.0), 1e-9)
        val src = File("src/main/java/com/dirk/kalshiodds/decision/Scalp.kt").readText()
        assertFalse(src.contains("openCostUsd(model) + cost > ScalpModels.sliceUsd"))
        assertFalse(src.contains("used + cost > ScalpModels.sliceUsd"))
        assertTrue(src.contains("openCost + cost > bankrollUsd()")) // only free-cash limit remains
    }

    @Test fun fc54ConfigsAndDeterminism() {
        for (c in ScalpParams.COINS) {
            val k = LeadForecaster.configFor(c)
            assertTrue(k.horizonS in LeadForecaster.HORIZONS_S)
            val x = LeadForecaster.features(0.5, null, 0.1, 100_050.0, 100_000.0, 2e-5, 600.0) { null }
            assertNotNull(x)
            assertEquals(LeadForecaster.predict(c, k.horizonS, x!!), LeadForecaster.predict(c, k.horizonS, x))
        }
        // full round trip + margin bar
        assertFalse(ForecastScalp.wantsEntry(0.50, 0.01, 0.50, 0.05, 600.0, 0.02))
        assertTrue(ForecastScalp.wantsEntry(0.50, 0.01, 0.50, 0.08, 600.0, 0.02))
    }

    @Test fun breakdownLabelsForecastTradesSeparately() {
        val t = ScalpTrade(id = "a", ticker = "KXBTC15M-26OCT101100-00", side = "YES", state = ScalpState.CLOSED,
            signalAtMs = 1L, signalAsk = 0.5, fairAtSignal = 0.55, contracts = 10, entryPrice = 0.5, entryAtMs = 1L,
            soldContracts = 10, proceedsUsd = 5.5, closedAtMs = 2L, netUsd = 0.4,
            ruleVersion = "x|${ForecastScalp.variantId(true, 600)}|P")
        assertEquals(KeptScalpers.REMOVED_LABEL, t.algoLabel)
        val rows = ScalpBreakdown.byStrategy(listOf(t))
        assertEquals(listOf(KeptScalpers.REMOVED_LABEL), rows.map { it.key })
        assertTrue(ScalpBreakdown.lines(listOf(t)).none { it.startsWith("Fair-gap ·") && !it.contains("no closed") })
    }
}
