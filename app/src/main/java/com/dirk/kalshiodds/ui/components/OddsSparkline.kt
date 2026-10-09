package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Lightweight YES-mid sparkline. No charting library — Path/Canvas only.
 * [points] are mid odds in percent (0–100), oldest → newest.
 */
@Composable
fun OddsSparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = DipTheme.colors.accentBlue
) {
    val colors = DipTheme.colors
    if (points.size < 2) {
        Box(modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                if (points.isEmpty()) "Chart warming up" else "Need 2 prints",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
        return
    }
    Canvas(modifier.height(36.dp).fillMaxSize()) {
        val min = points.min()
        val max = points.max()
        val span = (max - min).coerceAtLeast(0.4f)
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val path = Path()
        val last = points.lastIndex
        points.forEachIndexed { i, v ->
            val x = if (last == 0) 0f else size.width * i / last
            val y = size.height * (1f - ((v - min) / span).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = stroke)
        val lastX = size.width
        val lastY = size.height * (1f - ((points.last() - min) / span).coerceIn(0f, 1f))
        drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
    }
}
