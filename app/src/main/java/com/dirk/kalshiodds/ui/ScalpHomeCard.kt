package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Home-screen "Scalp" card — same chrome as the other home cards
 * (surface, 16 dp corners): net P&L + trade count + a 5-trade W/L
 * direction streak, tapping through to [ScalpScreen].
 */
@Composable
fun ScalpHomeCard(viewModel: ScalpViewModel, onOpen: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DipTheme.colors
    val stats = state.stats
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(ScalpCopy.TITLE, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            val net = stats?.totalNetCents
            if (net != null) {
                Text(
                    ScalpCopy.signedUsd(net),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (net >= 0) colors.up else colors.down,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (stats == null || !stats.hasTrades) {
            Text(
                if (state.loading) "Loading…" else ScalpCopy.EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Text(
                ScalpCopy.cardLine(stats.totalNetCents, stats.tradeCount),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            // Newest-first W/L marks of the last 5 closed trades.
            val streak = ScalpCopy.recentStreak(state.trades.map { it.pnlCents >= 0 })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                streak.split(" ").filter { it.isNotBlank() }.forEach { mark ->
                    val won = mark == "W"
                    Text(
                        mark,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (won) colors.up else colors.down,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(
                                (if (won) colors.up else colors.down).copy(alpha = 0.12f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                if (state.openPositions.isNotEmpty()) {
                    Text(
                        "${state.openPositions.size} open",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentOrange
                    )
                }
            }
        }
    }
}
