package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.api.KalshiTradeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOrderGatesTest {

    @Test
    fun catalogListsEveryLiveGateWithDefaultAndReason() {
        val ids = LiveOrderGates.catalog.map { it.id }.toSet()
        for (need in listOf(
            "tickets_enabled",
            "live_approve_tap",
            "credentials",
            "pem_only_key_id",
            "paper_isolation",
            "client_order_id",
            "live_all_in_cap",
            "min_profit_if_win",
            "approve_router_live",
            "close_cutoff"
        )) {
            assertTrue(need, need in ids)
        }
        assertEquals("$10 including fees", LiveOrderGates.defaultOf("live_all_in_cap"))
        assertEquals("removed", LiveOrderGates.defaultOf("min_profit_if_win"))
        assertEquals(10.0, LiveOrderGates.LIVE_ALL_IN, 1e-9)
        assertEquals(0.0, LiveOrderGates.MIN_PROFIT, 1e-9)
        LiveOrderGates.catalog.forEach {
            assertTrue(it.id, it.reason.isNotBlank())
            assertTrue(it.id, it.default.isNotBlank())
        }
    }

    @Test
    fun productionHostAndV2PathMatchDocs() {
        assertTrue(KalshiApi.TRADE_BASE_URL.startsWith("https://external-api.kalshi.com/trade-api/v2"))
        assertEquals("/trade-api/v2/portfolio/events/orders", KalshiTradeClient.V2_CREATE_PATH)
        assertTrue(KalshiTradeClient.verbatimHttp(400, """{"error":{"code":"invalid_order"}}""")
            .contains("invalid_order"))
    }
}
