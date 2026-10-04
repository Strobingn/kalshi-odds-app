package com.dirk.kalshiodds

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.AppNavigator
import com.dirk.kalshiodds.ui.AppRoutes
import com.dirk.kalshiodds.ui.ChartDetailScreen
import com.dirk.kalshiodds.ui.DataScreen
import com.dirk.kalshiodds.ui.DataViewModel
import com.dirk.kalshiodds.ui.DipApp
import com.dirk.kalshiodds.ui.OddsScreen
import com.dirk.kalshiodds.ui.OddsViewModel
import com.dirk.kalshiodds.ui.ScorecardScreen
import com.dirk.kalshiodds.ui.ScorecardViewModel
import com.dirk.kalshiodds.ui.SettingsScreen
import com.dirk.kalshiodds.ui.SettingsViewModel
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val oddsViewModel: OddsViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val scorecardViewModel: ScorecardViewModel by viewModels()
    private val dataViewModel: DataViewModel by viewModels()
    private val historyViewModel: com.dirk.kalshiodds.ui.HistoryViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* in-app recent-signals list still works if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalNotifier.ensureChannels(this)
        com.dirk.kalshiodds.signal.notify.OpportunityNotifier.ensureChannel(this)
        handleOpportunityIntent(intent)
        lifecycleScope.launch {
            KalshiOddsApp.from(this@MainActivity).container.preferences.settings
                .map { it.notificationsEnabled || it.liveSignalsEnabled }
                .distinctUntilChanged()
                .collect { wantsNotify ->
                    if (wantsNotify) requestNotificationPermission()
                }
        }
        lifecycleScope.launch {
            KalshiOddsApp.from(this@MainActivity).container.preferences.settings
                .map { it.opportunityAlertsEnabled }
                .distinctUntilChanged()
                .collect { wants ->
                    if (wants) requestNotificationPermission()
                }
        }
        enableEdgeToEdge()
        setContent {
            val colorStyle by androidx.compose.runtime.remember {
                KalshiOddsApp.from(this@MainActivity).container.preferences.settings.map { it.colorStyle }
            }.collectAsStateWithLifecycle(initialValue = com.dirk.kalshiodds.ui.theme.ColorStyles.CLASSIC)
            KalshiOddsTheme(colorStyle = colorStyle) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navigator = rememberSaveable(saver = AppNavigator.Saver) { AppNavigator() }
                    var settingsFocusApiKey by rememberSaveable { mutableStateOf(false) }
                    var chartTicker by rememberSaveable { mutableStateOf<String?>(null) }
                    val oddsState by oddsViewModel.state.collectAsStateWithLifecycle()
                    val sheetOpen = oddsState.tickets.phase is TicketPhase.AwaitingApprove
                    val chartMarket: MarketUiModel? = chartTicker?.let { t ->
                        oddsState.snapshot?.allMarkets?.firstOrNull { it.ticker == t }
                            ?: historyViewModel.marketModel(t)
                    }
                    DipApp(
                        navigator = navigator,
                        sheetOpen = sheetOpen,
                        onCancelSheet = { oddsViewModel.cancelTicketApprove() },
                        home = {
                            OddsScreen(
                                viewModel = oddsViewModel,
                                onOpenSettings = {
                                    settingsFocusApiKey = false
                                    navigator.open(AppRoutes.SETTINGS)
                                },
                                onOpenApiKeySettings = {
                                    settingsFocusApiKey = true
                                    navigator.open(AppRoutes.SETTINGS)
                                },
                                onOpenScorecard = { navigator.open(AppRoutes.SCORECARD) },
                                onOpenData = { navigator.open(AppRoutes.DATA) },
                                onOpenHistory = { navigator.open(AppRoutes.HISTORY) },
                                onOpenSignalHistory = { navigator.open(AppRoutes.SIGNAL_HISTORY) },
                                onOpenChart = {
                                    chartTicker = it.ticker
                                    navigator.open(AppRoutes.CHART)
                                }
                            )
                        },
                        settings = {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = {
                                    settingsFocusApiKey = false
                                    navigator.back()
                                },
                                onOpenData = { navigator.open(AppRoutes.DATA) },
                                scrollToApiKey = settingsFocusApiKey
                            )
                        },
                        scorecard = {
                            ScorecardScreen(
                                viewModel = scorecardViewModel,
                                onBack = { navigator.back() },
                                showBack = navigator.canPop
                            )
                        },
                        data = {
                            DataScreen(
                                viewModel = dataViewModel,
                                onBack = { navigator.back() },
                                onOpenHistory = { navigator.open(AppRoutes.HISTORY) },
                                showBack = navigator.canPop
                            )
                        },
                        live = {
                            com.dirk.kalshiodds.ui.LiveApproveScreen(oddsViewModel)
                        },
                        more = {
                            val message = oddsState.userMessage.orEmpty()
                            val failed = message.contains("offline", ignoreCase = true) ||
                                message.contains("fail", ignoreCase = true)
                            val pending = oddsState.snapshot?.fromCache == true
                            val syncText = when {
                                oddsState.isLoading -> "Syncing…"
                                failed -> "Sync failed"
                                pending -> "Pending sync"
                                else -> "Synced"
                            }
                            com.dirk.kalshiodds.ui.MoreScreen(
                                onOpen = { dest ->
                                    if (dest.tab) navigator.selectTab(dest.route)
                                    else navigator.open(dest.route)
                                },
                                syncText = syncText,
                                syncFailed = failed && !oddsState.isLoading,
                                syncPending = pending && !failed && !oddsState.isLoading,
                                onCheckUpdate = {
                                    navigator.open(AppRoutes.SETTINGS)
                                    settingsViewModel.checkForKashiUpdate()
                                }
                            )
                        },
                        history = {
                            com.dirk.kalshiodds.ui.HistoryScreen(
                                viewModel = historyViewModel,
                                onBack = { navigator.back() },
                                onOpenMarket = { m ->
                                    chartTicker = m.ticker
                                    navigator.open(AppRoutes.CHART)
                                }
                            )
                        },
                        signalHistory = {
                            com.dirk.kalshiodds.ui.SignalHistoryScreen(
                                cards = com.dirk.kalshiodds.ui.signalHistoryCards(oddsState.recentAlerts),
                                onBack = { navigator.back() }
                            )
                        },
                        chart = {
                            val market = chartMarket
                            if (market == null) {
                                LaunchedEffect(chartTicker) {
                                    if (navigator.current == AppRoutes.CHART) {
                                        chartTicker = null
                                        navigator.back()
                                    }
                                }
                                OddsScreen(
                                    viewModel = oddsViewModel,
                                    onOpenSettings = { navigator.open(AppRoutes.SETTINGS) },
                                    onOpenScorecard = { navigator.open(AppRoutes.SCORECARD) },
                                    onOpenData = { navigator.open(AppRoutes.DATA) },
                                    onOpenHistory = { navigator.open(AppRoutes.HISTORY) },
                                    onOpenSignalHistory = { navigator.open(AppRoutes.SIGNAL_HISTORY) },
                                    onOpenChart = {
                                        chartTicker = it.ticker
                                        navigator.open(AppRoutes.CHART)
                                    }
                                )
                            } else {
                                ChartDetailScreen(
                                    market = market,
                                    points = market.bidHistory.ifEmpty {
                                        market.oddsHistory.mapIndexed { i, mid ->
                                            com.dirk.kalshiodds.chart.BidPoint(
                                                tMs = (market.closeTimeEpochMs ?: 0L) -
                                                    (market.oddsHistory.size - 1 - i) * 2_000L,
                                                upBidCents = mid,
                                                downBidCents = 100f - mid,
                                                spotUsd = market.spotUsd
                                            )
                                        }
                                    },
                                    onBack = {
                                        chartTicker = null
                                        navigator.back()
                                    },
                                    onBuyYes = { m ->
                                        oddsViewModel.buyMarket(m, "YES")
                                        chartTicker = null
                                        navigator.back()
                                    },
                                    onBuyNo = { m ->
                                        oddsViewModel.buyMarket(m, "NO")
                                        chartTicker = null
                                        navigator.back()
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LiveSignalsKeepAlive.ensureServiceFromUi(this)
        oddsViewModel.onForeground()
    }

    override fun onResume() {
        super.onResume()
        oddsViewModel.onForeground()
    }

    override fun onStop() {
        LiveSignalsKeepAlive.markUiInForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpportunityIntent(intent)
    }

    private fun handleOpportunityIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(com.dirk.kalshiodds.signal.notify.OpportunityNotifier.EXTRA_OPEN_TICKET, false) != true &&
            intent?.getStringExtra(SignalNotifier.EXTRA_TICKER) == null
        ) {
            return
        }
        val ticker = intent.getStringExtra(SignalNotifier.EXTRA_TICKER)
        val ticketId = intent.getStringExtra(com.dirk.kalshiodds.signal.notify.OpportunityNotifier.EXTRA_TICKET_ID)
        oddsViewModel.focusTicket(ticker, ticketId)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
