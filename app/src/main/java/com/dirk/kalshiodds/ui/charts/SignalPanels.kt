package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs

/**
 * Live model bars for the coin detail screen. A row is omitted when the
 * engine has no number for it. Nothing here is a placeholder.
 */
object SignalPanels {
    data class Bar(val label: String, val value: Double, val text: String, val up: Boolean)

    fun telemetry(m: MarketUiModel): List<Bar> = listOfNotNull(
        m.aiConfidence?.takeIf { it.isFinite() }?.let { Bar("Confidence", it, pct(it), it >= 0.5) },
        m.pFill?.takeIf { it.isFinite() }?.let { Bar("P(fill)", it, pct(it), it >= 0.5) },
        m.uncertainty?.takeIf { it.isFinite() }?.let { Bar("Uncertainty", it.coerceIn(0.0, 1.0), String.format(Locale.US, "%.2f", it), it < 0.5) },
        m.midVolPp?.takeIf { it.isFinite() }?.let { Bar("Mid vol", (it / 5.0).coerceIn(0.0, 1.0), String.format(Locale.US, "%.1fpp", it), true) }
    )

    fun drivers(m: MarketUiModel): List<Bar> {
        val keys = listOf("ai", "flow", "imbalance", "velocity", "depth", "spot")
        return keys.mapNotNull { key ->
            m.featureDevs[key]?.takeIf { it.isFinite() }?.let { v ->
                Bar(key, v, String.format(Locale.US, "%+.1fpp", v), v >= 0.0)
            }
        }
    }

    fun ensemble(m: MarketUiModel): List<Bar> = listOfNotNull(
        prob("Mkt mid", m.yesProbabilityPercent),
        prob("FV blend", m.aiYesPercent),
        prob("Digital FV", m.digitalFairPp),
        prob("MLP", m.mlpPp),
        prob("GBM", m.gbmPp),
        prob("MM shadow", m.mmShadowPp)
    )

    private fun prob(label: String, pp: Double?): Bar? {
        val v = pp?.takeIf { it.isFinite() } ?: return null
        return Bar(label, (v / 100.0).coerceIn(0.0, 1.0), String.format(Locale.US, "%.0f¢", v), v >= 50.0)
    }

    private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v.coerceIn(0.0, 1.0) * 100.0)
}

@Composable
fun SignalModelPanels(market: MarketUiModel, modifier: Modifier = Modifier) {
    val telemetry = SignalPanels.telemetry(market)
    val drivers = SignalPanels.drivers(market)
    val ensemble = SignalPanels.ensemble(market)
    if (telemetry.isEmpty() && drivers.isEmpty() && ensemble.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        if (telemetry.isNotEmpty()) ChartBlock("Signal telemetry", "Live model. A missing input is hidden. Times on this screen are ET.") {
            LevelBars(telemetry, fraction = true)
        }
        if (drivers.isNotEmpty()) ChartBlock("Feature drivers", "Δ vs mid, from the live blend. Hidden when that driver was not computed.") {
            LevelBars(drivers, fraction = false)
        }
        if (ensemble.isNotEmpty()) ChartBlock("Model ensemble", "UP probability from each live model. Hidden when that model has no value.") {
            LevelBars(ensemble, fraction = true)
        }
    }
}

@Composable
private fun LevelBars(rows: List<SignalPanels.Bar>, fraction: Boolean) {
    val c = DipTheme.colors
    val span = if (fraction) 1.0 else rows.maxOf { abs(it.value) }.coerceAtLeast(0.1)
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, Modifier.width(92.dp), color = c.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Box(Modifier.weight(1f).height(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(c.border)) {
                    val frac = if (fraction) row.value.coerceIn(0.0, 1.0) else (abs(row.value) / span).coerceIn(0.0, 1.0)
                    Box(Modifier.fillMaxWidth(frac.toFloat()).height(8.dp).background(if (row.up) c.up else c.down))
                }
                Text(row.text, Modifier.width(64.dp), color = if (row.up) c.up else c.down, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}
