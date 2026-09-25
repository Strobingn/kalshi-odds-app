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
import com.dirk.kalshiodds.ui.DisagreementLabel
import com.dirk.kalshiodds.ui.HomeCopy
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * One named test per known 0.3.10–0.3.12 issue, on the real production
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
