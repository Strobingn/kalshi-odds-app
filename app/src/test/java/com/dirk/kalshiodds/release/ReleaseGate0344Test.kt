package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.api.KalshiHttpGate
import com.dirk.kalshiodds.data.api.KalshiPollBudget
import com.dirk.kalshiodds.data.api.KalshiPollLoops
import com.dirk.kalshiodds.data.api.KalshiRateLimiter
import com.dirk.kalshiodds.data.api.KalshiRequestStatus
import com.dirk.kalshiodds.data.api.KalshiTokenBucket
import com.dirk.kalshiodds.data.api.ResnapshotGate
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 0.3.44: Kalshi request volume — global limiter, singleton loops, resnapshot cooldown, local-throttle backoff. */
class ReleaseGate0344Test {
    @Before fun reset() = KalshiPollLoops.resetForTest()

    @Test
    fun globalBudgetIsFourPerSecondBurstEight() {
        assertEquals(4.0, KalshiPollBudget.READ_PER_SEC, 0.0)
        assertEquals(8.0, KalshiPollBudget.READ_BURST, 0.0)
        var now = 0L
        val b = KalshiTokenBucket(nowMs = { now }, randomUnit = { 0.0 })
        var granted = 0
        repeat(20) { if (b.reserveRead() == 0L) granted++ }
        assertEquals(8, granted) // burst
        now = 10_000L // 10 s later → bucket refilled to burst, not 40
        granted = 0
        repeat(60) { if (b.reserveRead() == 0L) granted++ }
        assertEquals(8, granted)
        // Sustained: one read per 250 ms.
        var ok = 0
        for (i in 1..40) { now += 250L; if (b.reserveRead() == 0L) ok++ }
        assertTrue("sustained $ok", ok in 38..41)
    }

    @Test
    fun localThrottleDoesNotEscalateBackoff() {
        var now = 0L
        val lim = KalshiRateLimiter(nowMs = { now }, randomUnit = { 0.0 })
        repeat(10) { lim.onFailure(429, 1_000L, local = true) }
        assertTrue(lim.remainingHoldMs() <= 1_000L)
        assertEquals(10, lim.localThrottles)
        // Real Kalshi 429s still honor Retry-After (floor) and back off.
        val w = lim.onFailure(429, 70_000L)
        assertTrue(w >= 70_000L)
    }

    @Test
    fun syntheticGate429IsMarkedLocal() {
        val req = Request.Builder().url("https://api.elections.kalshi.com/trade-api/v2/markets?series_ticker=KXBTC15M").build()
        val r = KalshiHttpGate.synthetic429(req, 1_200L)
        assertEquals(429, r.code)
        assertEquals("1", r.header(KalshiHttpGate.LOCAL_HEADER))
        val e = retrofit2.HttpException(retrofit2.Response.error<Any>(r.body!!, r))
        assertTrue(KalshiRequestStatus.isLocalThrottle(e))
    }

    @Test
    fun endpointKeysCollapseTickers() {
        fun k(u: String) = KalshiHttpGate.endpointKey(Request.Builder().url(u).build())
        assertEquals("GET markets", k("https://api.elections.kalshi.com/trade-api/v2/markets?series_ticker=KXBTC15M"))
        assertEquals("GET markets/{ticker}/orderbook", k("https://api.elections.kalshi.com/trade-api/v2/markets/KXBTC15M-26OCT091445-45/orderbook"))
        assertEquals("GET portfolio/balance", k("https://api.elections.kalshi.com/trade-api/v2/portfolio/balance"))
    }

    @Test
    fun singletonLoopPerType() {
        assertTrue(KalshiPollLoops.tryRun(KalshiPollLoops.Type.MARKETS, "vm@1", nowMs = 0))
        assertFalse(KalshiPollLoops.tryRun(KalshiPollLoops.Type.MARKETS, "vm@2", nowMs = 1_000))   // second VM
        assertFalse(KalshiPollLoops.tryRun(KalshiPollLoops.Type.MARKETS, "service", nowMs = 2_000)) // service
        assertTrue(KalshiPollLoops.tryRun(KalshiPollLoops.Type.D3, "vm@2", nowMs = 2_000))          // other type ok
        assertTrue(KalshiPollLoops.tryRun(KalshiPollLoops.Type.MARKETS, "vm@1", nowMs = 5_000))   // holder renews
        // Holder dies (stops renewing) → lease expires → another loop takes over.
        assertTrue(KalshiPollLoops.tryRun(KalshiPollLoops.Type.MARKETS, "service", nowMs = 5_000 + KalshiPollLoops.LEASE_MS + 1))
        KalshiPollLoops.release(KalshiPollLoops.Type.MARKETS, "service")
        assertTrue(KalshiPollLoops.tryRun(KalshiPollLoops.Type.MARKETS, "vm@1", nowMs = 40_000))
    }

    @Test
    fun loopsAreGuarded() {
        fun src(p: String) = listOf(File("app/src/main/java/com/dirk/kalshiodds/$p"), File("src/main/java/com/dirk/kalshiodds/$p")).first { it.exists() }.readText()
        val vm = src("ui/OddsViewModel.kt")
        assertTrue(vm.contains("KalshiPollLoops.tryRun(com.dirk.kalshiodds.data.api.KalshiPollLoops.Type.MARKETS, loopOwner)"))
        assertTrue(vm.contains("KalshiPollLoops.Type.D3, loopOwner"))
        assertTrue(vm.contains("KalshiPollLoops.Type.POSITIONS, loopOwner"))
        assertTrue(src("signal/service/LiveSignalsService.kt").contains("KalshiPollLoops.Type.MARKETS, \"service\""))
        assertTrue(src("signal/ws/KalshiWsClient.kt").contains("resnapshotGate.allow(listOf(ticker)"))
    }

    @Test
    fun resnapshotCooldownPerMarket() {
        val g = ResnapshotGate()
        val btc = "KXBTC15M-26OCT091445-45"; val eth = "KXETH15M-26OCT091445-45"
        assertEquals(listOf(btc), g.allow(listOf(btc, btc), 0L))           // dedupe
        assertTrue(g.allow(listOf(btc), 1_000L).isEmpty())                 // gap loop → suppressed
        assertTrue(g.allow(listOf(btc), 29_999L).isEmpty())
        assertEquals(listOf(eth), g.allow(listOf(eth, btc), 10_000L))      // per market
        assertEquals(listOf(btc), g.allow(listOf(btc), 30_000L))           // ≥ 30 s later ok
        // A gap storm of 100 messages in 10 s yields at most one resnapshot per market.
        val storm = ResnapshotGate()
        val total = (0 until 100).sumOf { storm.allow(listOf(btc), it * 100L).size }
        assertEquals(1, total)
        assertEquals(99L, storm.suppressed)
    }
}
