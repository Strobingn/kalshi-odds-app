package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.signal.engine.FeedStatus
import com.dirk.kalshiodds.signal.engine.MarketEventDebouncer
import com.dirk.kalshiodds.signal.engine.WsReconnectSchedule
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseGate0349Test {
    private fun src(p: String) = listOf(File("src/main/java/com/dirk/kalshiodds/$p"), File("app/src/main/java/com/dirk/kalshiodds/$p")).first { it.exists() }.readText()

    @Test fun evalFiresOnDeltaDebouncedPerMarket() {
        val scope = TestScope(StandardTestDispatcher())
        val calls = mutableListOf<Set<String>>()
        val d = MarketEventDebouncer(scope, debounceMs = 150, nowNanos = { scope.testScheduler.currentTime * 1_000_000 }) { calls += it }
        // 20 deltas for BTC + 1 for ETH within the window → one eval with both markets
        repeat(20) { d.fire("KXBTC15M-A") }
        d.fire("KXETH15M-A")
        scope.advanceTimeBy(149); scope.runCurrent()
        assertTrue(calls.isEmpty())
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(listOf(setOf("KXBTC15M-A", "KXETH15M-A")), calls)
        // a new delta after eval fires again ~150 ms later — no 5 s floor
        d.fire("KXBTC15M-A")
        scope.advanceTimeBy(151); scope.runCurrent()
        assertEquals(2, calls.size)
        assertTrue(d.lastLatencyMs in 149.0..160.0)
    }

    @Test fun hubFiresEventsOnTickerSnapshotAndDelta() {
        val hub = src("signal/SignalHub.kt")
        assertEquals(3, Regex("marketEvent\\((tick\\.)?ticker\\)").findAll(hub).count())
        val vm = src("ui/OddsViewModel.kt")
        assertTrue(vm.contains("hub.onMarketEvent = { t -> marketEvents.fire(t) }"))
        assertTrue(vm.contains("container.cfFeed.onTick"))
        assertFalse(vm.contains("BACKGROUND_POLL_MS)"))
        assertTrue(src("signal/service/LiveSignalsService.kt").contains("cfFeed.acceptAndNotify"))
    }

    @Test fun reconnectSchedule() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (0..6).map { WsReconnectSchedule.baseMs(it) })
        for (a in 0..12) for (u in listOf(-1.0, 0.0, 1.0)) {
            val v = WsReconnectSchedule.delayMs(a, u)
            assertTrue(v <= WsReconnectSchedule.CAP_MS)
            assertTrue(v >= (WsReconnectSchedule.baseMs(a) * 0.8).toLong())
        }
        assertEquals(10_000L, com.dirk.kalshiodds.signal.ws.KalshiWsClient.MAX_BACKOFF_MS)
        assertEquals(500L, com.dirk.kalshiodds.signal.ws.KalshiWsClient.INITIAL_BACKOFF_MS)
    }

    @Test fun noSixtySecondBackoffAnywhere() {
        val b = com.dirk.kalshiodds.data.api.KalshiRest.bucket
        repeat(12) { assertTrue(b.backoffDelayMs(it, null, 1.0) <= 10_000L) }
        val lim = com.dirk.kalshiodds.data.api.KalshiRateLimiter()
        repeat(12) { assertTrue(lim.backoffDelayMs(it, null, 1.0) <= 10_000L) }
        assertTrue(com.dirk.kalshiodds.signal.paper.AutopilotBackoff.MAX_MS <= 10_000L)
        val ab = com.dirk.kalshiodds.signal.paper.AutopilotBackoff()
        repeat(10) { assertTrue(ab.onError(it.toLong(), "x") <= 10_000L) }
        assertTrue(com.dirk.kalshiodds.prediction.SettlementPollPolicy.MAX_BACKOFF_MS <= 15_000L)
        // source scan: no 60 s / 30 s backoff constants left in main code
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val bad = root.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                val code = l.substringBefore("//")
                if (Regex("(?i)backoff[A-Za-z_]*\\s*(:\\s*Long)?\\s*=\\s*(60_000L|30_000L|60000|10L \\* 60L)").containsMatchIn(code)) "${f.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue("60s/30s backoff left: $bad", bad.isEmpty())
    }

    @Test fun statusLine() {
        val l = FeedStatus.line(true, "0.4s", 162.0, 3_000L, 0)
        assertEquals("WS connected · last tick 0.4s · AI eval 162 ms · REST 3.0s · 429s/1h 0", l)
        assertTrue(src("ui/HomeScreen.kt").contains("state.feedStatus"))
    }
}
