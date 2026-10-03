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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.components.AiFairLabel
import com.dirk.kalshiodds.ui.components.BidChart
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
    onBuyNo: (MarketUiModel) -> Unit
) {
    val colors = DipTheme.colors
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        com.dirk.kalshiodds.ui.WindowLabel.of(market.ticker, market.closeTimeEpochMs),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
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
            val quotes = com.dirk.kalshiodds.domain.MarketQuoteView.of(market)
            BidChart(
                points = points,
                modifier = Modifier.fillMaxWidth(),
                heightDp = 200,
                scrub = true,
                windowStartMs = market.closeTimeEpochMs?.minus(900_000L),
                windowEndMs = market.closeTimeEpochMs,
                strikeLabel = null,
                spotUsd = market.spotUsd,
                strikeUsd = market.floorStrike,
                spotHeightDp = 180,
                liveUpLabel = quotes.upChartLabel,
                liveDownLabel = quotes.downChartLabel
            )
            Text(
                "Orange = Coinbase/Binance spot with dashed TARGET. Green/red = UP/DOWN best bids. Drag to scrub.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 12.dp)
            )
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
