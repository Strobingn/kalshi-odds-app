package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.SignalCopy
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun SignalSummaryCard(
    card: SignalCopy.Card,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    var open by remember(card.title, card.details) { mutableStateOf(false) }
    val callColor = SideColor.of(SignalCopy.headline(card.call), colors)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            card.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            card.call,
            style = MaterialTheme.typography.titleMedium,
            color = callColor,
            fontWeight = FontWeight.Bold
        )
        Text(
            card.modelLine,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
        card.outcome?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (card.details != null) {
            Text(
                if (open) "Hide details" else "Details",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { open = !open }
            )
            if (open) {
                Text(
                    card.details,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
    }
}
