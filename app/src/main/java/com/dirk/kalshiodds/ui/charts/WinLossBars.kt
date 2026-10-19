package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme

data class WinLossRow(
    val label: String,
    val wins: Int,
    val losses: Int
)

@Composable
fun WinLossBars(
    rows: List<WinLossRow>,
    modifier: Modifier = Modifier
) {
    val c = DipTheme.colors
    val filtered = rows.filter { it.wins + it.losses > 0 }
    if (filtered.isEmpty()) {
        ChartEmptyState(modifier)
        return
    }

    val maxTotal = filtered.maxOf { it.wins + it.losses }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filtered.forEach { row ->
            val total = row.wins + row.losses
            val winPct = if (total > 0) row.wins.toDouble() / total else 0.0

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${row.wins}-${row.losses} · ${chartPct(winPct)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .semantics {
                        contentDescription = "${row.label}: ${row.wins} wins, ${row.losses} losses"
                    }
            ) {
                val barWidth = size.width * (total.toFloat() / maxTotal)
                val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                drawRoundRect(color = c.surfaceAlt, size = size, cornerRadius = radius)
                val winsWidth = barWidth * (row.wins.toFloat() / total)
                val lossesWidth = barWidth - winsWidth
                if (row.wins > 0) drawRoundRect(color = c.upButton, size = Size(winsWidth, size.height), cornerRadius = radius)
                if (row.losses > 0) drawRoundRect(color = c.downButton, topLeft = Offset(winsWidth, 0f),
                    size = Size(lossesWidth, size.height), cornerRadius = radius)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendSwatch(c.upButton)
            Text("Wins", style = MaterialTheme.typography.labelSmall, color = c.textSecondary,
                modifier = Modifier.padding(end = 12.dp))
            LegendSwatch(c.downButton)
            Text("Losses", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun LegendSwatch(color: Color) {
    Canvas(Modifier.size(8.dp)) { drawRoundRect(color = color, cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())) }
}
