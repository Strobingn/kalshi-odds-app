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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TradeModeLabel
import com.dirk.kalshiodds.ui.components.CollapsibleHomeSection
import com.dirk.kalshiodds.ui.components.MarketCard
import com.dirk.kalshiodds.ui.components.NextWindowLoadingCard
import com.dirk.kalshiodds.ui.components.SignalSummaryCard
import com.dirk.kalshiodds.ui.components.PaperBookCard
import com.dirk.kalshiodds.ui.components.PositionsCard
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
    onOpenChart: (MarketUiModel) -> Unit,
    onRefresh: () -> Unit,
    onBuyMarket: (MarketUiModel, String) -> Unit,
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
    onResumeAlerts: () -> Unit = {},
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
    val scoreLine = HomeCopy.scorecardLine(
        state.snapshot?.modelScoreCorrect,
        state.snapshot?.modelScoreTotal,
        state.snapshot?.modelMeanBrier
    )
    val signalCount = state.recentAlerts.size
    val ticketCount = state.tickets.proposals.size + state.tickets.working.count {
        it.orderId != null && it.error?.startsWith("cancelled") != true
    }
    val ticketActionable = state.tickets.proposals.any { it.canApprove }
    val positionCount = state.positions.size
    val paperCount = state.paper.fills.size

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.safeDrawing,
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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    actionIconContentColor = colors.accentBlue
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.bg),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        state.userMessage?.let { msg ->
                            item {
                                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        item {
                            ThisWindowCard(
                                market = best?.first,
                                decision = best?.second,
                                scorecardLine = scoreLine,
                                nowMs = nowMs,
                                onOpenScorecard = onOpenScorecard
                            )
                        }
                        items(coinCards, key = { it.series }) { card ->
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
                                    onSell = state.positions.firstOrNull { it.ticker == market.ticker }?.let {
                                        { onSellMarket(market) }
                                    },
                                    onOpenChart = { onOpenChart(market) }
                                )
                            }
                        }
                        item {
                            CollapsibleHomeSection(
                                title = "Signals",
                                count = signalCount,
                                autoExpand = signalCount > 0,
                                infoTitle = HomeHelp.SIGNALS_TITLE,
                                infoBody = HomeHelp.SIGNALS_BODY
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (state.recentAlerts.isEmpty()) {
                                        Text(
                                            "No live signals yet.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textSecondary
                                        )
                                    } else {
                                        state.recentAlerts.forEach { alert ->
                                            SignalSummaryCard(SignalCopy.card(alert))
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            CollapsibleHomeSection(
                                title = "Tickets",
                                count = ticketCount,
                                autoExpand = ticketCount > 0 && ticketActionable,
                                infoTitle = HomeHelp.TICKETS_TITLE,
                                infoBody = HomeHelp.TICKETS_BODY,
                                composeWhenCollapsed = true
                            ) {
                                TradeTicketsSection(
                                    tickets = state.tickets,
                                    credentialsConfigured = hasKey,
                                    paperTradingEnabled = state.settings.paperTradingEnabled,
                                    homeMode = true,
                                    onReview = onReviewTicket,
                                    onDismiss = onDismissTicket,
                                    onApprove = onApproveTicket,
                                    onApproveSell = onApproveSellTicket,
                                    onPaper = onPaperTicket,
                                    onPaperSell = onPaperSellTicket,
                                    onCancelApprove = onCancelApprove,
                                    onCancelOrder = onCancelOrder
                                )
                            }
                        }
                        item {
                            CollapsibleHomeSection(
                                title = "Positions",
                                count = positionCount,
                                autoExpand = positionCount > 0,
                                infoTitle = HomeHelp.POSITIONS_TITLE,
                                infoBody = HomeHelp.POSITIONS_BODY
                            ) {
                                PositionsCard(
                                    positions = state.positions,
                                    note = state.positionsNote,
                                    onSell = onSellPosition,
                                    onViewHistory = onOpenHistory,
                                    homeMode = true
                                )
                            }
                        }
                        item {
                            CollapsibleHomeSection(
                                title = "Paper",
                                count = paperCount,
                                autoExpand = paperCount > 0,
                                infoTitle = HomeHelp.PAPER_TITLE,
                                infoBody = HomeHelp.PAPER_BODY
                            ) {
                                PaperBookCard(
                                    paper = state.paper,
                                    enabled = state.settings.paperTradingEnabled,
                                    onToggle = onSetPaperTrading,
                                    onReset = onResetPaper,
                                    onSell = onSellPosition,
                                    onViewHistory = onOpenHistory,
                                    homeMode = true
                                )
                            }
                        }
                        item {
                            Text(
                                versionLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
