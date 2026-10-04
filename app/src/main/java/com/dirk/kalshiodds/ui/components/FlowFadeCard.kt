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
import com.dirk.kalshiodds.signal.flowfade.FlowFadeSummary
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Home card for the paper-only flow-fade tracker. Read-only: no buttons,
 * nothing here can place an order.
 */
@Composable
fun FlowFadeCard(state: LateFavoriteState) {
    val colors = DipTheme.colors
    val s = FlowFadeSummary.of(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            s.title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(s.recordLine, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        Text(s.pnlLine, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        Text(s.worseLine, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(s.openLine, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(s.note, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }
}
