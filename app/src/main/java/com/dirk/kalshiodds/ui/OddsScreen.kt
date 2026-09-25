package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import com.dirk.kalshiodds.signal.engine.SkipFilter
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.ui.components.AiFairLabel
import com.dirk.kalshiodds.ui.components.BidChart
import com.dirk.kalshiodds.ui.components.MarketAskHero
import com.dirk.kalshiodds.ui.components.MarketCard
import com.dirk.kalshiodds.ui.components.PaperBookCard
import com.dirk.kalshiodds.ui.components.PastSettlementsRow
import com.dirk.kalshiodds.ui.components.PositionsCard
import com.dirk.kalshiodds.ui.components.TapeConflictBanner
import com.dirk.kalshiodds.ui.components.TargetNowLine
import com.dirk.kalshiodds.ui.components.TimeLeftLabel
import com.dirk.kalshiodds.ui.components.TradeTicketsSection
import com.dirk.kalshiodds.ui.components.UpDownBuyButtons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OddsScreen(
    viewModel: OddsViewModel,
    onOpenSettings: () -> Unit,
    onOpenApiKeySettings: () -> Unit = onOpenSettings,
    onOpenScorecard: () -> Unit,
    onOpenData: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenChart: (MarketUiModel) -> Unit
) {
    val colors = DipTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Dip Hunter", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenData) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Data")
                    }
                    IconButton(onClick = onOpenScorecard) {
                        Icon(Icons.Default.Assessment, contentDescription = "Scorecard")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isLoading) {
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
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val snapshot = state.snapshot
            if (snapshot == null && state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentBlue)
                }
            } else {
                val allMarkets = snapshot?.allMarkets.orEmpty()
                val ticketCtx = TicketBuilder.Context(
                    settings = state.settings,
                    alertsPaused = state.alertsPaused
                )
                fun decisionOf(m: MarketUiModel) = BetCall.decide(m, ticketCtx)
                val ranked = allMarkets
                    .filter { it.edgePp != null }
                    .filter {
                        SkipFilter.shouldShowOpportunity(
                            passedFilter = it.passedFilter,
                            edgePp = it.edgePp,
                            settings = state.settings,
                            netEdgePp = it.netEdgePp,
                            muted = it.muted
                        )
                    }
                    .sortedWith(
                        compareBy<MarketUiModel> { BetCall.sortKey(decisionOf(it)) }
                            .thenByDescending { it.passedFilter && !it.muted }
                            .thenByDescending {
                                if (state.settings.rankByNetEv) abs(it.netEdgePp ?: it.edgePp ?: 0.0)
                                else abs(it.edgePp ?: 0.0)
                            }
                    )
                val threshold = state.settings.effectiveEdgeThresholdPp()
                val alertCount = allMarkets.count { it.edgeAlert && it.passedFilter }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.bg),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        MetaHeader(
                            fetchedAtEpochMs = snapshot?.fetchedAtEpochMs ?: 0L,
                            fromCache = snapshot?.fromCache == true,
                            message = state.userMessage,
                            pollLabel = state.pollLabel,
                            modelScoreLabel = state.modelScoreLabel,
                            chipLabel = state.signalStatus.chipLabel(),
                            chipState = state.signalStatus.state,
                            onOpenScorecard = onOpenScorecard,
                            pauseBanner = state.pauseBanner,
                            mutedSummary = state.mutedSummary,
                            onResumeAlerts = { viewModel.resumeAlerts() }
                        )
                    }
                    if (ApiKeyUi.showNoKeyBanner(state.settings.tradingCredentialsConfigured())) {
                        item {
                            Text(
                                ApiKeyUi.BANNER,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.accentOrange,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.accentOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                                    .clickable(onClick = onOpenApiKeySettings)
                            )
                        }
                    }
                    item {
                        LiveUpDownHero(
                            market = featuredLiveMarket(allMarkets),
                            onBuyYes = { m -> viewModel.buyMarket(m, "YES") },
                            onBuyNo = { m -> viewModel.buyMarket(m, "NO") },
                            onOpenChart = onOpenChart
                        )
                    }
                    item { SectionHeader("Paper book") }
                    item {
                        PaperBookCard(
                            paper = state.paper,
                            enabled = state.settings.paperTradingEnabled,
                            onToggle = viewModel::setPaperTrading,
                            onReset = viewModel::resetPaperBook,
                            onSell = { ticker, side -> viewModel.sellPosition(ticker, side) },
                            onViewHistory = onOpenHistory
                        )
                    }
                    item { SectionHeader("Positions") }
                    item {
                        PositionsCard(
                            positions = state.positions,
                            note = state.positionsNote,
                            onSell = { ticker, side -> viewModel.sellPosition(ticker, side) },
                            onViewHistory = onOpenHistory
                        )
                    }
                    item {
                        TradeTicketsSection(
                            tickets = state.tickets,
                            credentialsConfigured = state.settings.tradingCredentialsConfigured(),
                            paperTradingEnabled = state.settings.paperTradingEnabled,
                            onReview = { viewModel.openTicketApprove(it) },
                            onDismiss = { viewModel.dismissTicket(it) },
                            onApprove = { viewModel.approveTicket(it) },
                            onApproveSell = { id, count, price -> viewModel.approveSellTicket(id, count, price) },
                            onPaper = { viewModel.paperTicket(it) },
                            onPaperSell = { id, count, price -> viewModel.paperSellTicket(id, count, price) },
                            onCancelApprove = { viewModel.cancelTicketApprove() },
                            onCancelOrder = { viewModel.cancelWorkingOrder(it) }
                        )
                    }
                    item { SectionHeader("Signals") }
                    item {
                        LiveSignalsCard(
                            enabled = state.settings.liveSignalsEnabled,
                            connection = state.signalStatus.state,
                            onToggle = viewModel::setLiveSignals
                        )
                    }
                    state.mlGuardNote?.let { note ->
                        item {
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.accentOrange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.accentOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            )
                        }
                    }
                    if (state.recentAlerts.isNotEmpty()) {
                        item { SectionHeader("Recent signals") }
                        items(state.recentAlerts.take(8), key = { "sig-${it.id}" }) { alert ->
                            SignalRow(alert)
                        }
                    } else if (state.persistedHistory.isNotEmpty()) {
                        item { SectionHeader("Saved results (last session)") }
                        item {
                            Text(
                                "Reloaded from SQLite after a crash or kill. Export a CSV from Settings or Scorecard.",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary
                            )
                        }
                        items(state.persistedHistory.take(8), key = { "hist-${it.id}-${it.ticker}" }) { row ->
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s  %s  Δ%+.1fpp  fair %.0f%%  mkt %.0f%%",
                                    row.ticker,
                                    row.side,
                                    row.edgePp,
                                    row.fairPp,
                                    row.marketPp
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            )
                        }
                    }
                    if (alertCount > 0) {
                        item {
                            Text(
                                text = "⚡ Edge alert: $alertCount crypto market(s) with |AI−market| ≥ ${threshold.toInt()}pp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.accentGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.accentGreen.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            )
                        }
                    }
                    if (ranked.isNotEmpty()) {
                        item { SectionHeader("Ranked opportunities") }
                        item {
                            Text(
                                text = if (state.settings.rankByNetEv) {
                                    "Crypto only · ranked by |net EV| after fees/spread. “N contracts max” is advisory — no orders."
                                } else {
                                    "Crypto only · ranked by |fair − mid|. Stance is advisory — no orders."
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary
                            )
                        }
                        items(ranked.take(8), key = { "rank-${it.ticker}" }) { market ->
                            val call = decisionOf(market)
                            MarketCard(
                                market,
                                compact = true,
                                decision = call,
                                settings = state.settings,
                                paperTradingEnabled = state.settings.paperTradingEnabled,
                                onBuyYes = { viewModel.buyMarket(market, "YES") },
                                onBuyNo = { viewModel.buyMarket(market, "NO") },
                                onSell = sellAction(state, viewModel, market),
                                onOpenChart = { onOpenChart(market) }
                            )
                        }
                    }
                    item { SectionHeader("Live markets") }
                    if (state.settings.watchBtc) {
                        item { SectionHeader("Bitcoin · KXBTC15M") }
                        marketsOrEmpty("btc", snapshot?.btc.orEmpty(), viewModel, state, onOpenChart)
                    }
                    if (state.settings.watchEth) {
                        item { Spacer(Modifier.height(8.dp)); SectionHeader("Ethereum · KXETH15M") }
                        marketsOrEmpty("eth", snapshot?.eth.orEmpty(), viewModel, state, onOpenChart)
                    }
                    if (state.settings.watchSol) {
                        item { Spacer(Modifier.height(8.dp)); SectionHeader("Solana · KXSOL15M") }
                        marketsOrEmpty("sol", snapshot?.sol.orEmpty(), viewModel, state, onOpenChart)
                    }
                    if (snapshot?.extra.orEmpty().isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)); SectionHeader("Extra crypto") }
                        marketsOrEmpty("extra", snapshot?.extra.orEmpty(), viewModel, state, onOpenChart)
                    }
                    item {
                        Text(
                            AppVersion.label,
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

private fun androidx.compose.foundation.lazy.LazyListScope.marketsOrEmpty(
    section: String,
    markets: List<MarketUiModel>,
    viewModel: OddsViewModel,
    state: OddsUiState,
    onOpenChart: (MarketUiModel) -> Unit
) {
    if (markets.isEmpty()) {
        item(key = "empty-$section") {
            Text(
                text = "No open markets",
                color = DipTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    } else {
        val ctx = TicketBuilder.Context(settings = state.settings, alertsPaused = state.alertsPaused)
        val sorted = markets.sortedBy { BetCall.sortKey(BetCall.decide(it, ctx)) }
        items(sorted, key = { "$section-${it.ticker}" }) { market ->
            val call = BetCall.decide(market, ctx)
            MarketCard(
                market,
                decision = call,
                settings = state.settings,
                paperTradingEnabled = state.settings.paperTradingEnabled,
                onBuyYes = { viewModel.buyMarket(market, "YES") },
                onBuyNo = { viewModel.buyMarket(market, "NO") },
                onSell = sellAction(state, viewModel, market),
                onOpenChart = { onOpenChart(market) }
            )
        }
    }
}

private fun sellAction(
    state: OddsUiState,
    viewModel: OddsViewModel,
    market: MarketUiModel
): (() -> Unit)? {
    val pos = state.positions.firstOrNull { it.ticker == market.ticker } ?: return null
    return { viewModel.sellPosition(pos.ticker, pos.side) }
}

@Composable
private fun LiveSignalsCard(
    enabled: Boolean,
    connection: WsConnectionState,
    onToggle: (Boolean) -> Unit
) {
    val colors = DipTheme.colors
    val accent = when {
        !enabled -> colors.textSecondary
        connection == WsConnectionState.CONNECTED -> colors.accentGreen
        connection == WsConnectionState.RECONNECTING || connection == WsConnectionState.CONNECTING -> colors.accentOrange
        else -> colors.accentBlue
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Live signals",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (enabled) {
                        "On — WebSocket stays up when you leave the app. Leave the “DipHunter live signals” notification allowed."
                    } else {
                        "Off — odds stop shortly after you switch apps or turn the screen off."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun LiveUpDownHero(
    market: MarketUiModel?,
    onBuyYes: (MarketUiModel) -> Unit,
    onBuyNo: (MarketUiModel) -> Unit,
    onOpenChart: (MarketUiModel) -> Unit
) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = market?.title?.takeIf { it.isNotBlank() } ?: "Live scan",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            TimeLeftLabel(market?.closeTimeEpochMs, pill = true)
        }
        market?.let { TargetNowLine(it, Modifier.padding(top = 8.dp)) }
        market?.let { TapeConflictBanner(it, Modifier.padding(top = 8.dp)) }
        market?.let { m ->
            MarketAskHero(m, Modifier.padding(top = 10.dp))
            val quotes = com.dirk.kalshiodds.domain.MarketQuoteView.of(m)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    quotes.upHeader,
                    color = colors.accentGreen,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    quotes.downHeader,
                    color = colors.accentRed,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            AiFairLabel(m, Modifier.padding(top = 4.dp))
            PastSettlementsRow(m.pastSettlements, Modifier.padding(top = 8.dp))
            BidChart(
                points = m.bidHistory.ifEmpty {
                    m.oddsHistory.mapIndexed { i, mid ->
                        com.dirk.kalshiodds.chart.BidPoint(
                            tMs = (m.closeTimeEpochMs ?: 0L) - (m.oddsHistory.size - 1 - i) * 2_000L,
                            upBidCents = mid,
                            downBidCents = 100f - mid
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clickable { onOpenChart(m) },
                heightDp = 88,
                windowStartMs = m.closeTimeEpochMs?.minus(900_000L),
                windowEndMs = m.closeTimeEpochMs,
                strikeLabel = m.floorStrike?.let { String.format(Locale.US, "Strike $%,.0f", it) },
                spotUsd = m.spotUsd,
                strikeUsd = m.floorStrike,
                liveUpLabel = quotes.upChartLabel,
                liveDownLabel = quotes.downChartLabel
            )
        }
        Text(
            text = market?.ticker ?: "Waiting for live quote",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (market != null) {
            UpDownBuyButtons(
                market = market,
                onBuyYes = { onBuyYes(market) },
                onBuyNo = { onBuyNo(market) },
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

internal fun featuredLiveMarket(
    markets: List<MarketUiModel>,
    nowMs: Long = System.currentTimeMillis()
): MarketUiModel? = MarketLifecycle.featuredLive(markets, nowMs)

@Composable
private fun SignalRow(alert: SignalAlert) {
    val colors = DipTheme.colors
    val color = if (alert.deltaPp >= 0) colors.accentGreen else colors.accentOrange
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(alert.ticker, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                String.format(Locale.US, "%+.1f pp", alert.deltaPp),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Text(alert.reason, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        val tags = listOfNotNull(alert.regime, alert.tteRegime).joinToString(" · ")
        if (tags.isNotEmpty()) {
            Text(tags, style = MaterialTheme.typography.labelMedium, color = color)
        }
        Text(alert.stance, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun MetaHeader(
    fetchedAtEpochMs: Long,
    fromCache: Boolean,
    message: String?,
    pollLabel: String,
    modelScoreLabel: String?,
    chipLabel: String,
    chipState: WsConnectionState,
    onOpenScorecard: () -> Unit,
    pauseBanner: String? = null,
    mutedSummary: String? = null,
    onResumeAlerts: () -> Unit = {}
) {
    val colors = DipTheme.colors
    val chipColor = when (chipState) {
        WsConnectionState.CONNECTED -> colors.accentGreen
        WsConnectionState.RECONNECTING, WsConnectionState.CONNECTING -> colors.accentOrange
        WsConnectionState.NEEDS_API_KEY -> colors.accentOrange
        WsConnectionState.ERROR -> colors.accentRed
        WsConnectionState.REST_FALLBACK -> colors.accentBlue
        WsConnectionState.IDLE -> colors.textSecondary
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (fetchedAtEpochMs > 0) {
                    "Updated ${formatLocal(fetchedAtEpochMs)}"
                } else {
                    "Not yet updated"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            if (fromCache) {
                Text(
                    text = "CACHED",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accentBlue
                )
            }
        }
        Text(
            text = chipLabel,
            style = MaterialTheme.typography.labelMedium,
            color = chipColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 8.dp)
                .background(chipColor.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
        pauseBanner?.let { banner ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(colors.accentOrange.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = onResumeAlerts, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Resume alerts")
                }
            }
        }
        mutedSummary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentOrange,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = pollLabel,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        modelScoreLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentBlue,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(colors.accentBlue.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                text = "Open scorecard for hit rate, Brier, edge when right vs wrong",
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentBlue,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(onClick = onOpenScorecard)
            )
        }
        Text(
            text = "CRYPTO ONLY · BTC/ETH/SOL 15m · Paper vs Live Approve · no auto-fire.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private val stampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)

private fun formatLocal(epochMs: Long): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return stampFormatter.format(zoned)
}
