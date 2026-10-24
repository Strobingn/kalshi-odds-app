package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.DosMmEngine
import com.dirk.kalshiodds.ui.charts.ChartStroke
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Paper HFT snapshot ribbon. Reads [DosMmEngine.latest] and does not place orders. */
@Composable
internal fun HftMicroRibbon() {
    val c = DipTheme.colors
    val live by DosMmEngine.latest.collectAsState()
    val samples = remember { mutableStateListOf<DosMmEngine.Telemetry>() }
    LaunchedEffect(live) {
        if (samples.lastOrNull() != live) {
            samples.add(live)
            while (samples.size > 48) samples.removeAt(0)
        }
    }
    val ready = samples.count { it.ready } >= 2
    ArenaCard(Modifier.testTag("HFT_RIBBON"), accent = c.accentBlue) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Live HFT microstructure ribbon", color = c.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Student-t fair ${pct(live.fair)} · VAMP ${cents(live.vamp)} · HJB r ${cents(live.reservation)} · OFI5 ${signed(live.ofi5)}",
                    color = c.textSecondary,
                    style = ArenaUi.mono(MaterialTheme.typography.labelSmall),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ArenaBadge(if (live.mode.isBlank() || live.mode == "—") "HFT" else live.mode, ArenaTone.HFT)
        }
        if (!ready) {
            Text("Ribbon fills as the paper book publishes quotes.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            val fair = samples.map { it.fair }
            val vamp = samples.map { it.vamp }
            val res = samples.map { it.reservation }
            val all = fair + vamp + res
            val min = all.minOrNull() ?: 0.0
            val max = all.maxOrNull() ?: 1.0
            val span = (max - min).takeIf { it > 1e-6 } ?: 1e-6
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ArenaChrome.well())
            ) {
                fun drawShared(values: List<Double>, color: Color) {
                    if (values.size < 2) return
                    val path = Path()
                    val last = values.lastIndex.toFloat().coerceAtLeast(1f)
                    values.forEachIndexed { i, v ->
                        val x = 8.dp.toPx() + (size.width - 16.dp.toPx()) * i / last
                        val y = size.height - 8.dp.toPx() - ((v - min) / span).toFloat() * (size.height - 16.dp.toPx())
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = ChartStroke.SERIES_DP.dp.toPx(), cap = StrokeCap.Round))
                }
                drawShared(fair, c.up)
                drawShared(vamp, c.accentBlue)
                drawShared(res, c.spot)
            }
        }
        ChartLegend(
            listOf(
                c.up to "Student-t fair",
                c.accentBlue to "VAMP",
                c.spot to "HJB reservation"
            )
        )
    }
}

private fun pct(v: Double) = if (!v.isFinite()) "—" else String.format(Locale.US, "%.1f%%", v * 100.0)
private fun cents(v: Double) = if (!v.isFinite()) "—" else String.format(Locale.US, "%.1f¢", v * 100.0)
private fun signed(v: Double) = if (!v.isFinite()) "—" else String.format(Locale.US, "%+.2f", v)
