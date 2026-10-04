package com.dirk.kalshiodds.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchBitcoinBoardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun watchOffHidesTheCachedOfflineBoardAndShowsTheEmptyState() {
        setHome(watchBtc = false)
        rule.onNodeWithText(WatchBitcoinNotice.HOME_EMPTY).assertExists()
        rule.onNodeWithText(WatchBitcoinNotice.TURN_ON).assertExists()
        rule.onNodeWithText("BTC").assertDoesNotExist()
    }

    @Test
    fun turnOnBringsTheCachedBoardBackAndKeepsTheSetting() {
        val snap = cachedSnapshot()
        var settings by mutableStateOf(SignalSettings(watchBtc = false))
        rule.setContent {
            KalshiOddsTheme {
                home(snap, settings) { settings = settings.copy(watchBtc = true) }
            }
        }
        rule.onNodeWithText(WatchBitcoinNotice.HOME_EMPTY).assertExists()
        rule.onNodeWithText("BTC").assertDoesNotExist()
        rule.onNodeWithText(WatchBitcoinNotice.TURN_ON).performClick()
        rule.waitForIdle()
        assertTrue(settings.watchBtc)
        rule.onNodeWithText("BTC").assertExists()
        rule.onNodeWithText(WatchBitcoinNotice.HOME_EMPTY).assertDoesNotExist()
    }

    @Test
    fun watchOnStillShowsTheCachedBitcoinCard() {
        setHome(watchBtc = true)
        rule.onNodeWithText("BTC").assertExists()
        rule.onNodeWithText(WatchBitcoinNotice.HOME_EMPTY).assertDoesNotExist()
    }

    private fun setHome(watchBtc: Boolean) {
        val snap = cachedSnapshot()
        rule.setContent {
            KalshiOddsTheme {
                home(snap, SignalSettings(watchBtc = watchBtc)) {}
            }
        }
    }

    private fun cachedSnapshot(): MarketsSnapshot {
        val market = HomeFixtures.market(
            ticker = "KXBTC15M-CACHED",
            seriesLabel = "Bitcoin",
            yesAsk = 0.40,
            aiYes = 55.0,
            predicted = "YES",
            closeMs = HomeFixtures.NOW_MS + 600_000L
        )
        return MarketsSnapshot(
            btc = listOf(market),
            fetchedAtEpochMs = 50L,
            fromCache = true,
            errorMessage = "offline"
        )
    }

    @androidx.compose.runtime.Composable
    private fun home(
        snap: MarketsSnapshot,
        settings: SignalSettings,
        onWatchBitcoin: () -> Unit
    ) {
        HomeScreen(
            state = OddsUiState(
                snapshot = snap,
                settings = settings,
                isLoading = false
            ),
            onOpenSettings = {},
            onOpenScorecard = {},
            onOpenHistory = {},
            onOpenChart = {},
            onRefresh = {},
            onBuyMarket = { _, _ -> },
            onPaperSide = { _, _ -> },
            onSellMarket = {},
            onSetPaperTrading = {},
            onResetPaper = {},
            onSellPosition = { _, _ -> },
            onReviewTicket = {},
            onDismissTicket = {},
            onApproveTicket = {},
            onPaperTicket = {},
            onPaperSellTicket = { _, _, _ -> },
            onApproveSellTicket = { _, _, _ -> },
            onCancelApprove = {},
            onCancelOrder = {},
            onWatchBitcoin = onWatchBitcoin,
            nowMs = HomeFixtures.NOW_MS
        )
    }
}
