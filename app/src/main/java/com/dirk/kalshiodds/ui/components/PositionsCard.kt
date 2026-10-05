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
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.FieldShapes
import com.dirk.kalshiodds.ui.theme.FieldMetrics
import com.dirk.kalshiodds.ui.theme.fieldCard

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
            .fieldCard(colors.surface, colors.border)
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
                "Open Kalshi positions. Sell closes them at the current bid.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
        if (onViewHistory != null) {
            OutlinedButton(onClick = onViewHistory, modifier = Modifier.height(FieldMetrics.minTouch)) {
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
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.accentBlue.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                com.dirk.kalshiodds.ui.SignalCopy.callLabel(pos.side),
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentBlue,
                fontWeight = FontWeight.Bold
            )
            TimeLeftLabel(pos.closeTimeEpochMs, compact = true)
        }
        Text(
            com.dirk.kalshiodds.ui.PositionCopy.row(pos),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Button(
            shape = FieldShapes.button,
            onClick = { onSell(pos.ticker, pos.side) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(FieldMetrics.minTouch)
        ) { Text("Sell") }
    }
}
