package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.ui.components.MarketCard
import com.dirk.kalshiodds.ui.components.TradeTicketsSection
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.AccentRed
import com.dirk.kalshiodds.ui.theme.Bg
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OddsScreen(viewModel: OddsViewModel, onOpenSettings: () -> Unit, onOpenScorecard: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Dip Hunter") },
                actions = {
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
                    containerColor = Bg,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = AccentBlue
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
                    CircularProgressIndicator(color = AccentBlue)
                }
            } else {
                val allMarkets = snapshot?.allMarkets.orEmpty()
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
                        compareByDescending<MarketUiModel> { it.passedFilter && !it.muted }
                            .thenByDescending {
                                if (state.settings.rankByNetEv) abs(it.netEdgePp ?: it.edgePp ?: 0.0)
                                else abs(it.edgePp ?: 0.0)
                            }
                    )
                val threshold = state.settings.edgeThresholdPp
                val alertCount = allMarkets.count { it.edgeAlert && it.passedFilter }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Bg),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        LiveSignalsCard(
                            enabled = state.settings.liveSignalsEnabled,
                            connection = state.signalStatus.state,
                            onToggle = viewModel::setLiveSignals
                        )
                    }
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
                    item {
                        TradeTicketsSection(
                            tickets = state.tickets,
                            credentialsConfigured = state.settings.credentialsConfigured,
                            onReview = { viewModel.openTicketApprove(it) },
                            onDismiss = { viewModel.dismissTicket(it) },
                            onApprove = { viewModel.approveTicket(it) },
                            onCancelApprove = { viewModel.cancelTicketApprove() },
                            onCancelOrder = { viewModel.cancelWorkingOrder(it) }
                        )
                    }
                    if (state.recentAlerts.isNotEmpty()) {
                        item { SectionHeader("Recent signals") }
                        items(state.recentAlerts.take(8), key = { "sig-${it.id}" }) { alert ->
                            SignalRow(alert)
                        }
                    }
                    if (alertCount > 0) {
                        item {
                            Text(
                                text = "⚡ Edge alert: $alertCount crypto market(s) with |AI−market| ≥ ${threshold.toInt()}pp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AccentGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AccentGreen.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
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
                                color = TextSecondary
                            )
                        }
                        items(ranked.take(8), key = { "rank-${it.ticker}" }) { market ->
                            MarketCard(market, compact = true)
                        }
                    }
                    if (state.settings.watchBtc) {
                        item { SectionHeader("Bitcoin · KXBTC15M") }
                        marketsOrEmpty("btc", snapshot?.btc.orEmpty())
                    }
                    if (state.settings.watchEth) {
                        item { Spacer(Modifier.height(8.dp)); SectionHeader("Ethereum · KXETH15M") }
                        marketsOrEmpty("eth", snapshot?.eth.orEmpty())
                    }
                    if (state.settings.watchSol) {
                        item { Spacer(Modifier.height(8.dp)); SectionHeader("Solana · KXSOL15M") }
                        marketsOrEmpty("sol", snapshot?.sol.orEmpty())
                    }
                    if (snapshot?.extra.orEmpty().isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)); SectionHeader("Extra crypto") }
                        marketsOrEmpty("extra", snapshot?.extra.orEmpty())
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.marketsOrEmpty(
    section: String,
    markets: List<MarketUiModel>
) {
    if (markets.isEmpty()) {
        item(key = "empty-$section") {
            Text(
                text = "No open markets",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    } else {
        items(markets, key = { "$section-${it.ticker}" }) { market ->
            MarketCard(market)
        }
    }
}

@Composable
private fun LiveSignalsCard(
    enabled: Boolean,
    connection: WsConnectionState,
    onToggle: (Boolean) -> Unit
) {
    val accent = when {
        !enabled -> TextSecondary
        connection == WsConnectionState.CONNECTED -> AccentGreen
        connection == WsConnectionState.RECONNECTING || connection == WsConnectionState.CONNECTING -> AccentOrange
        else -> AccentBlue
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
                    color = TextSecondary
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SignalRow(alert: SignalAlert) {
    val color = if (alert.deltaPp >= 0) AccentGreen else AccentOrange
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
        Text(alert.reason, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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
    val chipColor = when (chipState) {
        WsConnectionState.CONNECTED -> AccentGreen
        WsConnectionState.RECONNECTING, WsConnectionState.CONNECTING -> AccentOrange
        WsConnectionState.NEEDS_API_KEY -> AccentOrange
        WsConnectionState.ERROR -> AccentRed
        WsConnectionState.REST_FALLBACK -> AccentBlue
        WsConnectionState.IDLE -> TextSecondary
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
                color = TextSecondary
            )
            if (fromCache) {
                Text(
                    text = "CACHED",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentBlue
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
                    .background(AccentOrange.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentOrange,
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
                color = AccentOrange,
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
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        modelScoreLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = AccentBlue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(AccentBlue.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                text = "Open scorecard for hit rate, Brier, edge when right vs wrong",
                style = MaterialTheme.typography.labelMedium,
                color = AccentBlue,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(onClick = onOpenScorecard)
            )
        }
        Text(
            text = "CRYPTO ONLY · BTC/ETH/SOL 15m · approve-gated tickets · no auto-fire.",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
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
