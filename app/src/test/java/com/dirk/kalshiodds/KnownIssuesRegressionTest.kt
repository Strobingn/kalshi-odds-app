package com.dirk.kalshiodds

import com.dirk.kalshiodds.data.api.KalshiTradeApi
import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.data.dto.CancelOrderV2Response
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.domain.ConsistentQuote
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.feedback.ForecastUnits
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.paper.PaperApprove
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.ApproveRouter
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.LiveOrderGates
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeModeLabel
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.domain.ActiveMarketResolver
import com.dirk.kalshiodds.domain.FakeClock
import com.dirk.kalshiodds.signal.market.MarketRollover
import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import com.dirk.kalshiodds.signal.ws.WsSubscriptionSwitch
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.ui.DisagreementLabel
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.HomeMarkets
import com.dirk.kalshiodds.ui.HomeScorecardSummary
import com.dirk.kalshiodds.ui.HomeSnapshotMerge
import com.dirk.kalshiodds.ui.SignalCopy
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.WindowLabel
import com.dirk.kalshiodds.ui.theme.DarkPalette
import com.dirk.kalshiodds.ui.theme.LightPalette
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * One named test per known 0.3.10–0.3.13 issue (#13 = home scorecard summary), on the real production
 * classes. No mocks of the logic under test.
 */
class KnownIssuesRegressionTest {

    private val nowMs = 1_700_000_000_000L
    private val fakePem = "-----BEGIN PRIVATE KEY-----\n${"A".repeat(120)}\n-----END PRIVATE KEY-----"

    @Test
    fun scorecardHitAndBrierScoreThePickedSideIncludingZeroOfFiveFade() {
        val fade = (0 until 5).map { i ->
            log(
                ticker = "KXETH15M-$i",
                predictedYes = 0.945,
                outcome = "yes",
                predictedSide = "NO",
                score = 1,
                brier = 0.003
            )
        }
        assertEquals(0, fade.count { ForecastUnits.hit(it) })
        val side = fade.map { ForecastUnits.sideBrier(it) }.average()
        val yes = fade.map { ForecastUnits.brier(it) }.average()
        val pSide = 1.0 - 0.945
        assertEquals((pSide - 1.0) * (pSide - 1.0), side, 1e-9)
        assertTrue("side-Brier ~0.893, not the P(YES) 0.003", abs(side - 0.893) < 0.002)
        assertFalse(abs(side - 0.003) < 0.001)
        assertEquals((0.945 - 1.0) * (0.945 - 1.0), yes, 1e-9)
        val card = ScorecardMetrics.window(fade)
        assertEquals(0, card.hits)
        assertEquals(5, card.total)
        assertEquals(side, card.brier!!, 1e-9)
        assertEquals(yes, card.pUpBrier!!, 1e-9)
        assertEquals(
            "Picked side: 0/5 correct (0%) · need 20+ results",
            HomeCopy.scorecardLine(card.hits, card.total, card.brier)
        )
        assertFalse(HomeCopy.scorecardLine(card.hits, card.total, card.brier).contains("0.003"))
    }

    @Test
    fun paperFillOnTickerNeverBlocksLiveApproveAndLivePositionNeverBlocksPaper() = runBlocking {
        val ticker = "KXETH15M-26SEP251230-30"
        val market = sample(ticker, yesAsk = 0.25, noAsk = 0.75, aiYes = 80.0, predicted = "YES")
        val livePos = LivePosition(
            ticker = ticker,
            side = "YES",
            contracts = 12.0,
            exposureUsd = 6.0,
            avgCost = 0.50
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(),
            alertsPaused = false,
            positions = listOf(livePos),
            nowMs = nowMs
        )

        val book = PaperBook()
        book.reset()
        val paperOnX = book.explicitFill(
            ticker = ticker,
            side = "NO",
            limitPrice = 0.15,
            wantContracts = 15,
            source = "paper",
            note = "open paper fill"
        )
        assertTrue(paperOnX.ok)
        assertTrue(
            book.snapshot().lastMessage!!.contains("Already have an open paper fill") ||
                book.snapshot().fills.any { !it.settled && it.ticker == ticker }
        )
        val secondPaper = book.explicitFill(
            ticker = ticker,
            side = "YES",
            limitPrice = 0.25,
            wantContracts = 5,
            source = "paper",
            note = "duplicate paper"
        )
        assertFalse("paper duplicate-order check is paper-only", secondPaper.ok)
        assertTrue(secondPaper.message.contains("open paper fill"))

        val http = AtomicInteger(0)
        val liveSession = TicketSession(
            placeOrder = { ticket, clientOrderId ->
                http.incrementAndGet()
                Result.success(
                    PlacedOrder(
                        ticket = ticket,
                        clientOrderId = clientOrderId,
                        orderId = "live-1",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = ticket.limitPrice,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val liveTicket = TicketBuilder.proposeManual(market, "YES", ctx)!!
        liveSession.addManual(liveTicket)
        val liveDecision = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = liveTicket.canApprove,
            intent = ApproveRouter.Intent.Live
        )
        assertEquals(ApproveRouter.Decision.Live, liveDecision)
        liveSession.approve(liveTicket.id)
        assertEquals("open paper fill must not swallow Live Approve HTTP", 1, http.get())

        val isolatedBook = PaperBook()
        isolatedBook.reset()
        val paperWhileLiveOpen = isolatedBook.explicitFill(
            ticker = ticker,
            side = "YES",
            limitPrice = 0.25,
            wantContracts = 10,
            source = "paper",
            note = "paper while live position open"
        )
        assertTrue(
            "live Kalshi position on $ticker must not trip the paper duplicate check: ${paperWhileLiveOpen.message}",
            paperWhileLiveOpen.ok
        )
        val paperSession = TicketSession(
            placeOrder = { _, _ -> error("paper path must not call the live client") }
        )
        val paperTicket = TicketBuilder.proposeManual(market, "YES", ctx)!!
        paperSession.addManual(paperTicket)
        val paperDecision = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = true,
            intent = ApproveRouter.Intent.Paper
        )
        assertEquals(ApproveRouter.Decision.Paper, paperDecision)
        val freshBook = PaperBook()
        freshBook.reset()
        val paperOut = PaperApprove.apply(paperSession, freshBook, paperTicket.id)
        assertTrue(
            "live Kalshi position on $ticker must not block PaperApprove: ${paperOut.message}",
            paperOut.ok
        )
        assertEquals(1, http.get())
    }

    @Test
    fun upDownPricesComeFromOneConsistentSnapshotAndInconsistentQuotesAreNotTradable() {
        val mixed = MarketQuoteView.of(0.55, 0.63, 0.37, 0.50)
        assertEquals(0.55, mixed.yesBid!!, 1e-12)
        assertEquals(0.63, mixed.yesAsk!!, 1e-12)
        assertEquals(0.37, mixed.noBid!!, 1e-12)
        assertEquals(0.45, mixed.noAsk!!, 1e-12)
        assertTrue(mixed.yesBid!! + mixed.noAsk!! <= 1.0 + ConsistentQuote.COMPLEMENT_EPS)
        assertTrue(mixed.yesAsk!! + mixed.noBid!! >= 1.0 - ConsistentQuote.COMPLEMENT_EPS)
        assertFalse(mixed.downHeader.contains("ask 50¢"))

        val fade = ConsistentQuote.fromSameUpdate(0.55, null, null, 0.50)
        assertNotNull(fade)
        assertEquals(0.55, fade!!.yesBid!!, 1e-12)
        assertEquals(0.45, fade.noAsk!!, 1e-12)
        assertFalse(fade.complementBroken())

        val rawBroken = ConsistentQuote.Snap(0.55, 0.63, 0.37, 0.50)
        assertTrue("mixed 55/63 vs 37/50 is an inconsistent snapshot", rawBroken.complementBroken())

        val crossed = sample(
            ticker = "KXETH15M-CROSSED",
            yesAsk = 0.60,
            noAsk = 0.40,
            aiYes = 80.0,
            predicted = "YES"
        ).copy(yesBid = 0.70, noBid = 0.50)
        val crossedView = MarketQuoteView.of(crossed)
        assertFalse(
            "repaired display must not keep a crossed/inconsistent book",
            ConsistentQuote.Snap(
                crossedView.yesBid,
                crossedView.yesAsk,
                crossedView.noBid,
                crossedView.noAsk
            ).complementBroken()
        )
        val decision = BetCall.decide(crossed, SignalSettings(), nowMs)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertFalse(decision.isActionable)
    }

    @Test
    fun everyPaperLiveLabelEqualsApproveRouterIncludingHomeButtonAndConfirmSheet() {
        for (paperOn in listOf(true, false)) {
            for (keySaved in listOf(true, false)) {
                for (paperOnly in listOf(true, false)) {
                    val settings = SignalSettings(
                        paperTradingEnabled = paperOn,
                        apiKeyId = if (keySaved) "key-id" else "",
                        hasPrivateKey = keySaved
                    )
                    val ticket = TradeTicket(
                        id = "t",
                        ticker = "KXETH15M-X",
                        side = "YES",
                        bookSide = "bid",
                        stakeUsd = 5.0,
                        limitPrice = 0.25,
                        yesLimitPrice = 0.25,
                        contracts = 19,
                        estimatedFillUsd = 5.0,
                        maxPayoutUsd = 19.0,
                        estimatedAvgFill = 0.25,
                        sizingNote = "test",
                        paperOnly = paperOnly
                    )
                    val decision = ApproveRouter.decide(
                        paperTradingEnabled = paperOn,
                        paperOnly = paperOnly,
                        isSell = false,
                        liveCredentialsConfigured = keySaved,
                        canApprove = true,
                        intent = ApproveRouter.Intent.Live
                    )
                    val mode = TradeModeLabel.forApprove(settings, ticket)
                    assertEquals(
                        "router vs label paperOn=$paperOn key=$keySaved paperOnly=$paperOnly",
                        TradeModeLabel.of(decision),
                        mode
                    )
                    val call = BetCall.Decision(
                        headline = BetCall.Headline.BET_UP,
                        side = "YES",
                        ticket = ticket,
                        ask = 0.25,
                        profitIfWinUsd = 14.0,
                        allInUsd = 5.0,
                        contracts = 19,
                        noBetReason = null
                    )
                    val primary = HomeCopy.primaryButtonLabel(mode, call)
                    assertTrue(primary.startsWith(mode))
                    assertTrue(primary.contains("UP"))
                    val confirm = HomeCopy.confirmApproveLabel(mode, ticket.stakeUsd, isSell = false)
                    when (mode) {
                        TradeModeLabel.LIVE -> assertEquals("LIVE $5.00", confirm)
                        else -> assertEquals(mode, confirm)
                    }
                }
            }
        }
    }

    @Test
    fun liveV2BodyNeverExceedsFiveDollarAllInIncludingLeftoverWinTargetAndNoFiftyTargetCopy() =
        runBlocking {
            val leftover = TradeTicket(
                id = "fat",
                ticker = "KXETH15M-26SEP251230-30",
                side = "YES",
                bookSide = "bid",
                stakeUsd = 50.0,
                limitPrice = 0.25,
                yesLimitPrice = 0.25,
                contracts = 200,
                estimatedFillUsd = 50.0,
                maxPayoutUsd = 200.0,
                estimatedAvgFill = 0.25,
                winTargetUsd = 50.0,
                sizingNote = "leftover $50 win-target"
            )
            val leftoverApi = RecordingTradeApi()
            val leftoverClient = KalshiTradeClient(
                primary = leftoverApi,
                credentials = { "key" to fakePem }
            )
            leftoverClient.createLimit(leftover, "cid-fat")
            val fatBody = leftoverApi.creates.single()
            assertTrue(v2AllIn(fatBody) <= 5.0 + 1e-9)
            assertTrue(fatBody.count.toDouble() <= 19.0 + 1e-9)

            for (cents in 1..99) {
                val ask = cents / 100.0
                val api = RecordingTradeApi()
                val client = KalshiTradeClient(primary = api, credentials = { "key" to fakePem })
                val ticket = leftover.copy(
                    id = "c$cents",
                    limitPrice = ask,
                    yesLimitPrice = ask,
                    estimatedAvgFill = ask
                )
                client.createLimit(ticket, "cid-$cents")
                val body = api.creates.single()
                val allIn = v2AllIn(body)
                assertTrue("all-in $allIn at ${cents}c body=$body", allIn <= 5.0 + 1e-9)
            }

            val liveNote = TicketBuilder.proposeManual(
                sample("KXETH15M-COPY", 0.25, 0.75, 80.0, "YES"),
                "YES",
                TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = nowMs)
            )!!.winTargetNote.orEmpty()
            assertFalse(liveNote.contains("$50"))
            assertFalse(liveNote.contains("win target", ignoreCase = true))
            assertTrue(liveNote.contains("$5 all-in"))

            val liveCopy = listOf(
                HomeCopy.allInProfit(
                    BetCall.Decision(
                        headline = BetCall.Headline.BET_UP,
                        side = "YES",
                        ticket = leftover.copy(stakeUsd = 5.0, contracts = 19, winTargetUsd = null),
                        ask = 0.25,
                        profitIfWinUsd = 14.0,
                        allInUsd = 5.0,
                        contracts = 19,
                        noBetReason = null
                    )
                ).orEmpty(),
                HomeCopy.primaryButtonLabel(TradeModeLabel.LIVE, BetCall.Decision(
                    headline = BetCall.Headline.BET_UP,
                    side = "YES",
                    ticket = null,
                    ask = 0.25,
                    profitIfWinUsd = 14.0,
                    allInUsd = 5.0,
                    contracts = 19,
                    noBetReason = null
                )),
                HomeCopy.confirmApproveLabel(TradeModeLabel.LIVE, 5.0, isSell = false)
            )
            liveCopy.forEach { line ->
                assertFalse(line, line.contains("$50"))
                assertFalse(line, line.contains("win target", ignoreCase = true))
            }
            assertTrue(LiveOrderGates.defaultOf("drawdown_pause").contains("drawdown"))
            assertTrue(LiveOrderGates.catalog.first { it.id == "drawdown_pause" }.reason.contains("drawdown"))

            val sources = listOf(
                "src/main/res/values/strings.xml",
                "src/main/java/com/dirk/kalshiodds/ui/HomeCopy.kt",
                "src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt",
                "src/main/java/com/dirk/kalshiodds/ui/components/TradeTicketCard.kt",
                "src/main/java/com/dirk/kalshiodds/ui/components/MarketCard.kt",
                "src/main/java/com/dirk/kalshiodds/ui/components/HomeChrome.kt"
            ).map { File(it) }.filter { it.isFile }
            assertTrue("live-path sources must be readable from the test cwd", sources.isNotEmpty())
            for (file in sources) {
                val text = file.readText()
                assertFalse("${file.name} still says \$50 win-target", text.contains("\$50 win-target"))
                assertFalse("${file.name} still labels Win target on live", text.contains("\"Win target\""))
            }
        }

    @Test
    fun cheapHunterWithNoModelEdgeIsNoBetHeadline() {
        val market = sample("KXETH15M-HUNTER", yesAsk = 0.03, noAsk = 0.97, aiYes = 4.0, predicted = "YES")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = nowMs)
        val hunter = TicketBuilder.proposeHunter(market, ctx)
        assertNotNull(hunter)
        assertEquals(TicketKind.HUNTER, hunter!!.kind)
        assertFalse(hunter.modelEdge)
        assertFalse(BetCall.qualifies(hunter, market, ctx))
        val decision = BetCall.decide(market, ctx)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertFalse(decision.isActionable)
    }

    @Test
    fun disagreementLabelNamesBothSidesUnderABetHeadlineAndIsNullOnAgreement() {
        val disagree = sample(
            ticker = "KXETH15M-DISAGREE",
            yesAsk = 0.80,
            noAsk = 0.20,
            aiYes = 20.0,
            predicted = "NO"
        ).copy(
            tapeConflict = true,
            modelLeanSide = "NO",
            primaryHeroSide = "YES",
            predictedSide = "NO"
        )
        val copy = DisagreementLabel.of(disagree)
        assertEquals(DisagreementLabel.TITLE, copy!!.title)
        assertEquals("Model disagrees with market", copy.title)
        assertTrue(copy.detail.contains("DOWN"))
        assertTrue(copy.detail.contains("UP"))
        assertTrue(copy.detail.contains("Market + spot"))
        val decision = BetCall.decide(disagree, SignalSettings(), nowMs)
        assertEquals(BetCall.Headline.BET_DOWN, decision.headline)
        assertTrue(decision.isActionable)
        assertNotNull(DisagreementLabel.of(disagree))

        assertNull(
            DisagreementLabel.of(
                tapeConflict = false,
                modelLeanSide = "YES",
                primaryHeroSide = "YES",
                modelYesPercent = 80.0
            )
        )
        assertNull(DisagreementLabel.of(tapeConflict = true))
    }

    @Test
    fun liveSellUsesIocReduceOnlyAtBestBid() = runBlocking {
        val ticker = "KXBTC15M-26SEP251530-30"
        val market = sample(ticker, yesAsk = 0.40, noAsk = 0.60, aiYes = 50.0, predicted = "YES")
            .copy(yesBid = 0.002, noBid = 0.598)
        val bookCtx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            books = mapOf(
                ticker to com.dirk.kalshiodds.signal.engine.BookLevelSnapshot(
                    yes = listOf(0.001 to 50.0)
                )
            ),
            nowMs = nowMs
        )
        val yes = TicketBuilder.proposeSell(market, "YES", heldContracts = 50, ctx = bookCtx)!!
        assertEquals(0.001, yes.limitPrice, 1e-12)
        assertEquals("ask", yes.bookSide)
        assertTrue(yes.reduceOnly)
        assertEquals(TicketBuilder.SELL_IOC_NOTE, yes.gateNote)

        val yesApi = RecordingTradeApi()
        KalshiTradeClient(primary = yesApi, credentials = { "key" to fakePem })
            .createLimit(yes, "cid-yes-8")
        val yesBody = yesApi.creates.single()
        assertEquals("ask", yesBody.side)
        assertEquals("50.00", yesBody.count)
        assertEquals("0.0010", yesBody.price)
        assertEquals(CreateOrderV2Request.TIME_IN_FORCE_IOC, yesBody.timeInForce)
        assertTrue(yesBody.reduceOnly)
        assertFalse(yesBody.timeInForce == CreateOrderV2Request.TIME_IN_FORCE_GTC)

        val no = TicketBuilder.proposeSell(
            sample(ticker, yesAsk = 0.80, noAsk = 0.20, aiYes = 20.0, predicted = "NO")
                .copy(yesBid = 0.78, noBid = 0.001),
            "NO",
            heldContracts = 10,
            ctx = TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                nowMs = nowMs
            )
        )!!
        assertEquals("bid", no.bookSide)
        assertEquals(0.001, no.limitPrice, 1e-12)
        assertEquals(0.999, no.yesLimitPrice, 1e-12)
        val noApi = RecordingTradeApi()
        KalshiTradeClient(primary = noApi, credentials = { "key" to fakePem })
            .createLimit(no, "cid-no-8")
        val noBody = noApi.creates.single()
        assertEquals("bid", noBody.side)
        assertEquals("0.9990", noBody.price)
        assertEquals(CreateOrderV2Request.TIME_IN_FORCE_IOC, noBody.timeInForce)
        assertTrue(noBody.reduceOnly)

        val empty = TicketBuilder.proposeSell(
            sample(ticker, yesAsk = 0.40, noAsk = 0.60, aiYes = 50.0, predicted = "YES")
                .copy(yesBid = null, yesAsk = null, noBid = null, noAsk = null, lastPrice = null),
            "YES",
            heldContracts = 50,
            ctx = TicketBuilder.Context(
                settings = SignalSettings(ticketsEnabled = true),
                alertsPaused = false,
                nowMs = nowMs
            )
        )!!
        assertEquals(TicketBuilder.NO_BUYERS, empty.blockedReason)
        assertFalse(empty.canApprove)

        val partial = PlacedOrder(
            ticket = yes,
            clientOrderId = "cid-partial-8",
            orderId = "ord-8",
            fillCount = 20.0,
            remainingCount = 0.0,
            averageFillPrice = 0.001,
            placedAtMs = 1L
        )
        assertEquals(
            "Sold 20 of 50 contracts. 30 didn't fill — no leftover order.",
            partial.fillSummary()
        )
        assertFalse(partial.isResting)
    }

    @Test
    fun fifteenMinuteWindowRollsOverThreeTimesWithoutRestart() = runBlocking {
        val t0 = 1_700_000_000_000L
        val clock = FakeClock(t0 + 60_000L)
        val closes = LongArray(4) { i -> t0 + (i + 1) * 900_000L }
        val keys = listOf("26SEP251200", "26SEP251215", "26SEP251230", "26SEP251245")
        val series = listOf("KXBTC15M" to "Bitcoin", "KXETH15M" to "Ethereum", "KXSOL15M" to "Solana")
        val windows = keys.mapIndexed { i, key ->
            series.map { (ser, label) ->
                sample("$ser-$key-45", 0.25, 0.75, 80.0, "YES").copy(
                    closeTimeEpochMs = closes[i],
                    status = "active",
                    seriesLabel = label
                )
            }
        }
        var listed: List<MarketUiModel> = windows[0]
        var lateEmpty = false
        val listCalls = AtomicInteger(0)
        val scored = mutableListOf<Set<String>>()
        val ws = RecordingWs()
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { ticket, clientOrderId ->
                placed.incrementAndGet()
                Result.success(
                    PlacedOrder(
                        ticket = ticket,
                        clientOrderId = clientOrderId,
                        orderId = "should-not-place",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = ticket.limitPrice,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val rollover = MarketRollover(
            clock = clock,
            listOpen = { seriesTicker ->
                listCalls.incrementAndGet()
                if (lateEmpty) emptyList()
                else listed.filter {
                    com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(it.ticker) == seriesTicker
                }
            }
        )
        rollover.addListener { event ->
            if (event.droppedTickers.isNotEmpty()) scored += event.droppedTickers
            session.voidTickers(event.droppedTickers)
            ws.apply(event)
        }

        var event = rollover.refreshFromRest()
        assertEquals(windows[0].map { it.ticker }.toSet(), event.activeTickers)
        assertEquals(closes[0] + ActiveMarketResolver.GRACE_AFTER_CLOSE_MS, event.nextWakeMs)
        assertEquals(windows[0].map { it.ticker }.toSet(), ws.tickers.toSet())
        assertTrue(ws.commands.any { it.cmd == "subscribe" })
        assertTrue(ws.commands.none { it.cmd == "unsubscribe" })
        assertWindowUi(windows[0], clock.nowMs())

        val firstTicket = TicketBuilder.proposeManual(
            windows[0].first { it.ticker.startsWith("KXBTC15M") },
            "YES",
            TicketBuilder.Context(settings = SignalSettings(ticketsEnabled = true), alertsPaused = false, nowMs = clock.nowMs())
        )!!
        session.addManual(firstTicket)
        assertTrue(firstTicket.canApprove)
        assertTrue(session.snapshot().phase is com.dirk.kalshiodds.signal.trade.TicketPhase.AwaitingApprove)

        for (step in 1..3) {
            val openTicket = TicketBuilder.proposeManual(
                windows[step - 1].first { it.ticker.startsWith("KXETH15M") },
                "YES",
                TicketBuilder.Context(
                    settings = SignalSettings(ticketsEnabled = true),
                    alertsPaused = false,
                    nowMs = clock.nowMs()
                )
            )!!
            session.addManual(openTicket)
            assertTrue(openTicket.canApprove)
            clock.set(closes[step - 1] + 4_000L)
            val dropped = windows[step - 1].map { it.ticker }.toSet()
            val added = windows[step].map { it.ticker }.toSet()
            if (step == 1) {
                lateEmpty = true
                val miss = rollover.refreshFromRest()
                assertTrue(miss.retrying.containsAll(listOf("KXBTC15M", "KXETH15M", "KXSOL15M")))
                assertEquals(clock.nowMs() + ActiveMarketResolver.RETRY_MS, miss.nextWakeMs)
                assertTrue(miss.activeTickers.isEmpty())
                assertEquals(dropped, miss.droppedTickers)
                lateEmpty = false
                clock.advance(ActiveMarketResolver.RETRY_MS)
            }
            listed = windows[step]
            event = rollover.refreshFromRest()
            assertEquals(added, event.activeTickers)
            assertEquals(added, event.addedTickers)
            if (step == 1) {
                assertTrue(event.droppedTickers.isEmpty())
            } else {
                assertEquals(dropped, event.droppedTickers)
            }
            assertEquals(added, ws.tickers.toSet())
            assertTrue("old tickers must be unsubscribed: $dropped vs ${ws.unsubscribed}", ws.unsubscribed.any { it == dropped })
            assertTrue("new tickers must be subscribed: $added vs ${ws.subscribed}", ws.subscribed.any { it == added })
            val lastSub = ws.commands.last { it.cmd == "subscribe" }
            assertEquals(added.toList().sorted(), lastSub.marketTickers.sorted())
            assertWindowUi(windows[step], clock.nowMs())
            assertTrue(scored.any { it == dropped })
            val voided = session.snapshot().proposals.filter { it.ticker in dropped }
            assertTrue(voided.isNotEmpty())
            voided.forEach { ticket ->
                assertEquals(TicketSession.WINDOW_CLOSED, ticket.blockedReason)
                assertFalse(ticket.canApprove)
            }
            session.approve(firstTicket.id)
            session.approve(openTicket.id)
            assertEquals(0, placed.get())
            assertEquals(TicketSession.WINDOW_CLOSED, session.snapshot().lastError)
        }

        assertTrue("lifecycle close should re-resolve", rollover.onLifecycle(windows[2][0].ticker, "deactivated"))
        assertTrue(listCalls.get() >= 4)
        assertEquals(3, scored.size)
    }

    @Test
    fun websocketReconnectMidWindowKeepsCurrentTicker() = runBlocking {
        val t0 = 1_700_000_000_000L
        val clock = FakeClock(t0 + 60_000L)
        val close = t0 + 900_000L
        val listed = listOf("KXBTC15M", "KXETH15M", "KXSOL15M").map { ser ->
            sample("$ser-26SEP251200-45", 0.25, 0.75, 80.0, "YES").copy(
                closeTimeEpochMs = close,
                status = "active"
            )
        }
        val ws = RecordingWs()
        val rollover = MarketRollover(
            clock = clock,
            listOpen = { series ->
                listed.filter { com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(it.ticker) == series }
            }
        )
        rollover.addListener { ws.apply(it) }
        val live = rollover.refreshFromRest()
        assertEquals(listed.map { it.ticker }.toSet(), live.activeTickers)
        val before = ws.commands.toList()
        val replay = rollover.onReconnect()
        assertTrue(replay.reconnect)
        assertEquals(live.activeTickers, replay.activeTickers)
        assertTrue(replay.droppedTickers.isEmpty())
        assertTrue(replay.addedTickers.isEmpty())
        val reconnectCmds = ws.commands.drop(before.size)
        assertEquals(1, reconnectCmds.size)
        assertEquals("subscribe", reconnectCmds.single().cmd)
        assertEquals(listed.map { it.ticker }.toSet(), reconnectCmds.single().marketTickers.toSet())
        assertEquals(listed.map { it.ticker }.toSet(), ws.tickers.toSet())
        assertTrue(reconnectCmds.none { it.cmd == "unsubscribe" })
        val switch = WsSubscriptionSwitch.resubscribeOnReconnect(
            9,
            LiveSignalsPolicy.subscriptionPlan(true, ws.tickers).channels,
            ws.tickers
        )
        assertEquals("subscribe", switch.cmd)
        assertEquals(ws.tickers.toSet(), switch.marketTickers.toSet())
    }

    @Test
    fun signalSideNeverContradictsModelEdge() {
        val a1 = HomeFixtures.sampleAlerts()[0]
        val card = SignalCopy.card(a1)
        assertEquals("NO BET", card.call)
        assertEquals(BetCall.Headline.NO_BET, SignalCopy.headline(card.call))
        assertEquals("Model 58% vs market 44% · edge +14 pts", card.modelLine)
        assertTrue(card.details!!.contains(SignalStance.DISAGREE_NOTE))
        assertFalse(SignalCopy.shouldNotify(a1))
        for (p in listOf(LightPalette, DarkPalette)) {
            assertEquals(p.textSecondary, SideColor.of(SignalCopy.headline(card.call), p))
        }
        assertTrue(SignalCopy.displayedEdgePts(58.0, 44.0, "NO")!! < 0.0)

        val down = SignalCopy.card(
            ticker = "KXETH15M-26SEP251400-40",
            side = "NO",
            modelYes = 30.0,
            marketYes = 48.0,
            fairYes = 30.0
        )
        assertEquals("DOWN", down.call)
        assertEquals("Model 70% vs market 52% · edge +18 pts", down.modelLine)
        assertTrue(SignalCopy.displayedEdgePts(30.0, 48.0, "NO")!! > 0.0)

        val up = SignalCopy.card(HomeFixtures.sampleAlerts()[1])
        assertEquals("UP", up.call)
        assertEquals("Model 68% vs market 64% · edge +4 pts", up.modelLine)

        val noBet = log(
            ticker = "KXBTC15M-nobet",
            predictedYes = 0.58,
            outcome = "yes",
            predictedSide = SignalStance.NO_BET
        )
        val scored = log(
            ticker = "KXBTC15M-up",
            predictedYes = 0.68,
            outcome = "yes",
            predictedSide = "YES"
        )
        assertFalse(ForecastUnits.isScoredPick(noBet))
        assertFalse(ForecastUnits.hit(noBet))
        val window = ScorecardMetrics.window(listOf(noBet, scored))
        assertEquals(1, window.total)
        assertEquals(1, window.hits)
    }

    @Test
    fun homeShowsFixedBtcSolEthCardsAcrossRollover() {
        val t0 = 1_700_000_000_000L
        val clock = FakeClock(t0 + 60_000L)
        val windowMs = com.dirk.kalshiodds.domain.MarketLifecycle.WINDOW_MS
        val closes = LongArray(6) { i -> t0 + (i + 1) * windowMs }
        val series = listOf(
            "KXBTC15M" to "Bitcoin",
            "KXSOL15M" to "Solana",
            "KXETH15M" to "Ethereum"
        )
        fun ticker(ser: String, step: Int) = "$ser-26SEP25${1200 + step * 15}-45"
        fun coin(ser: String, label: String, step: Int, edge: Double): MarketUiModel =
            sample(ticker(ser, step), 0.50, 0.50, 50.0 + edge, "YES").copy(
                closeTimeEpochMs = closes[step],
                openTimeEpochMs = closes[step] - windowMs,
                status = "active",
                seriesLabel = label,
                edgePp = edge,
                importedModelPp = 50.0 + edge,
                aiYesPercent = 50.0 + edge
            )

        fun assertOrder(cards: List<HomeMarkets.CoinCard>, btc: String?, sol: String?, eth: String?) {
            assertEquals(listOf("KXBTC15M", "KXSOL15M", "KXETH15M"), cards.map { it.series })
            assertEquals(btc, cards[0].market?.ticker)
            assertEquals(sol, cards[1].market?.ticker)
            assertEquals(eth, cards[2].market?.ticker)
        }

        // (a) closed-but-still-active BTC + next BTC window
        val closedBtc = coin("KXBTC15M", "Bitcoin", 0, 12.0)
        val nextBtc = coin("KXBTC15M", "Bitcoin", 1, 4.0)
        val sol0 = coin("KXSOL15M", "Solana", 0, 2.0).copy(
            closeTimeEpochMs = closes[1],
            openTimeEpochMs = closes[0]
        )
        val eth0 = coin("KXETH15M", "Ethereum", 0, 1.0).copy(
            closeTimeEpochMs = closes[1],
            openTimeEpochMs = closes[0]
        )
        clock.set(closes[0] + 2_000L)
        var listed = listOf(closedBtc, nextBtc, sol0, eth0)
        assertOrder(
            HomeMarkets.coinCards(listed, clock.nowMs()),
            ticker("KXBTC15M", 1),
            ticker("KXSOL15M", 0),
            ticker("KXETH15M", 0)
        )

        // (b) future ETH window listed early must not replace the current ETH card
        val futureEth = coin("KXETH15M", "Ethereum", 2, 40.0)
        listed = listed + futureEth
        assertOrder(
            HomeMarkets.coinCards(listed, clock.nowMs()),
            ticker("KXBTC15M", 1),
            ticker("KXSOL15M", 0),
            ticker("KXETH15M", 0)
        )

        // (c) SOL next listing missing for 10 s — slot stays, loading
        listed = listOf(nextBtc, eth0.copy(closeTimeEpochMs = closes[1], openTimeEpochMs = closes[0]), futureEth)
        clock.set(closes[0] + 6_000L)
        val missingSol = HomeMarkets.coinCards(listed, clock.nowMs())
        assertOrder(missingSol, ticker("KXBTC15M", 1), null, ticker("KXETH15M", 0))
        assertTrue(missingSol[1].loading)
        clock.advance(10_000L)
        listed = listed + coin("KXSOL15M", "Solana", 1, 8.0)
        assertOrder(
            HomeMarkets.coinCards(listed, clock.nowMs()),
            ticker("KXBTC15M", 1),
            ticker("KXSOL15M", 1),
            ticker("KXETH15M", 0)
        )

        // (d) edges flip across 3 rollovers; cards never reorder
        val settings = SignalSettings(ticketsEnabled = true)
        val edges = listOf(Triple(2.0, 25.0, 1.0), Triple(1.0, 2.0, 40.0), Triple(30.0, 3.0, 4.0))
        for (step in 1..3) {
            clock.set(closes[step - 1] + 4_000L)
            val (btcE, solE, ethE) = edges[step - 1]
            listed = series.mapIndexed { i, (ser, label) ->
                coin(ser, label, step, listOf(btcE, solE, ethE)[i])
            } + series.map { (ser, label) -> coin(ser, label, step - 1, 50.0) } +
                coin("KXBTC15M", "Bitcoin", step + 1, 99.0)
            val cards = HomeMarkets.coinCards(listed, clock.nowMs())
            assertOrder(
                cards,
                ticker("KXBTC15M", step),
                ticker("KXSOL15M", step),
                ticker("KXETH15M", step)
            )
            val live = cards.mapNotNull { it.market }
            val ctx = TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = clock.nowMs())
            val decisions = HomeMarkets.decisions(live, ctx)
            val best = HomeMarkets.best(HomeMarkets.ranked(live, decisions, settings), decisions)!!.first.ticker
            val expectedBest = when (step) {
                1 -> ticker("KXSOL15M", step)
                2 -> ticker("KXETH15M", step)
                else -> ticker("KXBTC15M", step)
            }
            assertEquals(expectedBest, best)
        }
    }

    @Test
    fun homeHasNoSignalList() {
        assertFalse(HomeCopy.SHOWS_SIGNAL_LIST)
        assertEquals("Signal history", HomeCopy.SIGNAL_HISTORY)
        assertTrue(HomeCopy.signalCardsOnHome(HomeFixtures.sampleAlerts()).isEmpty())
        assertTrue(HomeFixtures.sampleAlerts().isNotEmpty())
        val homeSrc = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt")
        ).first { it.isFile }
        val home = homeSrc.readText()
        assertFalse(home.contains("SignalSummaryCard"))
        assertFalse(home.contains("title = \"Signals\""))
        assertFalse(home.contains("recentAlerts"))
        assertFalse(home.contains("signalCount"))
        assertTrue(home.contains("SIGNAL_HISTORY"))
        assertTrue(home.contains("onOpenSignalHistory"))
        assertFalse(home.contains("title = \"Tickets\""))
        assertFalse(home.contains("title = \"Positions\""))
        assertFalse(home.contains("title = \"Paper\""))
        val historySrc = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/SignalHistoryScreen.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/SignalHistoryScreen.kt")
        ).first { it.isFile }
        val history = historySrc.readText()
        assertTrue(history.contains("SignalSummaryCard"))
        assertTrue(history.contains("HomeCopy.SIGNAL_HISTORY"))
        assertFalse(SignalCopy.shouldNotify(HomeFixtures.sampleAlerts()[0]))
    }

    @Test
    fun homeShowsScorecardSummary() {
        val wins = (0 until 12).map { i ->
            log(
                ticker = "KXBTC15M-$i",
                predictedYes = 0.70,
                outcome = "yes",
                predictedSide = "YES",
                score = 1
            )
        }
        val losses = (0 until 6).map { i ->
            log(
                ticker = "KXETH15M-$i",
                predictedYes = 0.70,
                outcome = "no",
                predictedSide = "YES",
                score = 0
            )
        }
        val noBet = log(
            ticker = "KXSOL15M-nb",
            predictedYes = 0.55,
            outcome = "yes",
            predictedSide = "NO_BET",
            score = null
        )
        val unsettled = log(
            ticker = "KXBTC15M-open",
            predictedYes = 0.70,
            outcome = "void",
            predictedSide = "YES",
            score = null
        ).copy(outcome = null)
        val summary = HomeScorecardSummary.of(wins + losses + noBet + unsettled, 12.40)
        assertEquals(12, summary.wins)
        assertEquals(6, summary.losses)
        assertEquals(18, summary.settledCount)
        assertEquals("12-6 · 67% · paper +$12.40", summary.line())
        assertEquals(
            "12-6 · 67% · paper +$12.40",
            HomeCopy.scorecardSummaryLine(wins + losses + noBet + unsettled, 12.40)
        )
        assertEquals(HomeScorecardSummary.NO_SETTLED, HomeScorecardSummary.of(emptyList(), 99.0).line())
        assertEquals(HomeScorecardSummary.NO_SETTLED, HomeScorecardSummary.of(listOf(noBet), 1.0).line())

        val homeSrc = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt")
        ).first { it.isFile }
        val home = homeSrc.readText()
        val thisWindow = home.indexOf("ThisWindowCard")
        val coins = home.indexOf("items(coinCards")
        assertTrue(thisWindow >= 0 && coins > thisWindow)
        assertTrue(home.contains("scorecard = state.scorecardSummary"))
        assertTrue(home.contains("onOpenScorecard"))
        assertTrue(home.contains("Icons.Default.Assessment"))
        assertFalse(home.contains("Color.Green"))
        assertFalse(home.contains("Color.Red"))
        assertFalse(home.contains("SignalSummaryCard"))

        val chromeSrc = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/components/HomeChrome.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/components/HomeChrome.kt")
        ).first { it.isFile }
        val chrome = chromeSrc.readText()
        val cardFn = chrome.indexOf("fun ThisWindowCard")
        val lineFn = chrome.indexOf("fun HomeScorecardLine")
        assertTrue(cardFn >= 0 && lineFn > cardFn)
        assertTrue(chrome.substring(cardFn, lineFn).contains("HomeScorecardLine"))
        val snippet = chrome.substring(lineFn, (lineFn + 800).coerceAtMost(chrome.length))
        assertTrue(snippet.contains("textSecondary"))
        assertTrue(snippet.contains("KeyboardArrowRight"))
        assertFalse(snippet.contains("Color.Green"))
        assertFalse(snippet.contains("Color.Red"))
        assertFalse(snippet.contains("accentGreen"))
        assertFalse(snippet.contains("accentRed"))
        assertTrue(snippet.contains("onOpenScorecard"))

        val activitySrc = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/MainActivity.kt"),
            File("src/main/java/com/dirk/kalshiodds/MainActivity.kt")
        ).first { it.isFile }
        val activity = activitySrc.readText()
        assertTrue(activity.contains("navigator.open(AppRoutes.SCORECARD)"))
        assertTrue(activity.contains("ScorecardScreen"))
        assertTrue(activity.contains("DipApp"))
    }

    @Test
    fun backNavigatesToPreviousScreen() {
        val nav = com.dirk.kalshiodds.ui.AppNavigator()
        assertTrue(nav.isHome())
        assertFalse(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.HOME, nav.current)

        nav.open(com.dirk.kalshiodds.ui.AppRoutes.SCORECARD)
        nav.open(com.dirk.kalshiodds.ui.AppRoutes.SETTINGS)
        nav.open(com.dirk.kalshiodds.ui.AppRoutes.DATA)
        nav.open(com.dirk.kalshiodds.ui.AppRoutes.HISTORY)
        nav.open(com.dirk.kalshiodds.ui.AppRoutes.CHART)
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.CHART, nav.current)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.HISTORY, nav.current)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.DATA, nav.current)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.SETTINGS, nav.current)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.SCORECARD, nav.current)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.HOME, nav.current)
        assertFalse(nav.back())

        nav.open(com.dirk.kalshiodds.ui.AppRoutes.SIGNAL_HISTORY)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.HOME, nav.current)

        val dip = File("app/src/main/java/com/dirk/kalshiodds/ui/DipApp.kt").takeIf { it.isFile }
            ?: File("src/main/java/com/dirk/kalshiodds/ui/DipApp.kt")
        val dipSrc = dip.readText()
        assertTrue(dipSrc.contains("BackHandler"))
        assertTrue(dipSrc.contains("onCancelSheet"))
        assertTrue(dipSrc.contains("sheetOpen"))
        assertTrue(dipSrc.contains("BackHandler(enabled = sheetOpen)"))
        assertTrue(dipSrc.contains("BackHandler(enabled = navigator.canPop && !sheetOpen)"))

        val tickets = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/components/TradeTicketCard.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/components/TradeTicketCard.kt")
        ).first { it.isFile }.readText()
        assertTrue(tickets.contains("BackHandler(enabled = true) { onCancelApprove() }"))
        assertTrue(tickets.contains("onDismiss = onCancelApprove"))

        val manifest = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()
        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"true\""))
        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        val activityBlock = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        assertTrue(activityBlock.contains("android:enableOnBackInvokedCallback=\"true\""))

        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile && it.readText().contains("targetSdk") }.readText()
        assertTrue(gradle.contains("targetSdk = 35"))

        val placed = AtomicInteger(0)
        val session = TicketSession(placeOrder = { _, _ ->
            placed.incrementAndGet()
            error("Back must not place")
        })
        val ticket = TicketBuilder.proposeManual(
            sample("KXBTC15M-BACK", 0.25, 0.75, 80.0, "YES"),
            "YES",
            TicketBuilder.Context(settings = SignalSettings(ticketsEnabled = true), alertsPaused = false, nowMs = nowMs)
        )!!
        session.addManual(ticket)
        session.openApprove(ticket.id)
        assertTrue(session.snapshot().phase is com.dirk.kalshiodds.signal.trade.TicketPhase.AwaitingApprove)
        session.cancelApprove()
        assertFalse(session.snapshot().phase is com.dirk.kalshiodds.signal.trade.TicketPhase.AwaitingApprove)
        assertEquals(0, placed.get())

        val sellPlaced = AtomicInteger(0)
        val sellSession = TicketSession(placeOrder = { _, _ ->
            sellPlaced.incrementAndGet()
            error("Back must not place sell")
        })
        val sell = HomeFixtures.sellTicketWithBid()
        assertTrue(sell.isSell)
        sellSession.addManual(sell)
        assertTrue(sellSession.snapshot().phase is com.dirk.kalshiodds.signal.trade.TicketPhase.AwaitingApprove)
        sellSession.cancelApprove()
        assertFalse(sellSession.snapshot().phase is com.dirk.kalshiodds.signal.trade.TicketPhase.AwaitingApprove)
        assertEquals(0, sellPlaced.get())
    }

    @Test
    fun rolloverSwitchesTickerWithoutRestart() = runBlocking {
        val t0 = 1_700_000_000_000L
        val close0 = t0 + 900_000L
        val close1 = close0 + 900_000L
        val clock = FakeClock(t0 + 60_000L)
        val old = listOf("KXBTC15M", "KXSOL15M", "KXETH15M").map { ser ->
            sample("$ser-26SEP251745-45", 0.25, 0.75, 80.0, "YES").copy(
                closeTimeEpochMs = close0,
                openTimeEpochMs = close0 - 900_000L,
                status = "active"
            )
        }
        val next = listOf("KXBTC15M", "KXSOL15M", "KXETH15M").map { ser ->
            sample("$ser-26SEP251800-00", 0.25, 0.75, 80.0, "YES").copy(
                closeTimeEpochMs = close1,
                openTimeEpochMs = close0,
                status = "active"
            )
        }
        var listed = old
        var throw429 = false
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val rollover = com.dirk.kalshiodds.signal.market.MarketRollover(
            clock = clock,
            listOpen = { series ->
                calls.incrementAndGet()
                if (throw429) {
                    val body = "rate limited".toResponseBody("text/plain".toMediaType())
                    throw retrofit2.HttpException(retrofit2.Response.error<Any>(429, body))
                }
                listed.filter { com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(it.ticker) == series }
            },
            sleeper = { }
        )
        val first = rollover.refreshFromRest()
        assertEquals(old.map { it.ticker }.toSet(), first.activeTickers)
        val snap0 = com.dirk.kalshiodds.data.repo.MarketsSnapshot(
            btc = listOf(old[0]),
            sol = listOf(old[1]),
            eth = listOf(old[2]),
            fetchedAtEpochMs = clock.nowMs(),
            fromCache = false
        )
        assertEquals(old[0].ticker, HomeMarkets.coinCards(snap0.allMarkets, clock.nowMs())[0].market?.ticker)

        clock.set(close0 + 1_000L)
        val staleOpen = rollover.refreshFromRest()
        assertTrue(staleOpen.retrying.containsAll(listOf("KXBTC15M", "KXSOL15M", "KXETH15M")))
        assertTrue(staleOpen.activeTickers.isEmpty())
        val loading = paintHome(snap0, staleOpen, clock.nowMs())
        val cards = HomeMarkets.coinCards(loading.allMarkets, clock.nowMs())
        assertEquals(listOf("KXBTC15M", "KXSOL15M", "KXETH15M"), cards.map { it.series })
        assertTrue(cards.all { it.market == null })

        clock.advance(2_500L)
        val stillStale = rollover.refreshFromRest()
        assertTrue(stillStale.retrying.isNotEmpty())
        clock.advance(5_000L)
        val stillLoading = paintHome(loading, stillStale, clock.nowMs())
        assertTrue(HomeMarkets.coinCards(stillLoading.allMarkets, clock.nowMs()).all { it.market == null })

        throw429 = true
        clock.advance(5_000L)
        val limited = rollover.refreshFromRest()
        assertTrue(limited.rateLimited.isNotEmpty())
        assertTrue(limited.retrying.isNotEmpty())
        val after429 = paintHome(stillLoading, limited, clock.nowMs())
        assertTrue(HomeMarkets.coinCards(after429.allMarkets, clock.nowMs()).all { it.market == null })
        throw429 = false

        clock.set(close0 + 42_000L)
        listed = next
        val swapped = rollover.refreshFromRest()
        assertEquals(next.map { it.ticker }.toSet(), swapped.activeTickers)
        val painted = paintHome(loading, swapped, clock.nowMs())
        val after = HomeMarkets.coinCards(painted.allMarkets, clock.nowMs())
        assertEquals(next[0].ticker, after[0].market?.ticker)
        assertEquals(next[1].ticker, after[1].market?.ticker)
        assertEquals(next[2].ticker, after[2].market?.ticker)
        assertEquals(listOf("KXBTC15M", "KXSOL15M", "KXETH15M"), after.map { it.series })

        val resume = rollover.refreshFromRest()
        assertEquals(swapped.activeTickers, resume.activeTickers)
        val replay = rollover.onReconnect()
        assertTrue(replay.reconnect)
        assertEquals(swapped.activeTickers, replay.activeTickers)
        val afterResume = paintHome(painted, resume, clock.nowMs())
        assertEquals(next[0].ticker, HomeMarkets.coinCards(afterResume.allMarkets, clock.nowMs())[0].market?.ticker)

        val vm = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
        ).first { it.isFile }.readText()
        assertTrue(vm.contains("bindRollover"))
        assertTrue(vm.contains("container.rollover.start(viewModelScope)"))
        assertTrue(vm.contains("applyRolloverEvent"))
        assertTrue(vm.contains("fun onForeground"))
        assertTrue(vm.contains("container.rollover.refreshFromRest()"))
        assertTrue(vm.contains("container.rollover.onReconnect()"))
        assertTrue(vm.contains("val event = container.rollover.applyListed"))
        assertTrue(vm.contains("HomeSnapshotMerge.apply"))
        val activity = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/MainActivity.kt"),
            File("src/main/java/com/dirk/kalshiodds/MainActivity.kt")
        ).first { it.isFile }.readText()
        assertTrue(activity.contains("oddsViewModel.onForeground()"))
        assertTrue(calls.get() >= 9)
    }

    @Test
    fun homeKeeps0312Layout() {
        assertEquals(
            listOf("Scorecard", "Settings", "Refresh"),
            HomeCopy.TOP_BAR_ACTIONS
        )
        val home = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt")
        ).first { it.isFile }.readText()
        assertTrue(home.contains("Icons.Default.Assessment"))
        assertTrue(home.contains("Icons.Default.Settings"))
        assertTrue(home.contains("Icons.Default.Refresh"))
        assertFalse(home.contains("Icons.Default.History"))
        assertFalse(home.contains("Icons.Default.Folder"))
        assertTrue(home.contains("scorecard = state.scorecardSummary"))
        assertTrue(home.contains("SIGNAL_HISTORY"))
        assertTrue(home.contains("onOpenSignalHistory"))
        assertFalse(HomeCopy.SHOWS_SIGNAL_LIST)

        val nav = com.dirk.kalshiodds.ui.AppNavigator()
        nav.open(com.dirk.kalshiodds.ui.AppRoutes.SCORECARD)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.HOME, nav.current)
        nav.open(com.dirk.kalshiodds.ui.AppRoutes.SIGNAL_HISTORY)
        assertTrue(nav.back())
        assertEquals(com.dirk.kalshiodds.ui.AppRoutes.HOME, nav.current)

        val up = HomeFixtures.actionableBtc()
        assertEquals("AI 80%", HomeCopy.tileAiUp(up))
        assertEquals("AI 20%", HomeCopy.tileAiDown(up))
    }

    @Test
    fun aiPercentInTilesMatchesModel() {
        val up = HomeFixtures.actionableBtc()
        val upTiles = HomeCopy.tileAiPercents(up)
        assertEquals("AI 80%", upTiles.up)
        assertEquals("AI 20%", upTiles.down)
        assertEquals(100, upTiles.upPct!! + upTiles.downPct!!)
        val modelUp = SignalStance.homeModelYes(up.importedModelPp, up.aiYesPercent)!!
        assertEquals(modelUp.roundToInt(), upTiles.upPct)

        val down = HomeFixtures.actionableDownBtc()
        val downTiles = HomeCopy.tileAiPercents(down)
        assertEquals("AI 10%", downTiles.up)
        assertEquals("AI 90%", downTiles.down)
        assertEquals(100, downTiles.upPct!! + downTiles.downPct!!)

        val noBet = HomeFixtures.noBetEth()
        val noBetTiles = HomeCopy.tileAiPercents(noBet)
        assertEquals("AI 70%", noBetTiles.up)
        assertEquals("AI 30%", noBetTiles.down)
        val mixed = HomeFixtures.disagreementBtc()
        val mixedTiles = HomeCopy.tileAiPercents(mixed)
        val mixedYes = SignalStance.homeModelYes(mixed.importedModelPp, mixed.aiYesPercent)!!
        assertEquals("AI ${mixedYes.roundToInt()}%", mixedTiles.up)
        assertEquals(100, mixedTiles.upPct!! + mixedTiles.downPct!!)

        val none = up.copy(importedModelPp = null, aiYesPercent = null)
        assertEquals(HomeCopy.AI_EM_DASH, HomeCopy.tileAiUp(none))
        assertEquals(HomeCopy.AI_EM_DASH, HomeCopy.tileAiDown(none))

        val card = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/components/MarketCard.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/components/MarketCard.kt")
        ).first { it.isFile }.readText()
        assertTrue(card.contains("HomeCopy.tileAiUp(market)"))
        assertTrue(card.contains("HomeCopy.tileAiDown(market)"))
        assertTrue(card.contains("titleSmall"))
        val loading = card.substringAfter("fun NextWindowLoadingCard").substringBefore("fun MarketCard")
        assertFalse(loading.contains("tileAi"))
        assertFalse(loading.contains("AI "))
    }

    /** Same paint path as [com.dirk.kalshiodds.ui.OddsViewModel.applyResult] / applyRolloverEvent. */
    private fun paintHome(
        snapshot: com.dirk.kalshiodds.data.repo.MarketsSnapshot,
        event: com.dirk.kalshiodds.signal.market.MarketRollover.Event,
        nowMs: Long
    ) = HomeSnapshotMerge.apply(snapshot, event, nowMs)

    private fun assertWindowUi(markets: List<MarketUiModel>, nowMs: Long) {
        val cards = HomeMarkets.currentWindowCards(markets, SignalSettings(), nowMs)
        assertEquals(3, cards.size)
        cards.forEach { card ->
            val label = WindowLabel.of(card.ticker, card.closeTimeEpochMs)
            assertTrue(label.contains("window"))
            assertEquals(card.closeTimeEpochMs, markets.first { it.ticker == card.ticker }.closeTimeEpochMs)
        }
    }

    private class RecordingWs {
        private var id = 1
        private val sids = mutableListOf<Int>()
        var tickers: List<String> = emptyList()
        val commands = mutableListOf<WsSubscriptionSwitch.Outbound>()
        val unsubscribed = mutableListOf<Set<String>>()
        val subscribed = mutableListOf<Set<String>>()
        private val channels = LiveSignalsPolicy.subscriptionPlan(
            subscribeTrades = true,
            watchedTickers = listOf("KXBTC15M-X")
        ).channels

        fun apply(event: MarketRollover.Event) {
            if (event.reconnect) {
                commands += WsSubscriptionSwitch.resubscribeOnReconnect(id++, channels, tickers)
                return
            }
            val next = event.activeTickers.toList().sorted()
            val out = WsSubscriptionSwitch.replace(id, channels, tickers, next, sids.toList())
            id += out.size
            commands += out
            out.filter { it.cmd == "unsubscribe" }.forEach { unsubscribed += it.droppedTickers.toSet() }
            out.filter { it.cmd == "subscribe" }.forEach { subscribed += it.marketTickers.toSet() }
            if (out.any { it.cmd == "unsubscribe" }) sids.clear()
            if (out.any { it.cmd == "subscribe" }) {
                tickers = next
                sids += listOf(11, 12, 13)
            }
        }
    }

    private fun v2AllIn(body: CreateOrderV2Request): Double {
        val count = body.count.toDouble()
        val price = body.price.toDouble()
        return count * price + KalshiFee.total(count.toInt(), price)
    }

    private fun log(
        ticker: String,
        predictedYes: Double,
        outcome: String,
        predictedSide: String?,
        score: Int? = null,
        brier: Double? = null
    ) = PredictionLogEntry(
        ticker = ticker,
        series = ticker.substringBefore("-"),
        predictedYes = predictedYes,
        predictedNo = 1.0 - predictedYes,
        marketMid = 0.63,
        timestampMs = 1L,
        closeTimeMs = 1L,
        outcome = outcome,
        score = score,
        brier = brier,
        predictedSide = predictedSide,
        edgePp = -8.0
    )

    private fun sample(
        ticker: String,
        yesAsk: Double,
        noAsk: Double,
        aiYes: Double,
        predicted: String
    ) = MarketUiModel(
        ticker = ticker,
        title = ticker,
        subtitle = null,
        floorStrike = 4000.0,
        yesBid = (yesAsk - 0.01).coerceAtLeast(0.01),
        yesAsk = yesAsk,
        noBid = (noAsk - 0.01).coerceAtLeast(0.01),
        noAsk = noAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = noAsk * 100.0,
        aiYesPercent = aiYes,
        aiNoPercent = 100.0 - aiYes,
        importedModelPp = aiYes,
        edgePp = aiYes - yesAsk * 100.0,
        volume = 1000.0,
        volume24h = 1000.0,
        openInterest = 100.0,
        liquidityDollars = 5000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = nowMs + 600_000L,
        status = "active",
        seriesLabel = "Ethereum",
        passedFilter = true,
        predictedSide = predicted,
        primaryHeroSide = predicted
    )

    private class RecordingTradeApi : KalshiTradeApi {
        val creates = mutableListOf<CreateOrderV2Request>()

        override suspend fun createOrderV2(body: CreateOrderV2Request): Response<CreateOrderV2Response> {
            creates += body
            return Response.success(
                201,
                CreateOrderV2Response(
                    orderId = "ord-${creates.size}",
                    remainingCount = body.count,
                    fillCount = "0.00"
                )
            )
        }

        override suspend fun getBalance() =
            Response.success(
                com.dirk.kalshiodds.data.dto.GetBalanceResponse(balance = 12_500, balanceDollars = "125.00")
            )

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
