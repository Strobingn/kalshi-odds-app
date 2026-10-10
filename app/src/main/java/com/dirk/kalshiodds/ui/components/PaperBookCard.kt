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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import java.util.Locale
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun PaperBookCard(
    paper: PaperBookState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
    onSell: ((ticker: String, side: String) -> Unit)? = null,
    onViewHistory: (() -> Unit)? = null,
    homeMode: Boolean = false
) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (!homeMode) {
                    Text(
                        "PAPER BOOK",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Unlimited-credit AI autopilot · visible-touch fills · never hits Kalshi",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                } else {
                    Text(
                        if (enabled) "Paper trading on" else "Paper trading off",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PaperStat("Synthetic", String.format(Locale.US, "$%.2f", paper.cashUsd), colors.textPrimary)
            PaperStat("Open", String.format(Locale.US, "$%.2f", paper.openStakeUsd), colors.accentOrange)
            val pnlColor = when {
                paper.realizedPnlUsd > 0 -> colors.textPrimary
                paper.realizedPnlUsd < 0 -> colors.textPrimary
                else -> colors.accentBlue
            }
            PaperStat("P&L", String.format(Locale.US, "%+.2f", paper.realizedPnlUsd), pnlColor)
        }
        if (!homeMode) {
            Text(
                if (enabled) {
                    "AI signals auto-fill their visible touch size with unlimited synthetic credit. Live Approve (below) is the only path that can place a real V2 order."
                } else {
                    "Paper autopilot is off. Flip the switch to auto-fill AI signals with unlimited synthetic credit."
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
        paper.lastMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset, modifier = Modifier.height(44.dp)) {
                Text("Reset paper to \$100,000")
            }
            if (onViewHistory != null) {
                OutlinedButton(onClick = onViewHistory, modifier = Modifier.height(44.dp)) {
                    Text("View all")
                }
            }
        }
        if (paper.fills.isEmpty()) {
            Text(
                if (enabled) "No paper fills yet — waiting for an AI hunter / signal."
                else "Ledger empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            )
        } else {
            Text(
                "Paper ledger (${paper.fills.size})",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            paper.fills.take(12).forEach { fill ->
                PaperLedgerRow(fill, onSell = if (!fill.settled) onSell else null)
            }
        }
    }
}

@Composable
private fun PaperStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PaperLedgerRow(
    fill: PaperFill,
    onSell: ((ticker: String, side: String) -> Unit)? = null
) {
    val colors = DipTheme.colors
    val status = when {
        !fill.settled -> "OPEN"
        fill.outcome == "void" -> "VOID"
        fill.won == true -> "WIN"
        else -> "LOSS"
    }
    val color = when (status) {
        "OPEN" -> colors.accentOrange
        "WIN" -> colors.textPrimary
        "LOSS" -> colors.textPrimary
        else -> colors.accentBlue
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "PAPER · $status · ${fill.source}",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Text(
                fill.pnlUsd?.let { String.format(Locale.US, "%+.2f", it) }
                    ?: String.format(Locale.US, "$%.2f", fill.stakeUsd),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            String.format(
                Locale.US,
                "%s %s · %d ct @ %s",
                com.dirk.kalshiodds.ui.SignalCopy.callLabel(fill.side),
                com.dirk.kalshiodds.ui.WindowLabel.of(fill.ticker),
                fill.contracts,
                com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(fill.limitPrice)
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (onSell != null && !fill.settled) {
            OutlinedButton(
                onClick = { onSell(fill.ticker, fill.side) },
                modifier = Modifier.padding(top = 6.dp).height(40.dp)
            ) { Text("Paper sell") }
        }
    }
}
