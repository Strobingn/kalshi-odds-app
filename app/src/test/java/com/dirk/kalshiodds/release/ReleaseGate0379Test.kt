package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReleaseGate0379Test {
    @org.junit.Before fun on() = ScalpModels.retirementForTest(true)
    @org.junit.After fun off() = ScalpModels.retirementForTest(false)

    @Test fun retiredNewTradeBecomesHiddenShadowKeptOneStaysPrimary() {
        val sh = ScalpModels.toShadowIfRetired(trade("sf30"))
        assertFalse(sh.isPrimary); assertEquals("sf30", sh.variantId); assertNull(ScalpModels.modelOf(sh))
        assertTrue(ScalpModels.toShadowIfRetired(trade("v2-sf01")).isPrimary)
    }

    private fun trade(vid: String) = ScalpTrade(id = "t-$vid", ticker = "KXBTC15M-26OCT162045-45", side = "YES",
        state = ScalpState.PENDING_ENTRY, signalAtMs = 1L, signalAsk = .5, fairAtSignal = .5, ruleVersion = "${ScalpRule.VERSION}|$vid|P")

    @Test fun retiredModelsAreOffCardsAndBoard() {
        for (code in listOf("sf30", "sf32", "sf41", "sf42", "sf43", "sf44", "v2-sf30", "v2-sf32", "v2-sf41x10", "dip", "mkdip")) {
            val m = ScalpModels.modelByCode(code)!!
            assertTrue(code, ScalpModels.isRetired(m))
            assertFalse(code, m in ScalpModels.ACTIVE)
            assertFalse(code, m in ScalpModels.V2_MODELS)
            assertFalse(code, ScalpModels.leaderboardFromStats(emptyList()).any { it.model == m })
        }
    }

    @Test fun smallSampleWinnersStay() {
        for (code in listOf("v2-sf01", "v2-sf08", "v2-sf09", "v2-sf24", "fairh60", "fcride", "sf29", "sf13", "sf03")) {
            val m = ScalpModels.modelByCode(code)!!
            assertFalse(code, ScalpModels.isRetired(m))
            assertTrue(code, m in ScalpModels.ACTIVE)
        }
    }

    @Test fun retiredOriginalStillMapsForOldLedgerRows() {
        assertEquals("sf30", ScalpModels.modelOf(trade("sf30"))?.code) // old rows still map
        assertEquals(ScalpModels.ALL.size, ScalpModels.Model.values().size)
    }

    @Test fun startGuardBlocksDoubleStart() {
        assertFalse(LiveSignalsKeepAlive.shouldStart(true, 0L, 1_000L))
        assertTrue(LiveSignalsKeepAlive.shouldStart(false, 0L, 1_000L))
        assertFalse(LiveSignalsKeepAlive.shouldStart(false, 100_000L, 130_000L))
        assertTrue(LiveSignalsKeepAlive.shouldStart(false, 100_000L, 100_000L + LiveSignalsKeepAlive.START_DEBOUNCE_MS))
        LiveSignalsKeepAlive.resetStartGuardForTest()
        assertTrue(LiveSignalsKeepAlive.claimStart(5_000_000L, isRunning = false))
        assertFalse(LiveSignalsKeepAlive.claimStart(5_010_000L, isRunning = false))
        LiveSignalsKeepAlive.resetStartGuardForTest()
    }

    @Test fun workerRunsOnIoAndNeverWaitsOnService() {
        val src = listOf("app/src/main/java/com/dirk/kalshiodds/worker/MarketRefreshWorker.kt", "src/main/java/com/dirk/kalshiodds/worker/MarketRefreshWorker.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("CoroutineWorker"))
        assertTrue(src.contains("withContext(Dispatchers.IO)"))
        assertTrue(src.contains("withTimeoutOrNull"))
        assertFalse(src.contains("runBlocking"))
        assertFalse(src.contains(".await()"))
    }
}
