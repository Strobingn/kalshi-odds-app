package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.decision.AiDirection
import com.dirk.kalshiodds.decision.GbmModel
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.delay

/** Live wrapper: ticks every second off the scalper's marks + on-phone GBM, so it updates and rolls with the window. */
@Composable
fun AiPredictionBannerLive(modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as? KalshiOddsApp)?.let { runCatching { it.container }.getOrNull() }
    val book = container?.scalp
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val marks = book?.marks?.collectAsState()?.value ?: emptyMap()
    val f = remember(marks, now) {
        val q = AiDirection.current(marks, now)
        AiDirection.forecast(q, now) { GbmModel.pSide(it, "YES", book?.dm60(it.ticker.uppercase(), it.nowMs) ?: 0.0) }
    }
    AiPredictionBanner(f, modifier)
}

@Composable
fun AiPredictionBanner(f: AiDirection.Forecast, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    val (tint, bg, icon) = when (f.call) {
        AiDirection.Call.UP -> Triple(c.upButton, c.upContainer, Icons.Filled.ArrowUpward)
        AiDirection.Call.DOWN -> Triple(c.downButton, c.downContainer, Icons.Filled.ArrowDownward)
        AiDirection.Call.NONE -> Triple(c.textSecondary, c.surfaceAlt, Icons.Filled.Remove)
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(bg, RoundedCornerShape(14.dp)).padding(12.dp).testTag("AI_PREDICTION_BANNER"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, AiDirection.label(f), tint = tint, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(AiDirection.label(f), color = if (f.call == AiDirection.Call.NONE) c.textSecondary else tint,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(f.confidence?.let { "${Math.round(it * 100)}% confidence" }, AiDirection.timeLeft(f.secondsLeft)).joinToString(" · "),
                color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
            Text("BTC 15-min window · on-phone model (paper)", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}
