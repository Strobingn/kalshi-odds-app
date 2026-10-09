package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalper.ScalperSummary
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Home card for the paper scalper. Read-only: no buttons, nothing here can
 * place an order. Toggle / reset live in Settings.
 */
@Composable
fun ScalperCard(summary: ScalperSummary) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            summary.title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            summary.totalLine,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        summary.strategyLines.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        }
        summary.queueLines.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        Text(summary.workingLine, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(summary.note, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }
}
