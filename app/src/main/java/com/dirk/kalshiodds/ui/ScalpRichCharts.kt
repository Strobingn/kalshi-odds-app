package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpTrade
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

/**
 * Extra scalp charts. Reads the registry at runtime and the trade table on [Dispatchers.IO].
 * [seed] is the in-memory page already on screen, used when the database read is unavailable.
 */
@Composable
internal fun ScalpRichCharts(focusScalperId: String? = null, seed: List<ScalpTrade> = emptyList()) {
    val context = LocalContext.current
    val registry = ScalpModels.ALL
    val seedKey = "${seed.size}:${seed.firstOrNull()?.id}:${seed.lastOrNull()?.closedAtMs}"
    var pack by remember(focusScalperId, seedKey) { mutableStateOf<RichChartLoader.ScalpPack?>(null) }
    LaunchedEffect(focusScalperId, seedKey) {
        while (isActive) {
            val bucket = System.currentTimeMillis() / 30_000L
            val cacheKey = "scalp:${focusScalperId ?: "all"}:$bucket"
            val cached = RichChartCache.get(cacheKey) as? RichChartLoader.ScalpPack
            pack = cached ?: withContext(Dispatchers.IO) {
                runCatching {
                    val app = context.applicationContext as? KalshiOddsApp ?: return@runCatching null
                    val codes = registry.map { it.code }
                    val ids = RichChartLoader.scalpQueryIds(codes, focusScalperId)
                    val limit = RichChartLoader.perQueryLimit(ids.size, focus = focusScalperId != null)
                    val rows = ids.flatMap { id -> app.container.scalp.pageTrades(id, limit = limit) }
                    val trades = rows.mapNotNull { RichTradeMath.fromScalp(it) }
                    RichChartLoader.scalp(trades, codes.zip(registry.map { it.label }), focusScalperId)
                }.getOrNull() ?: RichChartLoader.scalp(
                    seed.mapNotNull { RichTradeMath.fromScalp(it) },
                    registry.map { it.code to it.label },
                    focusScalperId
                )
            }.also { RichChartCache.put(cacheKey, it) }
            delay(30_000)
        }
    }
    var selected by rememberSaveable { mutableStateOf("") }
    val shownId = focusScalperId ?: selected
    var report by remember(pack, shownId) { mutableStateOf<RichTradeMath.Report?>(null) }
    LaunchedEffect(pack, shownId) {
        val loaded = pack ?: return@LaunchedEffect
        val rows = if (shownId.isEmpty()) loaded.trades else loaded.trades.filter { it.scalperId == shownId }
        report = withContext(Dispatchers.IO) { RichTradeMath.report(rows, System.currentTimeMillis()) }
    }
    val colors = DipTheme.colors
    Column(
        Modifier.fillMaxWidth().testTag("SCALP_RICH_CHARTS"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (focusScalperId == null) "More scalp charts" else "More charts · this scalper",
            color = colors.textPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Paper only. Computed from stored closed trades. Times are US Eastern.",
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        val loaded = pack
        val shown = report
        if (loaded == null || shown == null) {
            ChartEmptyState(text = "Loading charts…", height = 48.dp)
            return@Column
        }
        if (focusScalperId == null) {
            val chips = registry.filter { (loaded.counts[it.code] ?: 0) > 0 }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "combined") {
                    FilterChip(selected = shownId.isEmpty(), onClick = { selected = "" }, label = { Text("Combined") })
                }
                items(chips, key = { it.code }) { model ->
                    FilterChip(
                        selected = shownId == model.code,
                        onClick = { selected = model.code },
                        label = { Text(model.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
        RichReportCharts(
            report = shown,
            compare = if (shownId.isEmpty()) loaded.compare else emptyList(),
            scalp = true,
            capped = loaded.capped
        )
    }
}
