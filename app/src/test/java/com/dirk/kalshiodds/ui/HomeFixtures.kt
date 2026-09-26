package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketUiState
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.time.ZoneId
import java.time.ZonedDateTime

object HomeFixtures {
    const val NOW_MS = 1_700_000_000_000L
    const val CLOSE_MS = NOW_MS + 372_000L // 6:12 left

    fun market(
        ticker: String,
        seriesLabel: String,
        yesAsk: Double,
        aiYes: Double,
        predicted: String,
        closeMs: Long = CLOSE_MS,
        floorStrike: Double = 67_000.0,
        spotUsd: Double = 67_240.0,
        spotDelta: Double = 240.0
    ): MarketUiModel {
        val noAsk = (1.0 - yesAsk).coerceIn(0.01, 0.99)
        return MarketUiModel(
            ticker = ticker,
            title = seriesLabel,
            subtitle = null,
            floorStrike = floorStrike,
            yesBid = (yesAsk - 0.02).coerceAtLeast(0.01),
            yesAsk = yesAsk,
            noBid = (noAsk - 0.02).coerceAtLeast(0.01),
            noAsk = noAsk,
            lastPrice = yesAsk,
            yesProbabilityPercent = yesAsk * 100.0,
            noProbabilityPercent = noAsk * 100.0,
            aiYesPercent = aiYes,
            aiNoPercent = 100.0 - aiYes,
            importedModelPp = aiYes,
            edgePp = aiYes - yesAsk * 100.0,
            volume = 2000.0,
            volume24h = 2000.0,
            openInterest = 200.0,
            liquidityDollars = 8000.0,
            closeTimeLocal = null,
            closeTimeEpochMs = closeMs,
            status = "active",
            seriesLabel = seriesLabel,
            passedFilter = true,
            predictedSide = predicted,
            primaryHeroSide = predicted,
            spotUsd = spotUsd,
            spotVsTargetUsd = spotDelta,
            oddsHistory = listOf(48f, 50f, 51f, (yesAsk * 100.0).toFloat())
        )
    }

    fun actionableDownBtc() = market(
        ticker = "KXBTC15M-25SEP181700-50",
        seriesLabel = "Bitcoin",
        yesAsk = 0.80,
        aiYes = 10.0,
        predicted = "NO",
        floorStrike = 67_000.0,
        spotUsd = 66_760.0,
        spotDelta = -240.0
    )

    fun actionableBtc() = market(
        ticker = "KXBTC15M-25SEP181700-50",
        seriesLabel = "Bitcoin",
        yesAsk = 0.20,
        aiYes = 80.0,
        predicted = "YES",
        floorStrike = 67_000.0,
        spotUsd = 67_240.0,
        spotDelta = 240.0
    )

    fun noBetEth() = market(
        ticker = "KXETH15M-25SEP181700-40",
        seriesLabel = "Ethereum",
        yesAsk = 0.63,
        aiYes = 70.0,
        predicted = "YES",
        floorStrike = 4_000.0,
        spotUsd = 3_980.0,
        spotDelta = -20.0
    )

    /**
     * Phone screenshot on 0.3.14: BTC 15m, UP 34¢ / DOWN 66¢, AI 39% / 61%.
     * Realistic book is 33/34 vs 66/67. Model and market both favor DOWN.
     */
    fun screenshotPhoneBtc() = market(
        ticker = "KXBTC15M-26SEP251600-45",
        seriesLabel = "Bitcoin",
        yesAsk = 0.34,
        aiYes = 39.0,
        predicted = "YES",
        closeMs = NOW_MS + 707_000L,
        floorStrike = 84_144.0,
        spotUsd = 84_140.0,
        spotDelta = -4.0
    ).copy(
        yesBid = 0.33,
        yesAsk = 0.34,
        noBid = 0.66,
        noAsk = 0.67,
        lastPrice = 0.34,
        yesProbabilityPercent = 33.5,
        noProbabilityPercent = 66.5,
        aiYesPercent = 39.0,
        aiNoPercent = 61.0,
        importedModelPp = 39.0,
        edgePp = 39.0 - 33.5,
        tapeConflict = true,
        tapeConflictNote = "AI says UP, market + spot say DOWN",
        modelLeanSide = "YES",
        primaryHeroSide = "NO",
        predictedSide = "YES",
        oddsHistory = listOf(36f, 35f, 34f, 33f, 34f, 34f)
    )

    fun disagreementBtc() = actionableBtc().copy(
        tapeConflict = true,
        tapeConflictNote = "AI says UP, market + spot say DOWN",
        modelLeanSide = "YES",
        primaryHeroSide = "NO",
        importedModelPp = 58.0,
        aiYesPercent = 58.0,
        aiNoPercent = 42.0,
        edgePp = 38.0,
        spotVsTargetUsd = -240.0,
        spotUsd = 66_760.0
    )

    fun noBetSol() = market(
        ticker = "KXSOL15M-25SEP181700-20",
        seriesLabel = "Solana",
        yesAsk = 0.63,
        aiYes = 55.0,
        predicted = "YES",
        floorStrike = 180.0,
        spotUsd = 178.0,
        spotDelta = -2.0
    )

    fun settings(hasKey: Boolean): SignalSettings = SignalSettings(
        apiKeyId = if (hasKey) "key-id" else "",
        hasPrivateKey = hasKey,
        paperTradingEnabled = true,
        ticketsEnabled = true
    )

    val SAMPLE_SCORECARD = HomeScorecardSummary(
        wins = 12,
        losses = 6,
        hitRate = 12.0 / 18.0,
        paperPnlUsd = 12.40,
        settledCount = 18
    )

    fun sampleSettledEntries(): List<PredictionLogEntry> {
        val zone = ZoneId.of("America/New_York")
        fun et(hour: Int, i: Int): Long =
            ZonedDateTime.of(2026, 9, 25, hour, i, 0, 0, zone).toInstant().toEpochMilli()
        fun pick(
            ticker: String,
            series: String,
            at: Long,
            won: Boolean,
            side: String = "YES"
        ): PredictionLogEntry {
            val yesOutcome = if (side == "YES") won else !won
            return PredictionLogEntry(
                ticker = ticker,
                series = series,
                predictedYes = if (side == "YES") 0.70 else 0.30,
                predictedNo = if (side == "YES") 0.30 else 0.70,
                marketMid = 0.55,
                timestampMs = at,
                closeTimeMs = at,
                outcome = if (yesOutcome) "yes" else "no",
                score = if (won) 1 else 0,
                predictedSide = side,
                edgePp = 5.0,
                settledAtMs = at
            )
        }
        val btcMorning = (0 until 8).map { i ->
            pick("KXBTC15M-26SEP25${1000 + i}-50", "KXBTC15M", et(10, i), won = i < 6, side = if (i % 3 == 0) "NO" else "YES")
        }
        val btcAfternoon = (0 until 6).map { i ->
            pick("KXBTC15M-26SEP25${1400 + i}-20", "KXBTC15M", et(14, i), won = i < 4)
        }
        val btcEvening = (0 until 4).map { i ->
            pick("KXBTC15M-26SEP25${1800 + i}-40", "KXBTC15M", et(18, i), won = i < 2, side = if (i == 1) "NO" else "YES")
        }
        val storedSol = (0 until 6).map { i ->
            pick("KXSOL15M-26SEP25${1400 + i}-20", "KXSOL15M", et(14, i), won = i < 4)
        }
        val storedEth = (0 until 4).map { i ->
            pick("KXETH15M-26SEP25${1800 + i}-40", "KXETH15M", et(18, i), won = i < 2, side = if (i == 1) "NO" else "YES")
        }
        return btcMorning + btcAfternoon + btcEvening + storedSol + storedEth
    }

    fun sampleScorecardUi(): ScorecardUi =
        ScorecardUi(view = ScorecardCopy.of(sampleSettledEntries(), SAMPLE_SCORECARD.paperPnlUsd))

    fun sampleSettledFills(): List<PaperFill> {
        val entries = sampleSettledEntries().filter { it.ticker.startsWith("KXBTC15M") }
        return entries.mapIndexed { i, e ->
            val ask = if (e.predictedSide == "NO") 0.66 else if (i % 4 == 0) 0.28 else if (i % 3 == 0) 0.72 else 0.34
            val won = e.score == 1
            val contracts = if (i % 2 == 0) 29 else 8
            val fee = 0.18 + i * 0.01
            val stake = contracts * ask
            val pnl = if (won) contracts * 1.0 - stake - fee else -(stake + fee)
            val source = if (i < 10) "AI hunter" else PaperTileBuy.SOURCE
            PaperFill(
                id = "fill-$i",
                ticker = e.ticker,
                side = e.predictedSide ?: "YES",
                stakeUsd = stake,
                contracts = contracts,
                limitPrice = ask,
                source = source,
                createdAtMs = e.settledAtMs ?: e.timestampMs,
                settled = true,
                outcome = e.outcome,
                won = won,
                pnlUsd = pnl,
                note = String.format(java.util.Locale.US, "settled · fee $%.2f", fee)
            )
        }
    }

    fun sampleScorecardDetailUi(): ScorecardUi {
        val entries = sampleSettledEntries()
        val fills = sampleSettledFills()
        val windows = entries.take(6).map {
            com.dirk.kalshiodds.data.local.archive.SettledWindowRow(
                ticker = it.ticker,
                series = it.series,
                result = it.outcome ?: "yes",
                strikeUsd = 84_144.0
            )
        }
        return ScorecardUi(
            view = ScorecardCopy.of(entries, fills, paperPnlUsd = 0.0, windows = windows),
            metrics = com.dirk.kalshiodds.signal.feedback.ScorecardMetrics.compute(entries),
            allowlist = com.dirk.kalshiodds.signal.feedback.Allowlist.State(),
            adapter = com.dirk.kalshiodds.signal.feedback.OnlineAdapter.identity(),
            guardrails = com.dirk.kalshiodds.signal.feedback.Guardrails.identity(),
            extendedLine = "RL n=12 · meta n=8 · conformal n=4 cold",
            sitOut = false
        )
    }

    fun state(
        btc: MarketUiModel,
        eth: MarketUiModel,
        sol: MarketUiModel,
        hasKey: Boolean,
        correct: Int = 12,
        total: Int = 18,
        brier: Double? = 0.211,
        scorecard: HomeScorecardSummary = SAMPLE_SCORECARD
    ): OddsUiState = OddsUiState(
        isLoading = false,
        snapshot = MarketsSnapshot(
            btc = listOf(btc),
            eth = listOf(eth),
            sol = listOf(sol),
            fetchedAtEpochMs = NOW_MS,
            fromCache = false,
            modelScoreCorrect = correct,
            modelScoreTotal = total,
            modelMeanBrier = brier
        ),
        settings = settings(hasKey),
        tickets = TicketUiState(),
        paper = PaperBookState(),
        positions = emptyList(),
        recentAlerts = sampleAlerts(),
        scorecardSummary = scorecard
    )

    fun openPaperFill(
        ticker: String,
        side: String = "YES",
        contracts: Int = 47,
        limitPrice: Double = 0.20,
        stakeUsd: Double = 9.93
    ): PaperFill = PaperFill(
        id = "fixture-paper",
        ticker = ticker,
        side = side,
        stakeUsd = stakeUsd,
        contracts = contracts,
        limitPrice = limitPrice,
        source = PaperTileBuy.SOURCE,
        createdAtMs = NOW_MS,
        note = "fixture open paper"
    )

    fun openPosition(): LivePosition = LivePosition(
        ticker = "KXBTC15M-26SEP251530-30",
        side = "YES",
        contracts = 50.0,
        exposureUsd = 16.0,
        avgCost = 0.32,
        bestBid = 0.001,
        unrealizedPnlUsd = -15.95
    )

    fun sellMarket(bid: Double?): MarketUiModel = market(
        ticker = "KXBTC15M-26SEP251530-30",
        seriesLabel = "Bitcoin",
        yesAsk = 0.02,
        aiYes = 40.0,
        predicted = "YES"
    ).copy(
        yesBid = bid,
        noAsk = bid?.let { (1.0 - it).coerceIn(0.01, 0.99) },
        closeTimeEpochMs = NOW_MS + 600_000L,
        status = "active"
    )

    fun sellTicketWithBid(): TradeTicket = TicketBuilder.proposeSell(
        market = sellMarket(0.001),
        side = "YES",
        heldContracts = 50,
        ctx = TicketBuilder.Context(
            settings = settings(hasKey = true),
            alertsPaused = false,
            idFactory = { "sell-bid" },
            nowMs = NOW_MS
        )
    )!!

    fun sellTicketNoBid(): TradeTicket = TicketBuilder.proposeSell(
        market = sellMarket(null).copy(yesBid = null, noAsk = null),
        side = "YES",
        heldContracts = 50,
        ctx = TicketBuilder.Context(
            settings = settings(hasKey = true),
            alertsPaused = false,
            idFactory = { "sell-nobid" },
            nowMs = NOW_MS
        )
    )!!

    fun awaitingSell(ticket: TradeTicket): TicketUiState = TicketUiState(
        phase = TicketPhase.AwaitingApprove(ticket),
        proposals = listOf(ticket)
    )

    fun sampleAlerts(): List<SignalAlert> = listOf(
        SignalAlert(
            id = "a1",
            ticker = "KXBTC15M-26SEP251400-00",
            series = "KXBTC15M",
            deltaPp = -11.4,
            fairValuePp = 32.6,
            marketMidPp = 44.0,
            reason = "QUIET/EARLY · cal · adapt · AI 58% vs mkt 44% · flow YES · Δ -11.4pp (fv 33%)",
            createdAtMs = NOW_MS,
            receiveElapsedNanos = 0L,
            regime = "QUIET",
            tteRegime = "EARLY",
            predictedSide = "NO"
        ),
        SignalAlert(
            id = "a2",
            ticker = "KXBTC15M-26SEP251345-45",
            series = "KXBTC15M",
            deltaPp = 9.3,
            fairValuePp = 73.3,
            marketMidPp = 64.0,
            reason = "TREND/EARLY · cal · adapt · AI 68% vs mkt 64% · flow NO · Δ +9.3pp (fv 73%)",
            createdAtMs = NOW_MS,
            receiveElapsedNanos = 0L,
            regime = "TREND",
            tteRegime = "EARLY",
            predictedSide = "YES"
        )
    )
}
