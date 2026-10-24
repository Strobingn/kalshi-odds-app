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
import androidx.compose.ui.graphics.PathEffect
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
    Canvas(modifier.height(72.dp).fillMaxSize()) {
        val min = points.min()
        val max = points.max()
        val span = (max - min).coerceAtLeast(0.4f)
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        fun yOf(v: Float) = size.height * (1f - ((v - min) / span).coerceIn(0f, 1f))
        val path = Path()
        val area = Path()
        val last = points.lastIndex
        points.forEachIndexed { i, v ->
            val x = if (last == 0) 0f else size.width * i / last
            val y = yOf(v)
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(size.width, size.height)
        area.lineTo(0f, size.height)
        area.close()
        drawPath(area, color.copy(alpha = 0.16f))
        if (50f in min..max) {
            val y50 = yOf(50f)
            drawLine(
                color.copy(alpha = 0.45f),
                Offset(0f, y50),
                Offset(size.width, y50),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            )
        }
        drawPath(path = path, color = color, style = stroke)
        val lastY = yOf(points.last())
        drawCircle(color = color, radius = 3.5.dp.toPx(), center = Offset(size.width, lastY))
    }
}
