package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.data.api.KalshiTradeApi
import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.data.dto.CancelOrderV2Response
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.data.dto.OrdersListResponse
import com.dirk.kalshiodds.data.dto.PortfolioOrderDto
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
    fun confirmQuoteIsWhatCreateLimitSends() = runBlocking {
        val api = RecordingTradeApi(
            create = Response.success(
                201,
                CreateOrderV2Response(orderId = "ord-q", fillCount = "0.00", remainingCount = "22.00")
            )
        )
        val client = KalshiTradeClient(primary = api, credentials = { "key" to fakePem })
        val confirmed = com.dirk.kalshiodds.signal.trade.LimitPriceInput.apply(
            sampleTicket().copy(side = "NO", bookSide = "ask", limitPrice = 0.25, yesLimitPrice = 0.75),
            40,
            com.dirk.kalshiodds.signal.config.SignalConstants.DEFAULT_FEE_RATE
        )
        val placed = client.createLimit(confirmed, "cid-quote")
        val body = api.creates.single()
        assertEquals(confirmed.contracts, body.count.toDouble().toInt())
        assertEquals(
            com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(confirmed.yesLimitPrice),
            body.price
        )
        assertEquals(confirmed.contracts, placed.ticket.contracts)
        assertEquals(confirmed.limitPrice, placed.ticket.limitPrice, 1e-9)
        assertEquals(confirmed.stakeUsd, placed.ticket.stakeUsd, 1e-6)
    }

    @Test
    fun adoptYesOrderUsesExchangeSidePriceCountAndTicker() = runBlocking {
        val api = pageApi(
            OrdersListResponse(
                orders = listOf(
                    order(
                        clientOrderId = "cid-yes",
                        ticker = "KXBTC15M-YES",
                        side = "yes",
                        action = "buy",
                        yes = "0.2400",
                        no = "0.7600",
                        count = "12.00"
                    )
                )
            )
        )
        val found = client(api).findByClientOrderId("cid-yes", "KXBTC15M-YES")!!
        assertEquals("YES", found.ticket.side)
        assertEquals("bid", found.ticket.bookSide)
        assertEquals("KXBTC15M-YES", found.ticket.ticker)
        assertEquals(12, found.ticket.contracts)
        assertEquals(0.24, found.ticket.limitPrice, 1e-9)
        assertEquals(12 * 0.24, found.ticket.stakeUsd, 1e-6)
        assertFalse(found.ticket.isSell)
        assertEquals("KXBTC15M-YES", api.queriedTickers.single())
    }

    @Test
    fun adoptNoOrderUsesExchangeSidePriceCountAndTicker() = runBlocking {
        val api = pageApi(
            OrdersListResponse(
                orders = listOf(
                    order(
                        clientOrderId = "cid-no",
                        ticker = "KXBTC15M-NO",
                        side = "no",
                        action = "buy",
                        yes = "0.2400",
                        no = "0.7600",
                        count = "10.00"
                    )
                )
            )
        )
        val found = client(api).findByClientOrderId("cid-no")!!
        assertEquals("NO", found.ticket.side)
        assertEquals("ask", found.ticket.bookSide)
        assertEquals("KXBTC15M-NO", found.ticket.ticker)
        assertEquals(10, found.ticket.contracts)
        assertEquals(0.76, found.ticket.limitPrice, 1e-9)
        assertEquals(7.60, found.ticket.stakeUsd, 1e-6)
        assertFalse(found.ticket.isSell)
    }

    @Test
    fun adoptNoSellUsesSellKindAndBidBook() = runBlocking {
        val api = pageApi(
            OrdersListResponse(
                orders = listOf(
                    order(
                        clientOrderId = "cid-sell",
                        ticker = "KXBTC15M-SELL",
                        side = "no",
                        action = "sell",
                        yes = "0.4000",
                        no = "0.6000",
                        count = "4.00"
                    )
                )
            )
        )
        val found = client(api).findByClientOrderId("cid-sell")!!
        assertEquals("NO", found.ticket.side)
        assertEquals("bid", found.ticket.bookSide)
        assertTrue(found.ticket.isSell)
        assertEquals(4, found.ticket.contracts)
        assertEquals(0.60, found.ticket.limitPrice, 1e-9)
    }

    @Test
    fun orderLookupWalksCursorAndStopsAfterFivePages() = runBlocking {
        val api = object : RecordingTradeApi() {
            var pages = 0
            override suspend fun getOrders(limit: Int, cursor: String?, ticker: String?): Response<OrdersListResponse> {
                pages += 1
                val hit = if (pages == 6) {
                    listOf(order(clientOrderId = "cid-late", ticker = "KXBTC15M-LATE", side = "no", action = "buy", yes = "0.2000", no = "0.8000", count = "3.00"))
                } else {
                    listOf(order(clientOrderId = "other-$pages", ticker = "KXBTC15M-X", side = "yes", action = "buy", yes = "0.2000", no = "0.8000", count = "1.00"))
                }
                return Response.success(OrdersListResponse(orders = hit, cursor = "c$pages"))
            }
        }
        val found = client(api).findByClientOrderId("cid-late")
        assertEquals(null, found)
        assertEquals(com.dirk.kalshiodds.data.api.KalshiTradeClient.MAX_ORDER_LOOKUP_PAGES, api.pages)
    }

    @Test
    fun orderOnSecondPageIsAdopted() = runBlocking {
        val api = object : RecordingTradeApi() {
            override suspend fun getOrders(limit: Int, cursor: String?, ticker: String?): Response<OrdersListResponse> {
                return if (cursor == null) {
                    Response.success(
                        OrdersListResponse(
                            orders = listOf(order(clientOrderId = "nope", ticker = "KXBTC15M-A", side = "yes", action = "buy", yes = "0.1000", no = "0.9000", count = "1.00")),
                            cursor = "next"
                        )
                    )
                } else {
                    Response.success(
                        OrdersListResponse(
                            orders = listOf(order(clientOrderId = "cid-p2", ticker = "KXBTC15M-P2", side = "no", action = "buy", yes = "0.3000", no = "0.7000", count = "8.00"))
                        )
                    )
                }
            }
        }
        val found = client(api).findByClientOrderId("cid-p2")!!
        assertEquals("NO", found.ticket.side)
        assertEquals(8, found.ticket.contracts)
        assertEquals("KXBTC15M-P2", found.ticket.ticker)
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

    private fun client(api: RecordingTradeApi) =
        KalshiTradeClient(primary = api, credentials = { "key" to fakePem })

    private fun pageApi(page: OrdersListResponse) = RecordingTradeApi().also { it.ordersPage = page }

    private fun order(
        clientOrderId: String,
        ticker: String,
        side: String,
        action: String,
        yes: String,
        no: String,
        count: String
    ) = PortfolioOrderDto(
        orderId = "ord-$clientOrderId",
        clientOrderId = clientOrderId,
        ticker = ticker,
        side = side,
        action = action,
        yesPriceDollars = yes,
        noPriceDollars = no,
        initialCountFp = count,
        remainingCountFp = count,
        fillCountFp = "0.00"
    )

    private fun error(code: Int, body: String): Response<CreateOrderV2Response> =
        Response.error(code, body.toResponseBody("application/json".toMediaType()))

    private open class RecordingTradeApi(
        var create: Response<CreateOrderV2Response> = Response.success(
            CreateOrderV2Response(orderId = "x")
        )
    ) : KalshiTradeApi {
        val creates = mutableListOf<CreateOrderV2Request>()
        val queriedTickers = mutableListOf<String?>()
        var ordersPage: OrdersListResponse = OrdersListResponse()

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

        open override suspend fun getOrders(limit: Int, cursor: String?, ticker: String?): Response<OrdersListResponse> {
            queriedTickers += ticker
            return Response.success(ordersPage)
        }

        override suspend fun cancelOrderV2(
            orderId: String,
            marketTicker: String?,
            exchangeIndex: Int
        ): Response<CancelOrderV2Response> = Response.success(CancelOrderV2Response(orderId = orderId))
    }
}
