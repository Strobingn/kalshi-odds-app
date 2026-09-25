package com.dirk.kalshiodds.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiApiEnvTest {

    @Test
    fun productionAndDemoHostsMatchOfficialDocs() {
        assertEquals("https://external-api.kalshi.com/trade-api/v2/", KalshiApi.TRADE_BASE_URL)
        assertEquals("https://api.elections.kalshi.com/trade-api/v2/", KalshiApi.BASE_URL)
        assertEquals("https://external-api.demo.kalshi.co/trade-api/v2/", KalshiApi.DEMO_TRADE_BASE_URL)
        assertEquals("https://demo-api.kalshi.co/trade-api/v2/", KalshiApi.DEMO_SHARED_BASE_URL)
        assertEquals(KalshiApi.DEMO_TRADE_BASE_URL, KalshiApi.tradePrimary(true))
        assertEquals(KalshiApi.TRADE_BASE_URL, KalshiApi.tradePrimary(false))
        assertEquals(KalshiApi.DEMO_SHARED_BASE_URL, KalshiApi.tradeFallback(true))
        assertTrue(KalshiApi.publicBase(true).contains("demo-api.kalshi.co"))
        assertEquals(KalshiApi.BASE_URL, KalshiApi.publicBase(false))
    }
}
