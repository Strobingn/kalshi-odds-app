package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalp.ScalpAccount
import com.dirk.kalshiodds.signal.scalp.ScalpLabState
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/**
 * All 15 paper scalps. Read-only except Reset. Nothing on this screen
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
    val open = state.accounts.count { it.position != null }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Scalp lab",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "15 algorithms on the Bitcoin 15-minute market. Each starts at $100 and bets as much of that book as its rule wants, whenever it wants. " +
                    "Paper only — nothing here is sent to Kalshi.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                String.format(Locale.US, "Combined %+.2f · %d open", total, open),
                style = MaterialTheme.typography.labelLarge,
                color = if (total >= 0.0) colors.up else colors.down,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
            OutlinedButton(onClick = onReset, modifier = Modifier.padding(top = 4.dp)) {
                Text("Reset all 15 to $100")
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "$rank  ${account.name}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                String.format(Locale.US, "%+.2f", pnl),
                style = MaterialTheme.typography.labelLarge,
                color = if (pnl >= 0.0) colors.up else colors.down,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            String.format(
                Locale.US,
                "Cash $%.2f · %d trades · %d wins",
                account.cashUsd,
                account.trades,
                account.wins
            ),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Text(
            if (pos == null) {
                account.lastNote
            } else {
                String.format(
                    Locale.US,
                    "OPEN %s %d ct @ %.0f¢ · bid %.0f¢",
                    pos.side,
                    pos.contracts,
                    pos.entry * 100.0,
                    pos.lastBid * 100.0
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary
        )
        Text(
            account.rule,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
    }
}
