package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.api.KalshiEndpointCosts
import com.dirk.kalshiodds.data.api.KalshiPollBudget
import com.dirk.kalshiodds.data.api.KalshiRequestStatus
import com.dirk.kalshiodds.data.api.KalshiRest
import com.dirk.kalshiodds.data.api.KalshiTier
import com.dirk.kalshiodds.data.api.KalshiTokenBucket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.45: budget = 90 % of the real tier, adaptive drop on real Kalshi 429s, fast polling. */
class ReleaseGate0345Test {

    private fun grantedOver(b: KalshiTokenBucket, clock: LongArray, ms: Long, stepMs: Long = 10L): Int {
        var n = 0
        val end = clock[0] + ms
        while (clock[0] < end) { while (b.reserveRead() == 0L) n++; clock[0] += stepMs }
        return n
    }

    @Test
    fun basicBudgetIsNinetyPercentOfTier() {
        val clock = longArrayOf(0L)
        val b = KalshiTokenBucket(nowMs = { clock[0] }, randomUnit = { 0.0 }, tier = KalshiTier.BASIC)
        assertEquals(18.0, b.readsPerSec(), 1e-9)   // 0.9 × 200 tokens/s ÷ 10
        assertEquals(20.0, b.burstReads(), 1e-9)    // min(0.9 × 600, 200) tokens ÷ 10
        var burst = 0
        while (b.reserveRead() == 0L) burst++
        assertEquals(20, burst)
        val sustained = grantedOver(b, clock, 10_000L)
        assertTrue("sustained $sustained", sustained in 175..185) // ≈ 18/s × 10 s
    }

    @Test
    fun budgetFollowsTheRealTierFromAccountLimits() {
        val json = Json.parseToJsonElement(
            """{"usage_tier":"advanced","read":{"refill_rate":300,"bucket_capacity":900},"write":{"refill_rate":300,"bucket_capacity":900}}"""
        ).jsonObject
        val tier = KalshiRest.tierFromLimits(json)
        assertNotNull(tier)
        assertEquals("Advanced", tier!!.name); assertEquals("account/limits", tier.source)
        val b = KalshiTokenBucket(nowMs = { 0L }, tier = KalshiTier.BASIC)
        b.configure(tier)
        assertEquals(27.0, b.readsPerSec(0L), 1e-9) // 90 % of 300 tokens/s
    }

    @Test
    fun realKalshi429DropsToSeventyPercentThenRampsBack() {
        val clock = longArrayOf(0L)
        val b = KalshiTokenBucket(nowMs = { clock[0] }, randomUnit = { 0.0 })
        b.noteRead429(null)
        assertEquals(0.70, b.share(), 1e-9)
        assertEquals(14.0, b.readsPerSec(), 1e-9)
        clock[0] = 59_000L; assertEquals(0.70, b.share(), 1e-9)
        clock[0] = 75_000L; assertEquals(0.80, b.share(), 1e-9) // halfway up the 30 s ramp
        clock[0] = 91_000L; assertEquals(0.90, b.share(), 1e-9)
        assertEquals(1, b.real429s)
        // While dropped, sustained reads ≈ 14/s.
        val c2 = longArrayOf(0L)
        val d = KalshiTokenBucket(nowMs = { c2[0] }, randomUnit = { 0.0 })
        d.noteRead429(null)
        c2[0] = 1_000L
        while (d.reserveRead() == 0L) {}
        val n = grantedOver(d, c2, 10_000L)
        assertTrue("dropped $n", n in 135..145)
    }

    @Test
    fun endpointCostsIncludingCfBenchmarks() {
        assertEquals(10.0, KalshiEndpointCosts.costFor("GET", "/trade-api/v2/markets"), 0.0)
        assertEquals(50.0, KalshiEndpointCosts.costFor("GET", "/trade-api/v2/cfbenchmarks/value"), 0.0)
        val (d, m) = KalshiRest.costsFrom(Json.parseToJsonElement(
            """{"default_cost":10,"endpoint_costs":[{"method":"GET","path":"/portfolio/orders","cost":2}]}""").jsonObject)
        KalshiEndpointCosts.apply(d, m)
        assertEquals(2.0, KalshiEndpointCosts.costFor("GET", "/trade-api/v2/portfolio/orders"), 0.0)
        KalshiEndpointCosts.apply(10.0, emptyMap())
    }

    @Test
    fun fastPollingRestored() {
        assertEquals(1_000L, KalshiPollBudget.HOME_VISIBLE_MS)
        assertEquals(10_000L, KalshiPollBudget.POSITIONS_MS)
        assertEquals(10_000L, com.dirk.kalshiodds.data.api.ResnapshotGate.COOLDOWN_MS)
        val vm = listOf(java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt"), java.io.File("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")).first { it.exists() }.readText()
        assertTrue(vm.contains("const val WS_METADATA_POLL_MS = 3_000L"))
        assertTrue(vm.contains("D3Phase.ACTIVE -> 5_000L"))
        assertTrue(vm.contains("OTHER_DAILY_MS = 5_000L"))
    }

    @Test
    fun localThrottleNeverSaysRateLimited() {
        val req = okhttp3.Request.Builder().url("https://api.elections.kalshi.com/trade-api/v2/markets").build()
        val r = com.dirk.kalshiodds.data.api.KalshiHttpGate.synthetic429(req, 300L)
        val e = retrofit2.HttpException(retrofit2.Response.error<Any>(r.body!!, r))
        val msg = KalshiRequestStatus.message(e, 300L)
        assertFalse(msg.contains("Rate-limited"))
        assertTrue(KalshiRequestStatus.isQuietStatus(msg))
        val h = com.dirk.kalshiodds.data.api.KalshiFeedHealth()
        h.note(e, 300L)
        assertEquals(null, h.banner)
    }

    @Test
    fun countersShowTierBudgetAndUse() {
        val lines = KalshiRest.budgetLines()
        assertTrue(lines[0], lines[0].startsWith("Tier: Basic"))
        assertTrue(lines[1], lines[1].startsWith("Budget: 90% of tier = 18.0 reads/s, burst 20 reads"))
        assertTrue(lines[2], lines[2].startsWith("Used last 60 s:"))
    }
}
