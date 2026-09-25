package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.trade.TicketUiState

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

    fun state(
        btc: MarketUiModel,
        eth: MarketUiModel,
        sol: MarketUiModel,
        hasKey: Boolean,
        correct: Int = 12,
        total: Int = 18,
        brier: Double? = 0.211
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
        positions = emptyList()
    )
}
