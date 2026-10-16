package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.components.AiFairLabel
import com.dirk.kalshiodds.ui.components.MarketAskHero
import com.dirk.kalshiodds.ui.components.PastSettlementsRow
import com.dirk.kalshiodds.ui.components.TapeConflictBanner
import com.dirk.kalshiodds.ui.components.TargetNowLine
import com.dirk.kalshiodds.ui.components.TimeLeftLabel
import com.dirk.kalshiodds.ui.components.UpDownBuyButtons
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartDetailScreen(
    market: MarketUiModel,
    points: List<BidPoint>,
    onBack: () -> Unit,
    onBuyYes: (MarketUiModel) -> Unit,
    onBuyNo: (MarketUiModel) -> Unit,
    paper: com.dirk.kalshiodds.signal.paper.PaperBookState = com.dirk.kalshiodds.signal.paper.PaperBookState(),
    viewModel: OddsViewModel? = null
) {
    val colors = DipTheme.colors
    val close = market.closeTimeEpochMs ?: 0L
    val chartFlow = remember(viewModel, market.ticker, close) { viewModel?.paperWindowChart(market.ticker, close) }
    val initial = remember(market.ticker, close, points, paper) {
        PaperWindowChartData.build(market.ticker, close, System.currentTimeMillis(), points, paper)
    }
    val chart = if (chartFlow != null) {
        val current by chartFlow.collectAsStateWithLifecycle(initialValue = initial)
        current
    } else initial
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(com.dirk.kalshiodds.ui.WindowLabel.of(market.ticker, market.closeTimeEpochMs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TimeLeftLabel(market.closeTimeEpochMs, pill = true)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accentBlue,
                    actionIconContentColor = colors.accentBlue
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Details · ${market.ticker}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Spacer(Modifier.weight(1f))
            }
            TargetNowLine(market, Modifier.padding(top = 8.dp))
            TapeConflictBanner(market, Modifier.padding(top = 8.dp))
            MarketAskHero(market, Modifier.padding(top = 12.dp))
            AiFairLabel(market, Modifier.padding(top = 8.dp))
            PastSettlementsRow(market.pastSettlements, Modifier.padding(top = 8.dp, bottom = 12.dp))
            Text("Live paper window · 15 minutes", color = colors.textPrimary,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ExecutionWindowChart(chart)
            val fills = PaperWindowChartData.fills(paper, market.ticker)
            Text(java.lang.String.format(java.util.Locale.US, "Window realized $%+.2f · %d ct open · fees $%.2f",
                fills.sumOf { it.pnlUsd ?: 0.0 }, fills.filter { !it.settled }.sumOf { it.contracts },
                fills.sumOf { it.entryFeeUsd + it.exitFeeUsd }), color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            if (fills.any { it.settled && it.exitAtMs == null }) Text(
                "Older exits have no recorded execution time. Their P&L is included; exit markers start with this update.",
                color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text("Spot / target", color = colors.textPrimary, modifier = Modifier.padding(top = 16.dp))
            com.dirk.kalshiodds.ui.components.MarketSpotChart(chart.points, market.spotUsd, market.floorStrike,
                chart.startMs, chart.closeMs)
            UpDownBuyButtons(
                market = market,
                onBuyYes = { onBuyYes(market) },
                onBuyNo = { onBuyNo(market) },
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "Approve on the ticket is the only path that can place a real V2 order.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

