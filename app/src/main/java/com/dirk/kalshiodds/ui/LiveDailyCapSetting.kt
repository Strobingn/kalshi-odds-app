package com.dirk.kalshiodds.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.dirk.kalshiodds.signal.trade.LiveDailyCap
import com.dirk.kalshiodds.signal.trade.LiveDailyCapStore
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Settings → Live Approve tickets → daily live cap. Reads and writes
 * [LiveDailyCapStore] directly; the Approve path reads the same store.
 */
object LiveDailyCapCopy {
    const val SLIDER_MAX_USD = 200f
    const val SLIDER_STEP_USD = 5f

    const val HELP =
        "Stops live buys once this much has been sent in a day (all-in, fees included). " +
            "Sells and paper are never blocked. Resets at midnight on this phone. 0 turns it off. " +
            "Every way of buying this market lost money on average in the trade-tape study, " +
            "so this caps what a bad day can cost."

    /** Snap a slider position to a whole [SLIDER_STEP_USD] step. */
    fun snap(value: Float): Double =
        (Math.round(value / SLIDER_STEP_USD) * SLIDER_STEP_USD).toDouble().coerceIn(0.0, SLIDER_MAX_USD.toDouble())
}

@Composable
fun LiveDailyCapSetting() {
    val colors = DipTheme.colors
    val context = LocalContext.current
    val store = remember(context) { runCatching { LiveDailyCapStore.get(context) }.getOrNull() } ?: return
    val state by store.state.collectAsState()
    Text(
        LiveDailyCap.settingsLabel(state, store.today()),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textPrimary,
        fontWeight = FontWeight.SemiBold
    )
    Slider(
        value = state.capUsd.toFloat().coerceIn(0f, LiveDailyCapCopy.SLIDER_MAX_USD),
        onValueChange = { store.setCapUsd(LiveDailyCapCopy.snap(it)) },
        valueRange = 0f..LiveDailyCapCopy.SLIDER_MAX_USD,
        steps = (LiveDailyCapCopy.SLIDER_MAX_USD / LiveDailyCapCopy.SLIDER_STEP_USD).toInt() - 1
    )
    Text(
        LiveDailyCapCopy.HELP,
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary
    )
}
