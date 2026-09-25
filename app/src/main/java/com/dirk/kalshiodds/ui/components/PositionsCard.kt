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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.trade.LivePosition
import java.util.Locale
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun PositionsCard(
    positions: List<LivePosition>,
    note: String?,
    onSell: (ticker: String, side: String) -> Unit,
    onViewHistory: (() -> Unit)? = null,
    homeMode: Boolean = false
) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.accentBlue.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!homeMode) {
            Text(
                "Your positions",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Live Kalshi holdings (GET /portfolio/positions). Sell opens an approve-gated V2 reduce-only limit — never the retired v1 path.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
        if (onViewHistory != null) {
            OutlinedButton(onClick = onViewHistory, modifier = Modifier.height(44.dp)) {
                Text("View all")
            }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.accentOrange)
        }
        if (positions.isEmpty() && note == null) {
            Text(
                "No open positions.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }
        positions.forEach { pos ->
            PositionRow(pos, onSell)
        }
    }
}

@Composable
private fun PositionRow(pos: LivePosition, onSell: (String, String) -> Unit) {
    val colors = DipTheme.colors
    val pnl = pos.unrealizedPnlUsd
    val pnlColor = when {
        pnl == null -> colors.textSecondary
        pnl > 0 -> colors.textPrimary
        pnl < 0 -> colors.textPrimary
        else -> colors.accentBlue
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.accentBlue.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                pos.displaySide,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentBlue,
                fontWeight = FontWeight.Bold
            )
            TimeLeftLabel(pos.closeTimeEpochMs, compact = true)
        }
        Text(
            com.dirk.kalshiodds.ui.WindowLabel.of(pos.ticker, pos.closeTimeEpochMs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        pos.title?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
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
