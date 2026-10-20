package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs
import kotlin.math.max

/** Grouped bars: UP and DOWN entry counts, then UP and DOWN net, one row per strategy. */
@Composable
fun StrategySideBars(groups: List<ChartGallery.SideGroup>, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .testTag("chart_side_bars")
            .semantics { contentDescription = "UP and DOWN entries and net by strategy" },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("UP and DOWN by strategy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("Entries and net after fees. Both sides on each row.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (groups.isEmpty()) {
            ChartEmptyState(height = 120.dp)
            return
        }
        val maxEntries = groups.maxOf { max(it.upEntries, it.downEntries) }.coerceAtLeast(1)
        val maxNet = groups.maxOf { max(abs(it.upNet), abs(it.downNet)) }.coerceAtLeast(0.01)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("UP", color = c.up, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("DOWN", color = c.down, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        groups.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(g.label, color = c.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("UP ${g.upEntries} · ${chartMoney(g.upNet)}", color = c.up, style = MaterialTheme.typography.labelSmall)
                    Text("DOWN ${g.downEntries} · ${chartMoney(g.downNet)}", color = c.down, style = MaterialTheme.typography.labelSmall)
                }
                Canvas(Modifier.fillMaxWidth().height(36.dp).padding(bottom = 4.dp)) {
                    val gap = 8.dp.toPx()
                    val half = (size.width - gap) / 2f
                    val rowH = 12.dp.toPx()
                    fun entryBar(up: Boolean, count: Int) {
                        val y = if (up) 2.dp.toPx() else rowH + 6.dp.toPx()
                        val w = half * count / maxEntries.toFloat()
                        if (w > 0f) drawRoundRect(if (up) c.upButton else c.downButton, Offset(0f, y), Size(w, rowH), CornerRadius(3.dp.toPx()))
                    }
                    entryBar(true, g.upEntries)
                    entryBar(false, g.downEntries)
                    val zero = half + gap + half / 2f
                    val room = half / 2f - 2.dp.toPx()
                    drawLine(c.border, Offset(zero, 0f), Offset(zero, size.height), 1.dp.toPx())
                    fun netBar(up: Boolean, value: Double) {
                        val y = if (up) 2.dp.toPx() else rowH + 6.dp.toPx()
                        val len = (room * (abs(value) / maxNet)).toFloat()
                        if (len <= 0f) return
                        val left = if (value >= 0) zero else zero - len
                        drawRoundRect(if (up) c.upButton else c.downButton, Offset(left, y), Size(len, rowH), CornerRadius(3.dp.toPx()))
                    }
                    netBar(true, g.upNet)
                    netBar(false, g.downNet)
                }
            }
        }
    }
}
