package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.data.api.KalshiTradeApi
import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.data.dto.CancelOrderV2Response
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.data.dto.MarketPositionDto
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.PositionParser
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.PositionCopy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class PositionParserTest {

    @Test
    fun yesAndNoFromSignedPositionFp() {
        val yes = PositionParser.parseMarket(
            MarketPositionDto(
                ticker = "KXBTC15M-26SEP241700-00",
                positionFp = "12.00",
                marketExposureDollars = "2.4000",
                realizedPnlDollars = "0.1000"
            )
        )!!
        assertEquals("YES", yes.side)
        assertEquals(12.0, yes.contracts, 1e-9)
        assertEquals(0.20, yes.avgCost!!, 1e-9)
        val no = PositionParser.parseMarket(
            MarketPositionDto(
                ticker = "KXETH15M-26SEP241700-00",
                positionFp = "-8.50",
                marketExposureDollars = "1.7000"
            )
        )!!
        assertEquals("NO", no.side)
        assertEquals(8.50, no.contracts, 1e-9)
        assertEquals(0.20, no.avgCost!!, 1e-9)
        assertEquals(8, PositionParser.heldContracts(no))
    }

    @Test
    fun zeroAndBlankSkipped() {
        assertNull(PositionParser.parseMarket(MarketPositionDto(ticker = "X", positionFp = "0.00")))
        assertNull(PositionParser.parseMarket(MarketPositionDto(ticker = "X", positionFp = null)))
        assertTrue(PositionParser.parseAll(emptyList()).isEmpty())
    }

    @Test
    fun decorateMarksBidAndUnrealizedPnl() {
        val pos = LivePosition(
            ticker = "KXBTC15M-26SEP241700-00",
            side = "YES",
            contracts = 10.0,
            exposureUsd = 2.0,
            avgCost = 0.20
        )
        val market = MarketUiModel(
            ticker = pos.ticker,
            title = "BTC up?",
            subtitle = null,
            floorStrike = null,
            yesBid = 0.35,
            yesAsk = 0.37,
            noBid = 0.63,
            noAsk = 0.65,
            lastPrice = 0.36,
            yesProbabilityPercent = 36.0,
            noProbabilityPercent = 64.0,
            volume = 1.0,
            volume24h = 1.0,
            openInterest = 1.0,
            liquidityDollars = 1.0,
            closeTimeLocal = null,
            closeTimeEpochMs = 2_000_000L + 600_000L,
            status = "active",
            seriesLabel = "Bitcoin"
        )
        val marked = PositionParser.decorate(pos, market, bid = 0.35)
        assertEquals(0.35, marked.bestBid!!, 1e-9)
        assertEquals(1.50, marked.unrealizedPnlUsd!!, 1e-9)
        assertEquals(market.closeTimeEpochMs, marked.closeTimeEpochMs)
        assertEquals("BTC up?", marked.title)
    }
}

class SellTicketTest {

    private val now = 2_000_000L
    private val fakePem = "-----BEGIN PRIVATE KEY-----\n${"A".repeat(120)}\n-----END PRIVATE KEY-----"

    @Test
    fun sellCappedAtHeldAndReduceOnly() {
        val market = openMarket(ask = 0.40, bid = 0.38)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "sell" },
            nowMs = now
        )
        val ticket = TicketBuilder.proposeSell(market, "YES", heldContracts = 10, ctx = ctx, count = 99)!!
        assertEquals(TicketKind.SELL, ticket.kind)
        assertTrue(ticket.reduceOnly)
        assertEquals(10, ticket.contracts)
        assertEquals("ask", ticket.bookSide)
        assertEquals(0.38, ticket.limitPrice, 1e-9)
        assertEquals(0.38, ticket.yesLimitPrice, 1e-9)
        assertTrue(ticket.canApprove)
    }

    @Test
    fun sellNoUsesBidSideAndYesPriceComplement() {
        val market = openMarket(ask = 0.80, bid = 0.78)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "sell-no" },
            nowMs = now
        )
        val ticket = TicketBuilder.proposeSell(market, "NO", heldContracts = 4, ctx = ctx)!!
        assertEquals("NO", ticket.side)
        assertEquals("bid", ticket.bookSide)
        assertEquals(0.20, ticket.limitPrice, 1e-9)
        assertEquals(0.80, ticket.yesLimitPrice, 1e-9)
    }

    @Test
    fun noBidBlocksSell() {
        val market = openMarket(ask = null, bid = null)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "nobid" },
            nowMs = now
        )
        val ticket = TicketBuilder.proposeSell(market, "YES", heldContracts = 5, ctx = ctx)!!
        assertEquals(TicketBuilder.NO_BUYERS, ticket.blockedReason)
        assertFalse(ticket.canApprove)
    }

    @Test
    fun buyOppositeAddsCloseNote() {
        val market = openMarket(ask = 0.40, bid = 0.38)
        val held = LivePosition(
            ticker = market.ticker,
            side = "YES",
            contracts = 7.0,
            exposureUsd = 2.0,
            avgCost = 0.28
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            positions = listOf(held),
            idFactory = { "net" },
            nowMs = now
        )
        val ticket = TicketBuilder.proposeManual(market, "NO", ctx)!!
        assertTrue(ticket.closeNote, ticket.closeNote!!.contains("7"))
        assertTrue(ticket.closeNote!!.contains("UP"))
    }

    @Test
    fun v2SellYesBodyIsIocReduceOnlyAskAtTenthCent() = runBlocking {
        val ticket = TicketBuilder.proposeSell(
            market = openMarket(ask = 0.40, bid = 0.001),
            side = "YES",
            heldContracts = 50,
            ctx = TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                idFactory = { "sell-yes" },
                nowMs = now
            )
        )!!
        assertEquals("ask", ticket.bookSide)
        assertEquals(0.001, ticket.yesLimitPrice, 1e-12)
        assertEquals("0.0010", KalshiPrice.toWireDollars(ticket.yesLimitPrice))
        val api = RecordingTradeApi()
        KalshiTradeClient(primary = api, credentials = { "key" to fakePem })
            .createLimit(ticket, "cid-yes")
        val body = api.creates.single()
        assertEquals("ask", body.side)
        assertEquals("50.00", body.count)
        assertEquals("0.0010", body.price)
        assertEquals(CreateOrderV2Request.TIME_IN_FORCE_IOC, body.timeInForce)
        assertTrue(body.reduceOnly)
        assertFalse(body.timeInForce == CreateOrderV2Request.TIME_IN_FORCE_GTC)
        val json = kotlinx.serialization.json.Json.encodeToString(CreateOrderV2Request.serializer(), body)
        assertTrue(json.contains("\"reduce_only\":true"))
        assertTrue(json.contains("\"time_in_force\":\"immediate_or_cancel\""))
        assertTrue(json.contains("\"side\":\"ask\""))
        assertTrue(json.contains("\"price\":\"0.0010\""))
        assertFalse(json.contains("action"))
        assertFalse(json.contains("yes_price"))
        assertFalse(json.contains("buy_max_cost"))
        assertFalse(json.contains("sell_floor"))
    }

    @Test
    fun v2SellNoBodyIsIocReduceOnlyBidAtYesComplement() = runBlocking {
        val ticket = TicketBuilder.proposeSell(
            market = openMarket(ask = 0.80, bid = 0.78),
            side = "NO",
            heldContracts = 4,
            ctx = TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                idFactory = { "sell-no" },
                nowMs = now
            )
        )!!
        assertEquals("bid", ticket.bookSide)
        assertEquals(0.20, ticket.limitPrice, 1e-12)
        assertEquals(0.80, ticket.yesLimitPrice, 1e-12)
        val api = RecordingTradeApi()
        KalshiTradeClient(primary = api, credentials = { "key" to fakePem })
            .createLimit(ticket, "cid-no")
        val body = api.creates.single()
        assertEquals("bid", body.side)
        assertEquals("4.00", body.count)
        assertEquals("0.8000", body.price)
        assertEquals(CreateOrderV2Request.TIME_IN_FORCE_IOC, body.timeInForce)
        assertTrue(body.reduceOnly)
        val json = kotlinx.serialization.json.Json.encodeToString(CreateOrderV2Request.serializer(), body)
        assertTrue(json.contains("\"reduce_only\":true"))
        assertTrue(json.contains("\"time_in_force\":\"immediate_or_cancel\""))
        assertFalse(json.contains("\"action\""))
    }

    @Test
    fun partialIocFillShowsWhatFilledAndWhatDidNot() = runBlocking {
        val ticket = TicketBuilder.proposeSell(
            market = openMarket(ask = 0.40, bid = 0.38),
            side = "YES",
            heldContracts = 50,
            ctx = TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                idFactory = { "partial" },
                nowMs = now
            )
        )!!
        val api = RecordingTradeApi(
            create = Response.success(
                201,
                CreateOrderV2Response(
                    orderId = "ord-partial",
                    clientOrderId = "cid-partial",
                    fillCount = "20.00",
                    remainingCount = "0.00"
                )
            )
        )
        val placed = KalshiTradeClient(primary = api, credentials = { "key" to fakePem })
            .createLimit(ticket, "cid-partial")
        assertEquals(20.0, placed.fillCount, 1e-9)
        assertEquals(0.0, placed.remainingCount, 1e-9)
        assertFalse(placed.isResting)
        assertEquals(
            "Sold 20 of 50 contracts. 30 didn't fill — no leftover order.",
            placed.fillSummary()
        )
        val none = placed.copy(fillCount = 0.0, remainingCount = 0.0)
        assertEquals(
            "Sold 0 of 50 contracts. Nothing filled — no leftover order.",
            none.fillSummary()
        )
        val full = placed.copy(fillCount = 50.0, remainingCount = 0.0)
        assertEquals("Sold 50 of 50 contracts.", full.fillSummary())
    }

    @Test
    fun emptyBidBlocksSellAndApplySellQuote() {
        val market = openMarket(ask = null, bid = null)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "nobid" },
            nowMs = now
        )
        val ticket = TicketBuilder.proposeSell(market, "YES", heldContracts = 5, ctx = ctx)!!
        assertEquals(TicketBuilder.NO_BUYERS, ticket.blockedReason)
        assertEquals("No buyers right now; this position can't be sold", ticket.blockedReason)
        assertFalse(ticket.canApprove)
        val priced = TicketBuilder.applySellQuote(ticket.copy(blockedReason = null, contracts = 5), 5, bid = 0.0)
        assertEquals(TicketBuilder.NO_BUYERS, priced.blockedReason)
        assertFalse(priced.canApprove)
    }

    @Test
    fun sellUsesFreshBookBidNeverStaleHigherQuote() {
        val market = openMarket(ask = 0.40, bid = 0.002)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            books = mapOf(
                market.ticker to BookLevelSnapshot(yes = listOf(0.001 to 80.0))
            ),
            idFactory = { "fresh" },
            nowMs = now
        )
        val staleMax = TicketBuilder.bestBid(market, "YES", ctx)
        assertEquals(0.002, staleMax!!, 1e-12)
        val fresh = TicketBuilder.freshBestBid(market, "YES", ctx)
        assertEquals(0.001, fresh!!, 1e-12)
        val ticket = TicketBuilder.proposeSell(
            market = market,
            side = "YES",
            heldContracts = 50,
            ctx = ctx,
            limitPrice = 0.002
        )!!
        assertEquals(0.001, ticket.limitPrice, 1e-12)
        assertEquals(0.001, ticket.yesLimitPrice, 1e-12)
        val fee = KalshiFee.total(50, 0.001)
        assertEquals((50 * 0.001 - fee).coerceAtLeast(0.0), ticket.stakeUsd, 1e-9)
    }

    @Test
    fun confirmReusesOneClientOrderIdOnDoubleTap() = runBlocking {
        val ids = mutableListOf<String>()
        val session = TicketSession(
            placeOrder = { ticket, clientOrderId ->
                ids += clientOrderId
                Result.success(
                    PlacedOrder(
                        ticket = ticket,
                        clientOrderId = clientOrderId,
                        orderId = "ord-1",
                        fillCount = 0.0,
                        remainingCount = 0.0,
                        averageFillPrice = ticket.limitPrice,
                        placedAtMs = 1L
                    )
                )
            },
            idFactory = { "coid-once" }
        )
        val ticket = TicketBuilder.proposeSell(
            market = openMarket(ask = 0.40, bid = 0.38),
            side = "YES",
            heldContracts = 10,
            ctx = TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                idFactory = { "s1" },
                nowMs = now
            )
        )!!
        session.addManual(ticket)
        val awaiting = session.snapshot().phase as TicketPhase.AwaitingApprove
        assertEquals("coid-once", awaiting.clientOrderId)
        session.revise("s1") { TicketBuilder.applySellQuote(it, 8, 0.38) }
        val still = session.snapshot().phase as TicketPhase.AwaitingApprove
        assertEquals("coid-once", still.clientOrderId)
        session.approve("s1")
        session.approve("s1")
        assertEquals(listOf("coid-once"), ids)
    }

    @Test
    fun positionRowIsOneReadableLine() {
        val pos = LivePosition(
            ticker = "KXBTC15M-26SEP251530-30",
            side = "YES",
            contracts = 50.0,
            exposureUsd = 16.0,
            avgCost = 0.32,
            bestBid = 0.001,
            unrealizedPnlUsd = -15.95
        )
        assertEquals(
            "BTC · 3:30 PM window · UP · 50 contracts · avg 32¢ · now 0.1¢ · -$15.95",
            PositionCopy.row(pos)
        )
        assertEquals("No buyers right now", PositionCopy.sellBlockedMessage(TicketBuilder.NO_BUYERS))
    }

    @Test
    fun paperSellDoesNotPlaceLiveOrder() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { _, _ ->
                placed.incrementAndGet()
                error("live order must not run")
            }
        )
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 1L })
        val buy = TradeTicket(
            id = "b1",
            ticker = "KXBTC15M-P",
            side = "YES",
            bookSide = "bid",
            stakeUsd = 5.0,
            limitPrice = 0.20,
            yesLimitPrice = 0.20,
            contracts = 25,
            estimatedFillUsd = 5.0,
            maxPayoutUsd = 25.0,
            estimatedAvgFill = 0.20,
            sizingNote = "paper buy",
            kind = TicketKind.MANUAL
        )
        book.manualFill(buy)
        val sell = TicketBuilder.proposeSell(
            market = openMarket(ticker = "KXBTC15M-P", ask = 0.30, bid = 0.28),
            side = "YES",
            heldContracts = 25,
            ctx = TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                idFactory = { "s1" },
                nowMs = now
            ),
            paperOnly = true
        )!!
        assertTrue(sell.paperOnly)
        assertFalse(sell.canApprove)
        assertTrue(sell.canPaper)
        session.addManual(sell)
        session.approve("s1")
        assertEquals(0, placed.get())
        val sold = book.manualFill(sell)
        assertTrue(sold != null)
        assertEquals("sell", sold!!.outcome)
        assertEquals(0, placed.get())
    }

    private fun openMarket(
        ticker: String = "KXBTC15M-26SEP241700-00",
        ask: Double?,
        bid: Double?
    ) = MarketUiModel(
        ticker = ticker,
        title = "BTC",
        subtitle = null,
        floorStrike = null,
        yesBid = bid,
        yesAsk = ask,
        noBid = ask?.let { 1.0 - it },
        noAsk = bid?.let { 1.0 - it },
        lastPrice = ask ?: bid,
        yesProbabilityPercent = ask?.times(100),
        noProbabilityPercent = ask?.let { (1.0 - it) * 100 },
        volume = 2_000.0,
        volume24h = 2_000.0,
        openInterest = 2_000.0,
        liquidityDollars = 2_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = now + 600_000L,
        status = "active",
        seriesLabel = "Bitcoin",
        predictedSide = "YES"
    )

    private class RecordingTradeApi(
        var create: Response<CreateOrderV2Response> = Response.success(
            201,
            CreateOrderV2Response(orderId = "ord-v2", fillCount = "0.00", remainingCount = "0.00")
        )
    ) : KalshiTradeApi {
        val creates = mutableListOf<CreateOrderV2Request>()

        override suspend fun createOrderV2(body: CreateOrderV2Request): Response<CreateOrderV2Response> {
            creates += body
            return create
        }

        override suspend fun getBalance() =
            Response.success(com.dirk.kalshiodds.data.dto.GetBalanceResponse(balance = 12_500, balanceDollars = "125.00"))

        override suspend fun listOrders(ticker: String?, status: String?, limit: Int) =
            Response.success(com.dirk.kalshiodds.data.dto.OrdersListResponse())

        override suspend fun getPositions(
            countFilter: String,
            limit: Int,
            cursor: String?
        ) = Response.success(com.dirk.kalshiodds.data.dto.PositionsResponse())

        override suspend fun cancelOrderV2(
            orderId: String,
            marketTicker: String?,
            exchangeIndex: Int
        ): Response<CancelOrderV2Response> = Response.success(CancelOrderV2Response(orderId = orderId))
    }
}
