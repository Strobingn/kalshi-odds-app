package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.d3.D3Copy
import com.dirk.kalshiodds.signal.d3.D3Phase
import com.dirk.kalshiodds.signal.d3.D3Snapshot
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun D3Card(
    snapshot: D3Snapshot,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, com.dirk.kalshiodds.ui.theme.FieldShapes.card)
            .background(colors.surface, com.dirk.kalshiodds.ui.theme.FieldShapes.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            D3Copy.TITLE,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            D3Copy.phaseLine(snapshot),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        when (snapshot.phase) {
            D3Phase.WAITING, D3Phase.CLOSED -> { }
            D3Phase.ACTIVE -> {
                if (snapshot.qualifying.isEmpty()) {
                    Text(
                        D3Copy.NO_STRIKES,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                } else {
                    snapshot.qualifying.take(8).forEach { signal ->
                        val sideColor = if (signal.isYes) colors.up else colors.down
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                signal.displaySide,
                                style = MaterialTheme.typography.bodyMedium,
                                color = sideColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                D3Copy.strikeLine(signal),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        Text(
            snapshot.todayLine,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Text(
            D3Copy.EVIDENCE,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
    }
}
