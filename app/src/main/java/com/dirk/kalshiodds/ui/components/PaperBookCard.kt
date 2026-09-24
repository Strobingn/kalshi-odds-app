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
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.AccentRed
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun PaperBookCard(
    paper: PaperBookState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
    onSell: ((ticker: String, side: String) -> Unit)? = null,
    onViewHistory: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, AccentGreen.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "PAPER BOOK",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Start / reset $100 · $5 per AI fill · never hits Kalshi",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PaperStat("Cash", String.format(Locale.US, "$%.2f", paper.cashUsd), AccentGreen)
            PaperStat("Open", String.format(Locale.US, "$%.2f", paper.openStakeUsd), AccentOrange)
            val pnlColor = when {
                paper.realizedPnlUsd > 0 -> AccentGreen
                paper.realizedPnlUsd < 0 -> AccentRed
                else -> AccentBlue
            }
            PaperStat("P&L", String.format(Locale.US, "%+.2f", paper.realizedPnlUsd), pnlColor)
        }
        Text(
            if (enabled) {
                "AI hunter / LiveCall signals auto-log a paper fill here. Live Approve (below) is the only path that can place a real V2 order."
            } else {
                "Paper trading is off. Flip the switch to auto-log $5 AI fills on this $100 book."
            },
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        paper.lastMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AccentGreen, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset, modifier = Modifier.height(44.dp)) {
                Text("Reset paper to $100")
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
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AccentGreen.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            )
        } else {
            Text(
                "Paper ledger (${paper.fills.size})",
                style = MaterialTheme.typography.labelMedium,
                color = AccentGreen,
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
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PaperLedgerRow(
    fill: PaperFill,
    onSell: ((ticker: String, side: String) -> Unit)? = null
) {
    val status = when {
        !fill.settled -> "OPEN"
        fill.outcome == "void" -> "VOID"
        fill.won == true -> "WIN"
        else -> "LOSS"
    }
    val color = when (status) {
        "OPEN" -> AccentOrange
        "WIN" -> AccentGreen
        "LOSS" -> AccentRed
        else -> AccentBlue
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AccentGreen.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
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
                "%s %s · %d ct @ %.0f¢",
                fill.displaySide,
                fill.ticker,
                fill.contracts,
                fill.limitPrice * 100
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
