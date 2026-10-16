package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme

internal fun windowTime(at: Long): String = executionTime(at)
internal fun markerName(kind: ScalpWindowChartData.Kind): String = ExecutionWindowData.Kind.valueOf(kind.name).label

@Composable
internal fun ScalpWindowChart(d: ScalpAlgoDetail, onWindow: (String) -> Unit = {}, onLive: () -> Unit = {}, onTrade: (String) -> Unit = {}) {
    val c = DipTheme.colors
    val data = d.chart
    SwarmCard("Live strategy window · 15 minutes") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(data.ticker.ifEmpty { "Waiting for a market" }, color = c.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onLive, enabled = !d.followLive) { Text(if (d.followLive) "Following live" else "Follow live") }
        }
        if (d.chartMarkets.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.chartMarkets, key = { it.ticker }) { market ->
                FilterChip(selected = market.ticker == data.ticker, onClick = { onWindow(market.ticker) }, label = { Text(market.label) })
            }
        }
        ExecutionWindowChart(data.executionWindow(), onTrade)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Window realized", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text(ScalpTabCopy.money(data.windowNet()), color = c.textPrimary, fontWeight = FontWeight.Bold) }
            Column { Text("Open P&L · both sides", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text(ScalpTabCopy.money(data.openNet()), color = c.textPrimary, fontWeight = FontWeight.Bold) }
            Column { Text("Round trips", color = c.textSecondary, style = MaterialTheme.typography.labelSmall); Text("${data.trades.count { it.state == com.dirk.kalshiodds.decision.ScalpState.CLOSED }}", color = c.textPrimary, fontWeight = FontWeight.Bold) }
        }
    }
}
