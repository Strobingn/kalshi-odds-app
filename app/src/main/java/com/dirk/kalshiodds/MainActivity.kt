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
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.ChartDetailScreen
import com.dirk.kalshiodds.ui.DataScreen
import com.dirk.kalshiodds.ui.DataViewModel
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
            KalshiOddsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf("odds") }
                    var chartTicker by rememberSaveable { mutableStateOf<String?>(null) }
                    val oddsState by oddsViewModel.state.collectAsStateWithLifecycle()
                    val chartMarket: MarketUiModel? = chartTicker?.let { t ->
                        oddsState.snapshot?.allMarkets?.firstOrNull { it.ticker == t }
                            ?: historyViewModel.marketModel(t)
                    }
                    when {
                        screen == "settings" -> SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { screen = "odds" },
                            onOpenData = { screen = "data" }
                        )
                        screen == "scorecard" -> ScorecardScreen(
                            viewModel = scorecardViewModel,
                            onBack = { screen = "odds" }
                        )
                        screen == "data" -> DataScreen(
                            viewModel = dataViewModel,
                            onBack = { screen = "odds" },
                            onOpenHistory = { screen = "history" }
                        )
                        screen == "history" -> com.dirk.kalshiodds.ui.HistoryScreen(
                            viewModel = historyViewModel,
                            onBack = { screen = "odds" },
                            onOpenMarket = { m ->
                                chartTicker = m.ticker
                                screen = "odds"
                            }
                        )
                        chartMarket != null -> ChartDetailScreen(
                            market = chartMarket,
                            points = chartMarket.bidHistory.ifEmpty {
                                chartMarket.oddsHistory.mapIndexed { i, mid ->
                                    com.dirk.kalshiodds.chart.BidPoint(
                                        tMs = (chartMarket.closeTimeEpochMs ?: 0L) -
                                            (chartMarket.oddsHistory.size - 1 - i) * 2_000L,
                                        upBidCents = mid,
                                        downBidCents = 100f - mid,
                                        spotUsd = chartMarket.spotUsd
                                    )
                                }
                            },
                            onBack = { chartTicker = null },
                            onBuyYes = { m ->
                                oddsViewModel.buyMarket(m, "YES")
                                chartTicker = null
                            },
                            onBuyNo = { m ->
                                oddsViewModel.buyMarket(m, "NO")
                                chartTicker = null
                            }
                        )
                        else -> OddsScreen(
                            viewModel = oddsViewModel,
                            onOpenSettings = { screen = "settings" },
                            onOpenScorecard = { screen = "scorecard" },
                            onOpenData = { screen = "data" },
                            onOpenHistory = { screen = "history" },
                            onOpenChart = { chartTicker = it.ticker }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LiveSignalsKeepAlive.ensureServiceFromUi(this)
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
