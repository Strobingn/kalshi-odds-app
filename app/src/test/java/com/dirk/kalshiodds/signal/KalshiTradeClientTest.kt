package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.data.api.KalshiTradeApi
import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.data.dto.CancelOrderV2Response
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class KalshiTradeClientTest {

    private val fakePem = "-----BEGIN PRIVATE KEY-----\n${"A".repeat(120)}\n-----END PRIVATE KEY-----"

    @Test
    fun v2CreateUsesBidAskFixedPointAndNeverLegacyPath() = runBlocking {
        val api = RecordingTradeApi(
            create = Response.success(
                201,
                CreateOrderV2Response(
                    orderId = "ord-v2",
                    clientOrderId = "cid-1",
                    fillCount = "0.00",
                    remainingCount = "100.00",
                    tsMs = 1L
                )
            )
        )
        val client = KalshiTradeClient(primary = api, credentials = { "key" to fakePem })
        val placed = client.createLimit(sampleTicket(), "cid-1")
        assertEquals("ord-v2", placed.orderId)
        assertEquals(1, api.creates.size)
        val body = api.creates.single()
        assertEquals("KXBTC15M-26SEP241445-45", body.ticker)
        assertEquals("bid", body.side)
        assertEquals("100.00", body.count)
        assertEquals("0.0100", body.price)
        assertEquals("good_till_canceled", body.timeInForce)
        assertEquals("taker_at_cross", body.selfTradePreventionType)
        assertEquals("cid-1", body.clientOrderId)
        assertFalse(body.reduceOnly)
        assertFalse(body.cancelOrderOnPause)
        assertEquals("good_till_canceled", body.timeInForce)
        assertEquals("taker_at_cross", body.selfTradePreventionType)
        assertEquals(KalshiTradeClient.V2_CREATE_PATH, "/trade-api/v2/portfolio/events/orders")
        assertFalse(KalshiTradeClient.LEGACY_CREATE_PATH.contains("events"))
    }

    @Test
    fun http404DoesNotFallBackToLegacyPortfolioOrders() = runBlocking {
        val primary = RecordingTradeApi(create = error(404, """{"error":{"code":"not_found","message":"nope"}}"""))
        val client = KalshiTradeClient(primary = primary, credentials = { "key" to fakePem })
        try {
            client.createLimit(sampleTicket(), "cid-404")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("not falling back to deprecated v1"))
            assertFalse(e.message!!.contains("HTTP 410"))
        }
        assertEquals(1, primary.creates.size)
    }

    @Test
    fun http410IsReadableAndDoesNotClaimSuccess() = runBlocking {
        val gone = """{"error":{"code":"deprecated_v1_order_endpoint","message":"Please switch to the V2 endpoints","details":"https://docs.kalshi.com/api-reference/orders/create-order"}}"""
        val primary = RecordingTradeApi(create = error(410, gone))
        val client = KalshiTradeClient(primary = primary, credentials = { "key" to fakePem })
        try {
            client.createLimit(sampleTicket(), "cid-410")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            assertTrue(msg, msg.contains("retired the v1 order API") || msg.contains("V2 POST"))
            assertTrue(msg, msg.contains("deprecated_v1_order_endpoint") || msg.contains("switch to the V2"))
            assertFalse(msg.contains("BEGIN"))
        }
    }

    @Test
    fun v2HostFallbackOn404ThenSucceeds() = runBlocking {
        val primary = RecordingTradeApi(create = error(404, """{"error":{"code":"not_found"}}"""))
        val secondary = RecordingTradeApi(
            create = Response.success(
                201,
                CreateOrderV2Response(orderId = "ord-ext", remainingCount = "25.00", fillCount = "0.00")
            )
        )
        val client = KalshiTradeClient(
            primary = primary,
            fallback = secondary,
            credentials = { "key" to fakePem }
        )
        val placed = client.createLimit(sampleTicket(), "cid-fb")
        assertEquals("ord-ext", placed.orderId)
        assertEquals(1, primary.creates.size)
        assertEquals(1, secondary.creates.size)
        assertEquals("bid", secondary.creates.single().side)
    }

    @Test
    fun demoFlagUsesDemoHostNotProduction() = runBlocking {
        val live = RecordingTradeApi(
            create = Response.success(
                201,
                CreateOrderV2Response(orderId = "live-ord", remainingCount = "1.00", fillCount = "0.00")
            )
        )
        val demo = RecordingTradeApi(
            create = Response.success(
                201,
                CreateOrderV2Response(orderId = "demo-ord", remainingCount = "1.00", fillCount = "0.00")
            )
        )
        val client = KalshiTradeClient(
            primary = live,
            demoPrimary = demo,
            credentials = { "demo-key" to fakePem },
            useDemo = { true }
        )
        val placed = client.createLimit(sampleTicket(), "cid-demo")
        assertEquals("demo-ord", placed.orderId)
        assertTrue(live.creates.isEmpty())
        assertEquals(1, demo.creates.size)
        assertEquals("good_till_canceled", demo.creates.single().timeInForce)
        assertEquals("taker_at_cross", demo.creates.single().selfTradePreventionType)
    }

    @Test
    fun http401BodyIsShownVerbatim() = runBlocking {
        val body = """{"error":{"code":"INCORRECT_API_KEY_SIGNATURE","message":"incorrect signature"}}"""
        val primary = RecordingTradeApi(create = error(401, body))
        val client = KalshiTradeClient(primary = primary, credentials = { "key" to "pem" })
        try {
            client.createLimit(sampleTicket(), "cid-401")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            assertTrue(msg, msg.contains("INCORRECT_API_KEY_SIGNATURE"))
            assertTrue(msg, msg.contains("incorrect signature"))
            assertTrue(msg, msg.contains(body) || msg.contains("INCORRECT_API_KEY_SIGNATURE"))
        }
    }

    @Test
    fun testConnectionMissingPemDoesNotHitNetwork() = runBlocking {
        val api = RecordingTradeApi()
        val client = KalshiTradeClient(primary = api, credentials = { "key-only" to "" })
        val result = client.testConnection()
        assertTrue(result is com.dirk.kalshiodds.data.api.ConnectionTestResult.Fail)
        val fail = result as com.dirk.kalshiodds.data.api.ConnectionTestResult.Fail
        assertTrue(fail.reason, fail.reason.contains("Key ID alone") || fail.reason.contains("PEM"))
    }

    @Test
    fun missingKeysNeverPosts() = runBlocking {
        val api = RecordingTradeApi()
        val client = KalshiTradeClient(primary = api, credentials = { "" to "" })
        try {
            client.createLimit(sampleTicket(), "cid")
            fail("expected missing keys")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("API key missing"))
        }
        assertTrue(api.creates.isEmpty())
    }

    private fun sampleTicket() = TradeTicket(
        id = "t1",
        ticker = "KXBTC15M-26SEP241445-45",
        side = "YES",
        bookSide = "bid",
        stakeUsd = 1.0,
        limitPrice = 0.01,
        yesLimitPrice = 0.01,
        contracts = 100,
        estimatedFillUsd = 1.0,
        maxPayoutUsd = 100.0,
        estimatedAvgFill = 0.01,
        sizingNote = "test",
        kind = TicketKind.HUNTER
    )

    private fun error(code: Int, body: String): Response<CreateOrderV2Response> =
        Response.error(code, body.toResponseBody("application/json".toMediaType()))

    private class RecordingTradeApi(
        var create: Response<CreateOrderV2Response> = Response.success(
            CreateOrderV2Response(orderId = "x")
        )
    ) : KalshiTradeApi {
        val creates = mutableListOf<CreateOrderV2Request>()

        override suspend fun createOrderV2(body: CreateOrderV2Request): Response<CreateOrderV2Response> {
            creates += body
            return create
        }

        override suspend fun getBalance() =
            Response.success(com.dirk.kalshiodds.data.dto.GetBalanceResponse(balance = 12_500, balanceDollars = "125.00"))

        override suspend fun getPositions(
            countFilter: String,
            limit: Int,
            cursor: String?
        ) = Response.success(com.dirk.kalshiodds.data.dto.PositionsResponse())

        override suspend fun getOrders(limit: Int, cursor: String?) =
            Response.success(com.dirk.kalshiodds.data.dto.OrdersListResponse())

        override suspend fun cancelOrderV2(
            orderId: String,
            marketTicker: String?,
            exchangeIndex: Int
        ): Response<CancelOrderV2Response> = Response.success(CancelOrderV2Response(orderId = orderId))
    }
}
