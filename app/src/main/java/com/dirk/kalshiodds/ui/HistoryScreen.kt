package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.history.HistoryAssembler
import com.dirk.kalshiodds.data.local.history.HistoryBet
import com.dirk.kalshiodds.data.local.history.HistorySession
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.ui.components.SignalSummaryCard
import com.dirk.kalshiodds.domain.MarketUiModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.FieldShapes
import com.dirk.kalshiodds.ui.theme.FieldMetrics
import com.dirk.kalshiodds.ui.theme.fieldCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpenMarket: (MarketUiModel) -> Unit
) {
    val colors = DipTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Bets", "Signals", "Sessions", "Settings", "Markets")
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text("History", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accentBlue
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i; viewModel.load(i) }, text = { Text(label) })
                }
            }
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (tab) {
                    0 -> {
                        item { BetFilters(state, viewModel) }
                        item { TotalsCard(state.totals) }
                        if (state.pnl.size >= 2) {
                            item { CumulativePnlChart(state.pnl) }
                        }
                        items(state.bets, key = { it.id }) { BetRow(it) }
                    }
                    1 -> items(state.signals, key = { it.id }) { row ->
                        SignalSummaryCard(signalCard(row))
                    }
                    2 -> items(state.sessions, key = { it.id }) { SessionRow(it) }
                    3 -> {
                        state.currentSettingsJson?.let { json ->
                            item {
                                OutlinedButton(shape = FieldShapes.button,
                                    onClick = { viewModel.restoreSettings(json) },
                                    modifier = Modifier.fillMaxWidth().height(FieldMetrics.minTouch)
                                ) { Text("Restore current snapshot") }
                            }
                        }
                        items(state.settings, key = { "${it.id}-${it.createdAtMs}-${it.key}" }) { SettingsRow(it, viewModel) }
                    }
                    else -> items(state.markets, key = { it.ticker + (it.closeMs ?: 0L) }) { row ->
                        MarketRow(row) { onOpenMarket(viewModel.marketModel(row)) }
                    }
                }
                if (state.hasMore) {
                    item {
                        OutlinedButton(shape = FieldShapes.button,
                            onClick = viewModel::loadMore,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Load more") }
                    }
                }
                val empty = when (tab) {
                    0 -> state.bets.isEmpty()
                    1 -> state.signals.isEmpty()
                    2 -> state.sessions.isEmpty()
                    3 -> state.settings.isEmpty()
                    else -> state.markets.isEmpty()
                }
                if (empty) {
                    item {
                        Text(
                            "Nothing stored yet — Approve, paper fills, and backfill land here.",
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BetFilters(state: HistoryUiState, viewModel: HistoryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow {
            HistoryAssembler.SourceFilter.entries.forEach { f ->
                FilterChip(
                    selected = state.source == f,
                    onClick = { viewModel.setSource(f) },
                    label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        ChipRow {
            HistoryAssembler.CoinFilter.entries.forEach { f ->
                FilterChip(
                    selected = state.coin == f,
                    onClick = { viewModel.setCoin(f) },
                    label = { Text(if (f == HistoryAssembler.CoinFilter.ALL) "All coins" else f.name) }
                )
            }
        }
        ChipRow {
            listOf(
                HistoryAssembler.DateFilter.ALL to "All time",
                HistoryAssembler.DateFilter.D1 to "24h",
                HistoryAssembler.DateFilter.D7 to "7d",
                HistoryAssembler.DateFilter.D30 to "30d"
            ).forEach { (f, label) ->
                FilterChip(
                    selected = state.date == f,
                    onClick = { viewModel.setDate(f) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun TotalsCard(t: HistoryAssembler.Totals) {
    val colors = DipTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .fieldCard(colors.surfaceAlt, colors.border)
            .padding(12.dp)
    ) {
        Text(
            String.format(
                Locale.US,
                "%d bets · stake $%.2f · P&L $%+.2f · %d won / %d lost / %d open",
                t.count, t.stakeUsd, t.pnlUsd, t.wins, t.losses, t.open
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}

@Composable
private fun CumulativePnlChart(points: List<Pair<Long, Double>>) {
    val colors = DipTheme.colors
    val color = colors.textPrimary
    Column(Modifier.fillMaxWidth()) {
        Text("Cumulative P&L", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 4.dp)) {
            val ys = points.map { it.second }
            val minY = (ys.minOrNull() ?: 0.0) - 1.0
            val maxY = (ys.maxOrNull() ?: 0.0) + 1.0
            val spanY = (maxY - minY).coerceAtLeast(1.0)
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = size.width * i / (points.size - 1).coerceAtLeast(1).toFloat()
                val y = size.height * (1f - ((p.second - minY) / spanY).toFloat()).coerceIn(0f, 1f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            val zero = size.height * (1f - ((0.0 - minY) / spanY).toFloat()).coerceIn(0f, 1f)
            drawLine(color.copy(alpha = 0.25f), Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1f)
        }
    }
}

@Composable
private fun BetRow(b: HistoryBet) {
    val colors = DipTheme.colors
    val resultColor = when (b.result.lowercase()) {
        "won" -> colors.textPrimary
        "lost" -> colors.textPrimary
        else -> colors.textSecondary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .fieldCard(colors.surfaceAlt, colors.border)
            .padding(10.dp)
    ) {
        Text(
            "${WindowLabel.of(b.ticker)}  ·  ${SignalCopy.callLabel(b.side)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        Text(
            buildString {
                if (b.contracts > 0) append("${b.contracts} ct @ ${com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(b.price)} · ")
                append(String.format(Locale.US, "stake $%.2f", b.stakeUsd))
                append(" · ${b.source}")
                b.winTargetUsd?.let { append(String.format(Locale.US, " · win-target $%.0f", it)) }
                append(" · ${b.result}")
                b.pnlUsd?.let { append(String.format(Locale.US, " · P&L $%+.2f", it)) }
            },
            style = MaterialTheme.typography.labelMedium,
            color = resultColor
        )
    }
}

internal fun signalCard(s: HistoryAssembler.SignalLine): SignalCopy.Card {
    val parsed = SignalCopy.parseAiVsMarket(s.note)
    return SignalCopy.card(
        ticker = s.ticker,
        side = s.side,
        modelYes = parsed?.first ?: s.fairPp,
        marketYes = parsed?.second ?: s.marketPp,
        settled = s.settled,
        fairYes = s.fairPp,
        details = buildString {
            s.note?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (isNotEmpty()) append('\n')
            append(String.format(Locale.US, "Stored Δ %+.1f pp (fair − mid, not the card edge)", s.edgePp))
        }
    )
}

@Composable
private fun SessionRow(s: HistorySession) {
    val colors = DipTheme.colors
    Text(
        String.format(
            Locale.US,
            "%s → %s  markets %d  signals %d  bets %d  %s",
            historyTime(s.startedAtMs),
            s.endedAtMs?.let { historyTime(it) } ?: "open",
            s.markets,
            s.signals,
            s.bets,
            s.pnlUsd?.let { String.format(Locale.US, "P&L $%.2f", it) } ?: ""
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSecondary,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsRow(c: SettingsChange, viewModel: HistoryViewModel) {
    val colors = DipTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${historyTime(c.createdAtMs)}  ${c.key}: ${c.oldValue} → ${c.newValue}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        if (c.snapshotJson != null) {
            OutlinedButton(shape = FieldShapes.button,
                onClick = { viewModel.restoreSettings(c.snapshotJson) },
                modifier = Modifier.padding(top = 4.dp).height(FieldMetrics.minTouch)
            ) { Text("Restore these settings") }
        }
    }
}

@Composable
private fun MarketRow(row: SettledWindowRow, onOpen: () -> Unit) {
    val colors = DipTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .fieldCard(colors.surfaceAlt, colors.border)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(WindowLabel.of(row.ticker, row.closeMs), fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Text(
                "${row.closeMs?.let { historyTime(it) } ?: "—"}  ${row.result.uppercase()}  " +
                    (row.strikeUsd?.let { String.format(Locale.US, "strike $%,.0f", it) } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
        Text("Chart", color = colors.accentBlue, fontWeight = FontWeight.Bold)
    }
}

internal fun historyTime(ms: Long): String {
    val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
    return fmt.format(Date(ms))
}
