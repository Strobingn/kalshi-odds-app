package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TradeModeLabel
import com.dirk.kalshiodds.ui.components.D3Card
import com.dirk.kalshiodds.ui.components.MarketCard
import com.dirk.kalshiodds.ui.components.NextWindowLoadingCard
import com.dirk.kalshiodds.ui.components.ThisWindowCard
import com.dirk.kalshiodds.ui.components.TradeModeChip
import com.dirk.kalshiodds.ui.components.TradeTicketsSection
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: OddsUiState,
    onOpenSettings: () -> Unit,
    onOpenApiKeySettings: () -> Unit = onOpenSettings,
    onOpenScorecard: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSignalHistory: () -> Unit = onOpenHistory,
    onOpenChart: (MarketUiModel) -> Unit,
    onRefresh: () -> Unit,
    onBuyMarket: (MarketUiModel, String) -> Unit,
    onPaperSide: (MarketUiModel, String) -> Unit,
    onSellMarket: (MarketUiModel) -> Unit,
    onSetPaperTrading: (Boolean) -> Unit,
    onResetPaper: () -> Unit,
    onSellPosition: (String, String) -> Unit,
    onReviewTicket: (String) -> Unit,
    onDismissTicket: (String) -> Unit,
    onApproveTicket: (String) -> Unit,
    onApproveSellTicket: (String, Int, Double) -> Unit,
    onPaperTicket: (String) -> Unit,
    onPaperSellTicket: (String, Int, Double) -> Unit,
    onCancelApprove: () -> Unit,
    onCancelOrder: (String) -> Unit,
    onLimitCents: (String, String) -> Unit = { _, _ -> },
    onResumeAlerts: () -> Unit = {},
    onStop: () -> Unit = {},
    onCancelResting: (String, String, String) -> Unit = { _, _, _ -> },
    nowMs: Long = System.currentTimeMillis(),
    versionLabel: String = AppVersion.label
) {
    val colors = DipTheme.colors
    val mode = TradeModeLabel.forApprove(state.settings)
    val hasKey = state.settings.tradingCredentialsConfigured()
    val ctx = TicketBuilder.Context(settings = state.settings, alertsPaused = state.alertsPaused, nowMs = nowMs)
    val coinCards = HomeMarkets.coinCards(state.snapshot?.allMarkets.orEmpty(), nowMs)
    val windowMarkets = coinCards.mapNotNull { it.market }
    val decisions = HomeMarkets.decisions(windowMarkets, ctx)
    val ranked = HomeMarkets.ranked(windowMarkets, decisions, state.settings)
    val best = HomeMarkets.best(ranked, decisions)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.userMessage) {
        val msg = state.userMessage ?: return@LaunchedEffect
        if (com.dirk.kalshiodds.data.api.KalshiRequestStatus.isQuietStatus(msg)) return@LaunchedEffect
        snackbar.showSnackbar(msg)
    }
    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = dipContentInsets(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(HomeCopy.TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TradeModeChip(mode)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenScorecard) {
                        Icon(Icons.Default.Assessment, contentDescription = HomeCopy.SCORECARD_CONTENT_DESCRIPTION)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = HomeCopy.SETTINGS_CONTENT_DESCRIPTION)
                    }
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = HomeCopy.REFRESH_CONTENT_DESCRIPTION)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textSecondary
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (ApiKeyUi.showNoKeyBanner(hasKey)) {
                Text(
                    ApiKeyUi.BANNER,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(colors.accentOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .clickable(onClick = onOpenApiKeySettings)
                )
            }
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.snapshot == null && state.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accentBlue)
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.bg),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        state.pauseBanner?.let { banner ->
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colors.accentOrange.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(banner, style = MaterialTheme.typography.bodyMedium, color = colors.accentOrange, fontWeight = FontWeight.Bold)
                                    Button(onClick = onResumeAlerts, modifier = Modifier.padding(top = 8.dp)) {
                                        Text("Resume alerts")
                                    }
                                }
                            }
                        }
                        state.updateBanner?.let { banner ->
                            item {
                                Text(
                                    banner,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary,
                                    modifier = Modifier.clickable { onOpenSettings() }
                                )
                            }
                        }
                        state.pollLabel.takeIf { it.startsWith("Backing off") }?.let { label ->
                            item {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        state.cfFeedLine?.let { line ->
                            item {
                                Text(
                                    "CF Benchmarks · $line",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        state.userMessage?.takeIf { !it.startsWith("Paper ", true) && !it.startsWith("PAPER ", true) }?.let { msg ->
                            item {
                                val quiet = com.dirk.kalshiodds.data.api.KalshiRequestStatus.isQuietStatus(msg)
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (quiet) colors.textSecondary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        item {
                            HomeStopButton(onStop = onStop)
                        }
                        item {
                            ThisWindowCard(
                                market = best?.first,
                                decision = best?.second,
                                nowMs = nowMs,
                                scorecard = state.scorecardSummary,
                                onOpenScorecard = onOpenScorecard
                            )
                        }
                        val keyedCoins = LazyListKeys.keyed(coinCards) { it.series }
                        items(keyedCoins, key = { it.key }) { row ->
                            val card = row.value
                            val market = card.market
                            if (market == null) {
                                NextWindowLoadingCard(card.series)
                            } else {
                                val call = decisions[market.ticker] ?: BetCall.decide(market, ctx)
                                MarketCard(
                                    market = market,
                                    decision = call,
                                    settings = state.settings,
                                    paperTradingEnabled = state.settings.paperTradingEnabled,
                                    nowMs = nowMs,
                                    onBuyYes = { onBuyMarket(market, "YES") },
                                    onBuyNo = { onBuyMarket(market, "NO") },
                                    onPaperUp = { onPaperSide(market, "YES") },
                                    onPaperDown = { onPaperSide(market, "NO") },
                                    paperPosition = HomeCopy.paperPositionLine(state.paper, market.ticker),
                                    onSell = state.positions.firstOrNull { it.ticker == market.ticker }?.let {
                                        { onSellMarket(market) }
                                    },
                                    onOpenChart = { onOpenChart(market) }
                                )
                            }
                        }
                        if (state.d3.closeTimeEpochMs != null ||
                            state.d3.qualifying.isNotEmpty() ||
                            state.d3.todayPicks.isNotEmpty() ||
                            state.d3.phase != com.dirk.kalshiodds.signal.d3.D3Phase.WAITING
                        ) {
                            item {
                                D3Card(snapshot = state.d3)
                            }
                        }
                        item {
                            HomeOpenBetsSection(
                                rows = HomeOpenBets.rows(state.positions, state.restingOrders, state.paper.fills),
                                onClose = onSellPosition,
                                onCancel = onCancelResting
                            )
                        }
                        item {
                            Text(
                                HomeCopy.SIGNAL_HISTORY,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                                modifier = Modifier
                                    .padding(top = 4.dp, bottom = 4.dp)
                                    .clickable(onClick = onOpenSignalHistory)
                            )
                        }
                    }
                    TradeTicketsSection(
                        tickets = state.tickets,
                        credentialsConfigured = hasKey,
                        paperTradingEnabled = state.settings.paperTradingEnabled,
                        feeRate = state.settings.feeRate,
                        homeMode = true,
                        listVisible = false,
                        onReview = onReviewTicket,
                        onDismiss = onDismissTicket,
                        onApprove = onApproveTicket,
                        onApproveSell = onApproveSellTicket,
                        onPaper = onPaperTicket,
                        onPaperSell = onPaperSellTicket,
                        onCancelApprove = onCancelApprove,
                        onCancelOrder = onCancelOrder,
                        onLimitCents = onLimitCents
                    )
                    }
                }
            }
        }
    }
}
