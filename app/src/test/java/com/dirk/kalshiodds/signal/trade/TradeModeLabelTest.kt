package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Paper toggle ON (default) + key saved must label LIVE $, because
 * [ApproveRouter.Intent.Live] sends a real order. The old MarketCard
 * used `if (paperTradingEnabled) PAPER` and contradicted Approve.
 */
class TradeModeLabelTest {

    @Test
    fun labelMatchesIntentLiveRouterForEveryToggleKeyPaperOnlyCombo() {
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
                    val fromSettings = TradeModeLabel.forApprove(settings, ticket)
                    val fromFlags = TradeModeLabel.forApprove(
                        paperTradingEnabled = paperOn,
                        liveCredentialsConfigured = keySaved,
                        paperOnly = paperOnly
                    )
                    assertEquals(
                        "settings vs flags paperOn=$paperOn key=$keySaved paperOnly=$paperOnly",
                        fromFlags,
                        fromSettings
                    )
                    assertEquals(
                        "label vs router paperOn=$paperOn key=$keySaved paperOnly=$paperOnly decision=$decision",
                        TradeModeLabel.of(decision),
                        fromSettings
                    )
                    val expected = when {
                        paperOnly -> TradeModeLabel.PAPER
                        keySaved -> TradeModeLabel.LIVE
                        else -> TradeModeLabel.NO_KEY
                    }
                    assertEquals(
                        "concrete paperOn=$paperOn key=$keySaved paperOnly=$paperOnly",
                        expected,
                        fromSettings
                    )
                }
            }
        }
    }

    @Test
    fun paperOnWithKeyIsLiveDollarNotPaper() {
        val settings = SignalSettings(
            paperTradingEnabled = true,
            apiKeyId = "key-id",
            hasPrivateKey = true
        )
        assertEquals(TradeModeLabel.LIVE, TradeModeLabel.forApprove(settings))
        assertEquals(
            ApproveRouter.Decision.Live,
            ApproveRouter.decide(
                paperTradingEnabled = true,
                paperOnly = false,
                isSell = false,
                liveCredentialsConfigured = true,
                canApprove = true,
                intent = ApproveRouter.Intent.Live
            )
        )
    }
}
