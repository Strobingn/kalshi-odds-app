package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.signal.scalp.ScalpStats
import com.dirk.kalshiodds.signal.scalp.ScalpTrade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scalping tab: every scalp round trip - open positions, realized PnL,
 * win rate, and the full trade history. Data comes from ScalpTradeLog
 * (SQLite-backed, survives restarts).
 */
@Composable
fun ScalpScreen(
    log: com.dirk.kalshiodds.signal.scalp.ScalpTradeLog,
    onBack: () -> Unit
) {
    val trades by log.trades.collectAsState()
    val open = trades.filter { it.exitTimeMs == null }
    val closed = trades.filter { it.exitTimeMs != null }
    val stats = statsOf(closed)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Scalping", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "DipHunter Scalper · every scalp entry and exit",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        StatsRow(stats)

        Spacer(Modifier.height(12.dp))
        if (open.isNotEmpty()) {
            SectionLabel("Open (${open.size})")
            open.forEach { TradeRow(it) }
            Spacer(Modifier.height(12.dp))
        }
        SectionLabel("History (${closed.size})")
        LazyColumn {
            items(closed, key = { it.id }) { TradeRow(it) }
        }
    }
}

private fun statsOf(closed: List<ScalpTrade>): ScalpStats {
    if (closed.isEmpty()) return ScalpStats()
    val pnls = closed.mapNotNull { it.pnlUsd }
    val wins = pnls.count { it > 0.0 }
    return ScalpStats(
        trades = pnls.size,
        wins = wins,
        pnlUsd = pnls.sum(),
        winRate = if (pnls.isNotEmpty()) wins.toDouble() / pnls.size else null,
        avgPnlUsd = pnls.takeIf { it.isNotEmpty() }?.average(),
        bestUsd = pnls.maxOrNull(),
        worstUsd = pnls.minOrNull()
    )
}

@Composable
private fun StatsRow(stats: ScalpStats) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = formatUsd(stats.pnlUsd),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (stats.pnlUsd >= 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Text("realized scalp PnL", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell("Trades", stats.trades.toString())
                StatCell("Wins", stats.wins.toString())
                StatCell("Win rate", stats.winRate?.let { "${(it * 100).toInt()}%" } ?: "—")
                StatCell("Avg", stats.avgPnlUsd?.let { formatUsd(it) } ?: "—")
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun TradeRow(t: ScalpTrade) {
    val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${if (t.side == "YES") "UP" else "DOWN"} · ${t.contracts} ct @ ${(t.entryPrice * 100).toInt()}¢",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    t.pnlUsd?.let { formatUsd(it) } ?: "open",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        t.pnlUsd == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        t.pnlUsd > 0 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            Text(
                "${t.ticker} · in ${fmt.format(Date(t.entryTimeMs))}" +
                    (t.exitTimeMs?.let { " → out ${fmt.format(Date(it))} @ ${(t.exitPrice ?: 0.0 * 100).toInt()}¢ · ${t.mode}" } ?: " · ${t.mode}"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatUsd(v: Double): String =
    String.format(Locale.US, "%+.2f", v)
