package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.data.dto.MarketPositionDto
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.PositionParser
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun v2SellBodyIsAskReduceOnlyNoActionField() {
        val req = CreateOrderV2Request(
            ticker = "KXBTC15M-26SEP241700-00",
            side = "ask",
            count = "10.00",
            price = "0.3800",
            clientOrderId = "cid",
            reduceOnly = true
        )
        val json = kotlinx.serialization.json.Json.encodeToString(CreateOrderV2Request.serializer(), req)
        assertTrue(json.contains("\"reduce_only\":true"))
        assertTrue(json.contains("\"side\":\"ask\""))
        assertFalse(json.contains("action"))
        assertFalse(json.contains("yes_price"))
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
}
