package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.signal.scalp.BankrollPoint
import com.dirk.kalshiodds.signal.scalp.ClosedTrade
import com.dirk.kalshiodds.signal.scalp.ScalpPosition
import com.dirk.kalshiodds.signal.scalp.ScalpStats
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScalpScreen(viewModel: ScalpViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScalpScreen(state = state, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScalpScreen(state: ScalpScreenState, onBack: () -> Unit) {
    val colors = DipTheme.colors
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(ScalpCopy.SCREEN_TITLE) },
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    ScalpCopy.SCREEN_SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
            state.error?.let { err ->
                item {
                    Text(
                        "${ScalpCopy.LOADING_FAILED}: $err",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.accentOrange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.accentOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    )
                }
            }
            val stats = state.stats
            when {
                state.loading && stats == null -> item {
                    ScalpSectionCard {
                        Text(
                            "Loading…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary
                        )
                    }
                }
                stats == null || !stats.hasTrades -> item {
                    ScalpSectionCard {
                        Text(
                            ScalpCopy.EMPTY,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                else -> {
                    item { ScalpSummaryCard(stats) }
                    if (stats.bankroll.size >= 2) {
                        item { ScalpBankrollCard(stats.bankroll) }
                    }
                    if (state.openPositions.isNotEmpty()) {
                        item { ScalpOpenPositionsCard(state.openPositions) }
                    }
                    if (stats.byStrategy.isNotEmpty()) {
                        item {
                            ScalpSectionCard(ScalpCopy.BY_STRATEGY) {
                                stats.byStrategy.values.forEach { s ->
                                    ScalpBreakdownRow(
                                        label = ScalpCopy.strategyLabel(s.strategy),
                                        detail = ScalpCopy.winLossLabel(s.wins, s.losses) +
                                            " · ${s.tradeCount} trades",
                                        netCents = s.netCents
                                    )
                                }
                            }
                        }
                    }
                    if (stats.byCoin.isNotEmpty()) {
                        item {
                            ScalpSectionCard(ScalpCopy.BY_COIN) {
                                stats.byCoin.values.forEach { c ->
                                    ScalpBreakdownRow(
                                        label = c.coin,
                                        detail = ScalpCopy.winLossLabel(c.wins, c.losses) +
                                            " · ${c.tradeCount} trades",
                                        netCents = c.netCents
                                    )
                                }
                            }
                        }
                    }
                    if (stats.byHourUtc.isNotEmpty()) {
                        item {
                            ScalpSectionCard(ScalpCopy.BY_HOUR) {
                                stats.byHourUtc.values.forEach { h ->
                                    ScalpBreakdownRow(
                                        label = String.format("%02d:00", h.hourUtc),
                                        detail = ScalpCopy.winLossLabel(h.wins, h.losses) +
                                            " · ${h.tradeCount} trades",
                                        netCents = h.netCents
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            ScalpCopy.ALL_TRADES,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                    }
                    val keyed = LazyListKeys.keyed(state.trades) { it.positionId }
                    items(keyed, key = { it.key }) { row -> ScalpTradeRow(row.value) }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ScalpSectionCard(
    title: String? = null,
    content: @Composable () -> Unit
) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        title?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
private fun ScalpSummaryCard(stats: ScalpStats) {
    val colors = DipTheme.colors
    val netColor = if (stats.totalNetCents >= 0) colors.up else colors.down
    ScalpSectionCard {
        Text(ScalpCopy.TOTAL_NET, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            ScalpCopy.signedUsd(stats.totalNetCents),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = netColor
        )
        Text(
            "${ScalpCopy.usd(stats.grossWonCents)} won vs ${ScalpCopy.usd(-stats.grossLostCents)} lost · " +
                "${ScalpCopy.usd(stats.totalFeesCents)} fees",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Text(
            ScalpCopy.FEES_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ScalpStat(ScalpCopy.WIN_LOSS, ScalpCopy.winLossLabel(stats.winCount, stats.lossCount))
            ScalpStat(ScalpCopy.TRADES, "${stats.tradeCount}")
            ScalpStat(ScalpCopy.WIN_RATE, ScalpCopy.winRateLabel(stats.winRate))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ScalpStat(ScalpCopy.AVG_WIN, if (stats.winCount > 0) ScalpCopy.signedUsd(kotlin.math.round(stats.avgWinCents).toLong()) else ScalpCopy.EM_DASH, colors.up)
            ScalpStat(ScalpCopy.AVG_LOSS, if (stats.lossCount > 0) ScalpCopy.signedUsd(kotlin.math.round(stats.avgLossCents).toLong()) else ScalpCopy.EM_DASH, colors.down)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ScalpStat(ScalpCopy.BIGGEST_WIN, if (stats.maxWinCents > 0) ScalpCopy.signedUsd(stats.maxWinCents) else ScalpCopy.EM_DASH, colors.up)
            ScalpStat(ScalpCopy.BIGGEST_LOSS, if (stats.maxLossCents < 0) ScalpCopy.signedUsd(stats.maxLossCents) else ScalpCopy.EM_DASH, colors.down)
        }
    }
}

@Composable
private fun ScalpStat(label: String, value: String, valueColor: Color? = null) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Cumulative paper-bankroll line. Compose Canvas only (same pattern as the
 * scorecard's CumulativePnlCard) — the chart/ pipeline is bid-price oriented
 * (BidPoint up/down series), which doesn't fit a single-bankroll time series.
 * Time-scaled x-axis, min/max labels.
 */
@Composable
private fun ScalpBankrollCard(points: List<BankrollPoint>) {
    val colors = DipTheme.colors
    val range = ScalpCopy.bankrollRange(points)
    val last = points.last().bankrollCents
    val seedCents = com.dirk.kalshiodds.signal.scalp.ScalpStatsMath.PAPER_START_BANKROLL_CENTS
    val lineColor = if (last >= seedCents) colors.up else colors.down
    ScalpSectionCard(ScalpCopy.BANKROLL_TITLE) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                ScalpCopy.bankrollLabel(last),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (last >= seedCents) colors.up else colors.down
            )
            range?.let { (min, max) ->
                Text(
                    "${ScalpCopy.bankrollLabel(min)} – ${ScalpCopy.bankrollLabel(max)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp)) {
            if (points.size < 2 || range == null) return@Canvas
            val (minC, maxC) = range
            val minT = points.first().atMs
            val maxT = points.last().atMs
            val spanC = (maxC - minC).coerceAtLeast(1L)
            val spanT = (maxT - minT).coerceAtLeast(1L)
            fun xOf(p: BankrollPoint) = size.width * ((p.atMs - minT).toFloat() / spanT)
            fun yOf(p: BankrollPoint) =
                (size.height * (1f - (p.bankrollCents - minC).toFloat() / spanC)).coerceIn(0f, size.height)

            // Zero-drift reference: the $100 paper stake line.
            val seedY = (size.height * (1f - (seedCents - minC).toFloat() / spanC)).coerceIn(0f, size.height)
            drawLine(colors.textSecondary.copy(alpha = 0.25f), Offset(0f, seedY), Offset(size.width, seedY), strokeWidth = 1f)

            val path = Path()
            points.forEachIndexed { i, p ->
                val x = xOf(p)
                val y = yOf(p)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ScalpOpenPositionsCard(positions: List<ScalpPosition>) {
    val colors = DipTheme.colors
    ScalpSectionCard(ScalpCopy.OPEN_POSITIONS) {
        positions.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${ScalpCopy.strategyLabel(p.strategy)} · ${p.ticker}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary
                )
                Text(
                    "${p.contracts} ct @ ${p.entryPriceCents}¢ since ${ScalpCopy.hhMmUtc(p.entryTimeMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun ScalpBreakdownRow(label: String, detail: String, netCents: Long) {
    val colors = DipTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Text(
            ScalpCopy.signedUsd(netCents),
            style = MaterialTheme.typography.bodyMedium,
            color = if (netCents >= 0) colors.up else colors.down,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ScalpTradeRow(trade: ClosedTrade) {
    val colors = DipTheme.colors
    val win = trade.pnlCents >= 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                ScalpCopy.strategyLabel(trade.strategy),
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentBlue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(colors.accentBlue.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Text(
                ScalpCopy.signedUsd(trade.pnlCents.toLong()),
                style = MaterialTheme.typography.bodyMedium,
                color = if (win) colors.up else colors.down,
                fontWeight = FontWeight.Bold
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${trade.entryPriceCents}¢ → ${trade.exitPriceCents}¢ · ${trade.contracts} ct",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
            Text(
                ScalpCopy.reasonLabel(trade.reason),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
        Text(
            ScalpCopy.tradeTimeSpan(trade.entryTimeMs, trade.exitTimeMs) + " UTC · ${trade.ticker}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
    }
}
