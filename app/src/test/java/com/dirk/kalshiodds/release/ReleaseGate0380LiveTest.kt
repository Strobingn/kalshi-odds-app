package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.api.KalshiTokenBucket
import com.dirk.kalshiodds.signal.engine.FeedStatus
import com.dirk.kalshiodds.signal.engine.LiveQuotePacer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReleaseGate0380LiveTest {
    @Test fun firstQuotePublishesImmediatelyAndBurstsWaitAtMostTheInterval() {
        assertEquals(0L, LiveQuotePacer.waitMs(0L, 5_000L))
        assertEquals(60L, LiveQuotePacer.waitMs(1_000L, 1_040L))
        assertEquals(0L, LiveQuotePacer.waitMs(1_000L, 1_500L))
        for (dt in 0L..500L) assertTrue(LiveQuotePacer.waitMs(1_000L, 1_000L + dt) in 0L..LiveQuotePacer.INTERVAL_MS)
    }

    @Test fun liveVsPollingIndicator() {
        assertTrue(FeedStatus.line(true, "0.1s", null, 3_000L, 0).startsWith("● Live prices"))
        assertTrue(FeedStatus.line(false, "—", null, 1_000L, 0).startsWith("○ Polling prices"))
    }

    @Test fun restBudgetIs95PercentAndRealA429StillDropsIt() {
        assertEquals(0.95, KalshiTokenBucket.TARGET_SHARE, 1e-9)
        assertTrue(KalshiTokenBucket.DROP_SHARE < KalshiTokenBucket.TARGET_SHARE)
    }

    @Test fun homePricesNoLongerRideThePaperLane() {
        val src = listOf("app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt", "src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt").map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("hub.onMarketEvent = { t -> onLiveQuote(t); marketEvents.fire(t) }"))
        val onEvents = src.substringAfter("private fun onMarketEvents(").substringBefore("private fun onLiveQuote")
        assertFalse(onEvents.contains("_state.update { it.copy(snapshot = next) }"))
    }
}
