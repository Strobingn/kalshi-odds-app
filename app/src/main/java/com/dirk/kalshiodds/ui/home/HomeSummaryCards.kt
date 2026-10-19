package com.dirk.kalshiodds.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.ui.ScalpTabCopy
import com.dirk.kalshiodds.ui.charts.ChartEmptyState
import com.dirk.kalshiodds.ui.charts.NetBar
import com.dirk.kalshiodds.ui.charts.RankedNetBars
import com.dirk.kalshiodds.ui.charts.WinRateGauge
import com.dirk.kalshiodds.ui.components.FieldCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.worker.HomeSummaryScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Best scalper (24h and 7d) then today's ET summary. Both sit above the rest of Home.
 * The results-store read runs on [Dispatchers.IO] and never blocks composition.
 */
@Composable
fun HomeSummarySection(
    loadSnapshot: (suspend () -> HomeSummarySnapshot)? = null
) {
    val context = LocalContext.current
    var snap by remember { mutableStateOf<HomeSummarySnapshot?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(loadSnapshot) {
        while (isActive) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { loadSnapshot?.invoke() ?: loadHomeSummary(context) }
            }
            if (loaded.isSuccess) {
                snap = loaded.getOrNull()
                failed = false
            } else if (snap == null) {
                failed = true
            }
            delay(60_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeBestScalperCard(
            best24 = snap?.best24,
            best7 = snap?.best7,
            loading = snap == null && !failed,
            failed = failed && snap == null
        )
        HomeDailySummaryCard(
            summary = snap?.daily,
            loading = snap == null && !failed,
            failed = failed && snap == null
        )
    }
}

internal suspend fun loadHomeSummary(context: Context): HomeSummarySnapshot = withContext(Dispatchers.IO) {
    val app = context.applicationContext as? KalshiOddsApp
        ?: return@withContext HomeSummaryLoader.empty(System.currentTimeMillis(), settingsTracked = false)
    val store = app.container.predictionLedger
        ?: return@withContext HomeSummaryLoader.empty(System.currentTimeMillis(), settingsTracked = false)
    val settingsResult = runCatching { app.container.archive.recentSettingsChanges(HomeSummaryLoader.SETTINGS_LIMIT) }
    HomeSummaryLoader.load(
        store,
        settingsResult.getOrDefault(emptyList()),
        System.currentTimeMillis(),
        settingsTracked = settingsResult.isSuccess
    )
}

@Composable
fun HomeBestScalperCard(
    best24: RankedScalper?,
    best7: RankedScalper?,
    loading: Boolean = false,
    failed: Boolean = false
) {
    val colors = DipTheme.colors
    val lead = best24 ?: best7
    FieldCard(accentColor = accentFor(lead?.netUsd, colors.up, colors.down)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                HomeSummaryCopy.BEST_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(HomeSummaryCopy.PAPER, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            WindowBlock("Last 24h", best24, loading, failed)
            WindowBlock("Last 7d", best7, loading, failed)
            if (!loading && !failed) {
                Spacer(Modifier.height(4.dp))
                RankedNetBars(
                    listOfNotNull(
                        best24?.let { NetBar("24h · ${it.label}", it.netUsd) },
                        best7?.let { NetBar("7d · ${it.label}", it.netUsd) }
                    ),
                    Modifier.fillMaxWidth(),
                    ranked = false,
                    labelWidthDp = 120
                )
            }
        }
    }
}

@Composable
fun HomeDailySummaryCard(
    summary: DailySummary?,
    loading: Boolean = false,
    failed: Boolean = false
) {
    val context = LocalContext.current
    val colors = DipTheme.colors
    var notify by remember { mutableStateOf(HomeSummaryPrefs.isEnabled(context)) }
    FieldCard(accentColor = accentFor(summary?.netUsd, colors.up, colors.down)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                HomeSummaryCopy.DAILY_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                summary?.let { "${it.dayEt} ET · ${HomeSummaryCopy.PAPER}" } ?: "ET · ${HomeSummaryCopy.PAPER}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            when {
                loading -> Text(HomeSummaryCopy.LOADING, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                failed || summary == null -> Text(HomeSummaryCopy.FAILED, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                else -> {
                    Text(
                        ScalpTabCopy.money(summary.netUsd),
                        style = MaterialTheme.typography.headlineSmall,
                        color = pnlColor(summary.netUsd, colors.up, colors.down, colors.textSecondary),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${HomeSummaryCopy.tradeCount(summary.trades)} · ${ScalpTabCopy.pct(summary.winRate)} win",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary
                    )
                    if (summary.trades <= 0) ChartEmptyState() else Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WinRateGauge(summary.winRate, summary.trades, size = 88.dp, unit = "trades")
                        RankedNetBars(
                            listOfNotNull(
                                summary.best?.let { NetBar("Best · ${it.label}", it.netUsd) },
                                summary.worst?.takeIf { it.scalperId != summary.best?.scalperId }
                                    ?.let { NetBar("Worst · ${it.label}", it.netUsd) }
                            ),
                            Modifier.fillMaxWidth(),
                            ranked = false,
                            labelWidthDp = 120
                        )
                    }
                    Text(
                        HomeSummaryCopy.settingsHeadline(summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    summary.settings.take(HomeSummaryCopy.SETTINGS_SHOWN).forEach { change ->
                        Text(
                            HomeSummaryCopy.settingsLine(change),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val extra = summary.settings.size - HomeSummaryCopy.SETTINGS_SHOWN
                    if (extra > 0) {
                        Text(
                            "and $extra more",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(HomeSummaryCopy.NOTIFY_LABEL, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Text(HomeSummaryCopy.NOTIFY_HINT, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
                Switch(
                    checked = notify,
                    onCheckedChange = { on ->
                        notify = on
                        HomeSummaryScheduler.setEnabled(context, on)
                    },
                    modifier = Modifier.testTag("home_daily_summary_notify")
                )
            }
        }
    }
}

@Composable
private fun WindowBlock(title: String, rank: RankedScalper?, loading: Boolean, failed: Boolean) {
    val colors = DipTheme.colors
    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    when {
        loading -> Text(HomeSummaryCopy.LOADING, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        failed -> Text(HomeSummaryCopy.FAILED, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        rank == null -> Text(HomeSummaryCopy.EMPTY_WINDOW, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        else -> {
            Text(
                rank.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${ScalpTabCopy.money(rank.netUsd)} net · ${ScalpTabCopy.pct(rank.winRate)} win · ${HomeSummaryCopy.tradeCount(rank.trades)}",
                style = MaterialTheme.typography.bodySmall,
                color = pnlColor(rank.netUsd, colors.up, colors.down, colors.textSecondary)
            )
        }
    }
}

private fun accentFor(net: Double?, up: Color, down: Color): Color? = when {
    net == null -> null
    net > 0.004 -> up
    net < -0.004 -> down
    else -> null
}

private fun pnlColor(net: Double, up: Color, down: Color, flat: Color): Color = when {
    net > 0.004 -> up
    net < -0.004 -> down
    else -> flat
}
