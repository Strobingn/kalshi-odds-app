package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.prediction.ledger.LedgerReport
import com.dirk.kalshiodds.ui.theme.ArenaColors
import kotlin.math.sqrt

/**
 * Calibration: what the model said (across) against how often YES happened
 * (up), one dot per 10-point bucket, sized by its calls; Kalshi's own price
 * in the second colour. On the dashed diagonal, a forecast means what it says.
 */
@Composable
fun CalibrationChart(model: List<LedgerReport.Bucket>, market: List<LedgerReport.Bucket>) {
    if (model.isEmpty() && market.isEmpty()) return
    val ac = ArenaColors.current()
    val modelColor = ac.series[0]
    val marketColor = ac.series[1]
    val most = (model + market).maxOfOrNull { it.n }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f).testTag("CALIBRATION_CHART")) {
            val pad = 6.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            fun x(p: Double) = pad + (p.coerceIn(0.0, 1.0) * w).toFloat()
            fun y(p: Double) = pad + ((1.0 - p.coerceIn(0.0, 1.0)) * h).toFloat()
            for (g in listOf(0.0, 0.5, 1.0)) {
                drawLine(ac.grid, Offset(x(g), y(0.0)), Offset(x(g), y(1.0)), strokeWidth = 1.dp.toPx())
                drawLine(ac.grid, Offset(x(0.0), y(g)), Offset(x(1.0), y(g)), strokeWidth = 1.dp.toPx())
            }
            drawLine(
                ac.baseline, Offset(x(0.0), y(0.0)), Offset(x(1.0), y(1.0)), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            )
            fun dots(buckets: List<LedgerReport.Bucket>, color: androidx.compose.ui.graphics.Color) {
                for (b in buckets) {
                    val r = (3.dp.toPx() + 6.dp.toPx() * sqrt(b.n.toFloat() / most)).coerceAtMost(10.dp.toPx())
                    val c = Offset(x(b.meanForecast), y(b.actualYes))
                    drawCircle(ac.surface, radius = r + 1.5.dp.toPx(), center = c)
                    drawCircle(color, radius = r, center = c)
                }
            }
            dots(market, marketColor)
            dots(model, modelColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DotKey(modelColor)
            Muted("Model")
            DotKey(marketColor)
            Muted("Kalshi price")
            Muted("· across: said · up: happened")
        }
    }
}
