package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.components.BidChart
import com.dirk.kalshiodds.ui.components.TimeLeftLabel
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.Bg
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartDetailScreen(
    market: MarketUiModel,
    points: List<BidPoint>,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(market.title.ifBlank { market.ticker }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Bg,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = AccentBlue
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(market.ticker, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            TimeLeftLabel(market.closeTimeEpochMs)
            Text(
                "Drag to scrub UP / DOWN bids over this 15m window",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
            BidChart(
                points = points,
                modifier = Modifier.fillMaxWidth(),
                heightDp = 280,
                scrub = true,
                windowStartMs = market.closeTimeEpochMs?.minus(900_000L),
                windowEndMs = market.closeTimeEpochMs,
                strikeLabel = listOfNotNull(
                    market.floorStrike?.let { String.format(Locale.US, "Strike $%,.0f", it) },
                    market.spotLabel,
                    market.digitalFairPp?.let { String.format(Locale.US, "Fair %.0f¢", it) }
                ).joinToString(" · ").ifBlank { null }
            )
            Text(
                "UP = YES best bid · DOWN = NO best bid. Fed from the live book plus stored snapshots; downsampled so the heap stays small.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "Nothing is sent to Kalshi from this screen.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
