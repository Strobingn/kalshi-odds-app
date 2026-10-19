package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.absoluteValue

const val CHART_EMPTY_TEXT = "No data yet"

@Composable
fun ChartEmptyState(
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    text: String = CHART_EMPTY_TEXT
) {
    Box(
        modifier = modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = DipTheme.colors.textSecondary
        )
    }
}

@Composable
fun chartSignColor(v: Double): Color = with(DipTheme.colors) {
    when {
        v > 0.004 -> upButton
        v < -0.004 -> downButton
        else -> textSecondary
    }
}

/** Text-safe gain/loss ink (AA on cards); [chartSignColor] is for fills and lines. */
@Composable
fun chartSignTextColor(v: Double): Color = with(DipTheme.colors) {
    when {
        v > 0.004 -> up
        v < -0.004 -> down
        else -> textSecondary
    }
}

fun chartMoney(v: Double): String {
    val a = v.absoluteValue
    return if (a < 0.005) "+$0.00"
    else if (v > 0) "+" + String.format(Locale.US, "$%.2f", a)
    else "\u2212" + String.format(Locale.US, "$%.2f", a)
}

fun chartPct(rate: Double): String = String.format(Locale.US, "%.0f%%", rate * 100)
