package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Copy and formatting for the home paper-cash reset. Paper only. */
object HomePaperCash {
    const val TITLE = "Paper cash"
    const val BUTTON = "Reset paper cash"
    const val DIALOG_TITLE = "Reset paper cash?"
    const val CONFIRM = "Reset"
    const val BODY =
        "Sets paper cash back to the configured start. The previous run is archived. " +
            "Nothing is deleted. No Kalshi order and no remote rows."

    fun cash(v: Double): String = if (!v.isFinite()) "—" else String.format(Locale.US, "$%.2f", v)
    fun pnl(v: Double): String = if (!v.isFinite()) "—" else String.format(Locale.US, "%+.2f", v)
}

@Composable
fun HomePaperCashCard(cashUsd: Double, pnlUsd: Double, onReset: () -> Unit) {
    val colors = DipTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(HomePaperCash.TITLE, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            "Paper only. Reset asks first, then archives the previous run.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Cash", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Text(HomePaperCash.cash(cashUsd), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("P&L", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Text(HomePaperCash.pnl(pnlUsd), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(HomePaperCash.BUTTON)
        }
    }
}
