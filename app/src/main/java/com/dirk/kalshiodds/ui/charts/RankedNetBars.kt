package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs

data class NetBar(val label: String, val value: Double)

/** Diverging net-$ bars, ranked best first unless [ranked] is false. Gains grow right in green, losses left in red. */
@Composable
fun RankedNetBars(bars: List<NetBar>, modifier: Modifier = Modifier, maxBars: Int = 10, labelWidthDp: Int = 110, ranked: Boolean = true) {
    val c = DipTheme.colors
    val finite = bars.filter { it.value.isFinite() }
    val shown = (if (ranked) finite.sortedByDescending { it.value } else finite).take(maxBars)
    if (shown.isEmpty()) { ChartEmptyState(modifier); return }
    val maxAbs = shown.maxOf { abs(it.value) }.coerceAtLeast(0.01)
    val allPos = shown.all { it.value >= 0 }; val allNeg = shown.all { it.value <= 0 }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEachIndexed { i, bar ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ranked) "${i + 1}. ${bar.label}" else bar.label, Modifier.width(labelWidthDp.dp), style = MaterialTheme.typography.labelMedium,
                    color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(1f).height(16.dp).semantics { contentDescription = "${bar.label}: ${chartMoney(bar.value)}" }) {
                    val zero = when { allPos -> 0f; allNeg -> size.width; else -> size.width / 2f }
                    val room = if (allPos || allNeg) size.width else size.width / 2f
                    val len = (abs(bar.value) / maxAbs * room).toFloat()
                    val inset = 2.dp.toPx()
                    val left = if (bar.value >= 0) zero else zero - len
                    if (len > 0f) drawRoundRect(if (bar.value >= 0) c.upButton else c.downButton, Offset(left, inset),
                        Size(len, size.height - 2 * inset), CornerRadius(3.dp.toPx()))
                    drawLine(c.border, Offset(zero, 0f), Offset(zero, size.height), strokeWidth = 1.dp.toPx())
                }
                Text(chartMoney(bar.value), Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall,
                    color = chartSignTextColor(bar.value), textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}
