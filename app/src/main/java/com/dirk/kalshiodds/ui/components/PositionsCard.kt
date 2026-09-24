package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.AccentRed
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun PositionsCard(
    positions: List<LivePosition>,
    note: String?,
    onSell: (ticker: String, side: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentBlue.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Your positions",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Live Kalshi holdings (GET /portfolio/positions). Sell opens an approve-gated V2 reduce-only limit — never the retired v1 path.",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AccentOrange)
        }
        if (positions.isEmpty() && note == null) {
            Text(
                "No open positions.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        positions.forEach { pos ->
            PositionRow(pos, onSell)
        }
    }
}

@Composable
private fun PositionRow(pos: LivePosition, onSell: (String, String) -> Unit) {
    val pnl = pos.unrealizedPnlUsd
    val pnlColor = when {
        pnl == null -> TextSecondary
        pnl > 0 -> AccentGreen
        pnl < 0 -> AccentRed
        else -> AccentBlue
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AccentBlue.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                pos.displaySide,
                style = MaterialTheme.typography.labelMedium,
                color = AccentBlue,
                fontWeight = FontWeight.Bold
            )
            TimeLeftLabel(pos.closeTimeEpochMs, compact = true)
        }
        Text(
            pos.ticker,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        pos.title?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        Text(
            String.format(
                Locale.US,
                "%.2f sh · avg %s · bid %s · uP&L %s",
                pos.contracts,
                pos.avgCost?.let { String.format(Locale.US, "%.1f¢", it * 100) } ?: "—",
                pos.bestBid?.let { String.format(Locale.US, "%.1f¢", it * 100) } ?: "—",
                pnl?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"
            ),
            style = MaterialTheme.typography.labelMedium,
            color = pnlColor
        )
        Button(
            onClick = { onSell(pos.ticker, pos.side) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(44.dp)
        ) { Text("Sell") }
    }
}
