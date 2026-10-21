package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.ui.charts.ChartEmptyState
import com.dirk.kalshiodds.ui.charts.rich.RichChartCache
import com.dirk.kalshiodds.ui.charts.rich.RichChartLoader
import com.dirk.kalshiodds.ui.charts.rich.RichReportCharts
import com.dirk.kalshiodds.ui.charts.rich.RichTradeMath
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Paper-book charts for Live, Scorecard, History, and Data. The read runs on [Dispatchers.IO]. */
@Composable
fun PaperRichCharts(place: String) {
    val context = LocalContext.current
    var pack by remember { mutableStateOf<RichChartLoader.PaperPack?>(null) }
    var report by remember { mutableStateOf<RichTradeMath.Report?>(null) }
    LaunchedEffect(place) {
        while (isActive) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val app = context.applicationContext as? KalshiOddsApp ?: return@runCatching null
                    val snap = app.container.paper.book.snapshot()
                    val key = "paper|${snap.postResetFills.size}|${snap.fills.size}|${snap.openCount}|${snap.lifetimeRealizedPnlUsd}|${snap.cashUsd}"
                    val hit = RichChartCache.get(key) as? Pair<RichChartLoader.PaperPack, RichTradeMath.Report>
                    if (hit != null) return@runCatching hit
                    val fills = snap.postResetFills.ifEmpty { snap.fills }
                    val built = RichChartLoader.paper(fills)
                    val stats = RichTradeMath.report(built.trades, System.currentTimeMillis())
                    (built to stats).also { RichChartCache.put(key, it) }
                }.getOrNull()
            }
            if (loaded != null) {
                pack = loaded.first
                report = loaded.second
            } else if (report == null) {
                report = RichTradeMath.report(emptyList(), 0L)
                pack = RichChartLoader.PaperPack(emptyList(), false)
            }
            delay(15_000)
        }
    }
    val colors = DipTheme.colors
    Column(
        Modifier.fillMaxWidth().testTag("PAPER_RICH_CHARTS"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Paper charts · $place",
            color = colors.textPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Paper only. The autopilot and manual paper ledger, not live orders. Times are US Eastern.",
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        val shown = report
        val rows = pack
        if (shown == null || rows == null) {
            ChartEmptyState(text = "Loading charts…", height = 48.dp)
        } else {
            RichReportCharts(report = shown, compare = emptyList(), scalp = false, capped = rows.capped)
        }
    }
}
