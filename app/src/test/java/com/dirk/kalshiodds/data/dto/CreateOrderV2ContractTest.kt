package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the official Create Order V2 request shape
 * https://docs.kalshi.com/api-reference/orders/create-order-v2
 */
class CreateOrderV2ContractTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun v2BodyMatchesOfficialSchemaAndOmitsV1Fields() {
        val req = CreateOrderV2Request(
            ticker = "HIGHNY-24JAN01-T60",
            side = "bid",
            count = "10.00",
            price = "0.5600",
            clientOrderId = "8c35ecb3-328f-4f52-8c7c-0f4b9862f8d1"
        )
        val encoded = json.encodeToString(CreateOrderV2Request.serializer(), req)
        assertTrue(encoded, encoded.contains("\"ticker\":\"HIGHNY-24JAN01-T60\""))
        assertTrue(encoded, encoded.contains("\"side\":\"bid\""))
        assertTrue(encoded, encoded.contains("\"count\":\"10.00\""))
        assertTrue(encoded, encoded.contains("\"price\":\"0.5600\""))
        assertTrue(encoded, encoded.contains("\"time_in_force\":\"good_till_canceled\""))
        assertTrue(encoded, encoded.contains("\"self_trade_prevention_type\":\"taker_at_cross\""))
        assertTrue(encoded, encoded.contains("\"client_order_id\""))
        assertTrue(encoded, encoded.contains("\"reduce_only\":false"))
        assertTrue(encoded, encoded.contains("\"cancel_order_on_pause\":false"))
        assertTrue(encoded, encoded.contains("\"post_only\":false"))
        assertFalse(encoded, encoded.contains("yes_price"))
        assertFalse(encoded, encoded.contains("no_price"))
        assertFalse(encoded, encoded.contains("\"action\""))
        assertFalse(encoded, encoded.contains("buy_max_cost"))
        assertFalse(encoded, encoded.contains("\"type\":"))
        assertEquals(false, req.cancelOrderOnPause)
        assertEquals("good_till_canceled", req.timeInForce)
        assertEquals("taker_at_cross", req.selfTradePreventionType)
    }

    @Test
    fun v2PriceKeepsSubPennyFixedPointDollars() {
        assertEquals("0.0010", com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(0.001))
        assertEquals("0.0150", com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(0.015))
        assertEquals("0.0180", com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(0.018))
        assertEquals("0.2500", com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(0.25))
        assertEquals(null, com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(0.0))
        assertEquals(null, com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(1.0))
        val req = CreateOrderV2Request(
            ticker = "KXBTC15M-26SEP251600-00",
            side = "ask",
            count = "5000.00",
            price = com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(0.001)!!,
            clientOrderId = "subpenny"
        )
        val encoded = json.encodeToString(CreateOrderV2Request.serializer(), req)
        assertTrue(encoded, encoded.contains("\"price\":\"0.0010\""))
        assertFalse(encoded, encoded.contains("\"price\":\"0.0100\""))
    }
}
