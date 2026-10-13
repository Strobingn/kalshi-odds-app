package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalp.ScalpAccount
import com.dirk.kalshiodds.signal.scalp.ScalpLabState
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/**
 * All 20 paper scalps. Read-only except Reset. Nothing on this screen
 * can place a Kalshi order.
 */
@Composable
fun ScalpScreen(
    state: ScalpLabState,
    onReset: () -> Unit
) {
    val colors = DipTheme.colors
    val ranked = state.accounts.sortedByDescending { it.pnlUsd() }
    val total = state.accounts.sumOf { it.pnlUsd() }
    val cash = state.accounts.sumOf { it.cashUsd }
    val open = state.accounts.count { it.position != null }
    val ahead = state.accounts.count { it.pnlUsd() >= 0.0 }
    val trades = state.accounts.sumOf { it.trades }
    val wins = state.accounts.sumOf { it.wins }
    val winRate = if (trades > 0) 100.0 * wins / trades else 0.0
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Scalp lab",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "20 books · Bitcoin 15-minute · paper only",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                    }
                    Chip(
                        text = if (total >= 0.0) "AHEAD" else "BEHIND",
                        fg = if (total >= 0.0) colors.up else colors.down,
                        bg = if (total >= 0.0) colors.upContainer else colors.downContainer
                    )
                }
                Text(
                    String.format(Locale.US, "%+.2f", total),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (total >= 0.0) colors.up else colors.down,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "Combined cash $%,.0f", cash),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(String.format(Locale.US, "%d open", open), colors.accentBlue, colors.surfaceAlt)
                    Chip(String.format(Locale.US, "%d ahead", ahead), colors.up, colors.upContainer)
                    Chip(String.format(Locale.US, "%d behind", state.accounts.size - ahead), colors.down, colors.downContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(String.format(Locale.US, "%d trades", trades), colors.textPrimary, colors.surfaceAlt)
                    Chip(
                        String.format(Locale.US, "%.0f%% wins", winRate),
                        if (winRate >= 50.0) colors.up else colors.textSecondary,
                        colors.surfaceAlt
                    )
                }
                Text(
                    "Sells when the bid is 2¢ under the high of the trade. Nothing is held to settlement.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
                ScalpCharts(state.accounts)
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset all 20 to \$20,000")
                }
            }
        }
        itemsIndexed(ranked, key = { _, account -> account.id }) { index, account ->
            ScalpRow(rank = index + 1, account = account)
        }
    }
}

@Composable
private fun ScalpRow(rank: Int, account: ScalpAccount) {
    val colors = DipTheme.colors
    val pnl = account.pnlUsd()
    val pos = account.position
    val winRate = if (account.trades > 0) 100.0 * account.wins / account.trades else 0.0
    val pnlColor = if (pnl >= 0.0) colors.up else colors.down
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (pos != null) pnlColor.copy(alpha = 0.45f) else colors.border, RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(pnlColor.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$rank",
                        style = MaterialTheme.typography.labelLarge,
                        color = pnlColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (pos != null) {
                            Chip("OPEN", colors.up, colors.upContainer)
                        } else {
                            Chip("FLAT", colors.textSecondary, colors.surfaceAlt)
                        }
                        if (frozen) Chip("FROZEN", colors.accentOrange, colors.accentOrange.copy(alpha = 0.16f))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    String.format(Locale.US, "%+.2f", pnl),
                    style = MaterialTheme.typography.titleMedium,
                    color = pnlColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "cash $%,.0f", account.cashUsd),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (account.cashUsd < 0.0) colors.down else colors.textSecondary
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(
                String.format(Locale.US, "%d trades", account.trades),
                colors.textPrimary,
                colors.surfaceAlt
            )
            Chip(
                if (account.trades > 0) String.format(Locale.US, "%.0f%%", winRate) else "no trades",
                if (winRate >= 50.0 && account.trades > 0) colors.up else colors.textSecondary,
                colors.surfaceAlt
            )
            if (account.sizeScale < 0.99) {
                Chip(
                    String.format(Locale.US, "size %.0f%%", account.sizeScale * 100.0),
                    colors.accentOrange,
                    colors.accentOrange.copy(alpha = 0.16f)
                )
            }
        }
        ScalpSpark(account)
        if (pos != null) {
            val off = ((pos.peakBid - pos.lastBid) * 100.0).coerceAtLeast(0.0)
            Text(
                String.format(
                    Locale.US,
                    "%s %d ct · in %.0f¢ · bid %.0f¢ · high %.0f¢ · %.0f¢ off",
                    pos.side,
                    pos.contracts,
                    pos.entry * 100.0,
                    pos.lastBid * 100.0,
                    pos.peakBid * 100.0,
                    off
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                String.format(Locale.US, "Stake $%,.0f · sells at 2¢ off the high", pos.stakeUsd),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        } else {
            Text(
                account.lastNote.ifBlank { "Flat. Waiting for a quote." },
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (account.lesson.isNotEmpty()) {
            Text(
                account.lesson,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentOrange,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            account.rule,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Chip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
