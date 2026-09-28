package com.dirk.kalshiodds.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.local.recording.MarketDataRecorder
import com.dirk.kalshiodds.data.local.recording.RecordingStats
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Human size: `812 KB`, `14.3 MB`. */
internal fun recordingSizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%d KB", bytes / 1024L)
    else -> "$bytes B"
}

/** `Today 14.3 MB · 6 days stored (82.0 MB)`. */
internal fun recordingSummary(stats: RecordingStats): String {
    val days = stats.days.size
    val base = "Today ${recordingSizeLabel(stats.todayBytes)} · $days day${if (days == 1) "" else "s"} stored" +
        " (${recordingSizeLabel(stats.totalBytes)})"
    return when {
        stats.overCap -> "$base · size cap reached, recording paused"
        stats.droppedRows > 0 -> "$base · ${stats.droppedRows} rows dropped (queue full)"
        else -> base
    }
}

private fun recorderOf(context: Context): MarketDataRecorder? =
    runCatching { KalshiOddsApp.from(context).container.recorder }.getOrNull()

/** Recorder stats, refreshed every 5 s off the main thread. */
@Composable
private fun rememberRecordingStats(): RecordingStats {
    val context = LocalContext.current
    val stats by produceState(RecordingStats(), context) {
        val recorder = recorderOf(context) ?: return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { recorder.stats() }.getOrDefault(value) }
            delay(5_000L)
        }
    }
    return stats
}

/** Settings → Trade feed: size of today's recording and days stored. */
@Composable
fun RecordingStatsLine() {
    val colors = DipTheme.colors
    val stats = rememberRecordingStats()
    Text(
        recordingSummary(stats),
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary
    )
}

/**
 * Data → "Export recordings": pick days (none picked = all), then save a
 * zip through the system file picker (Drive, Downloads, …).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsExportSection(onExport: (uri: Uri, days: List<String>) -> Unit) {
    val colors = DipTheme.colors
    val stats = rememberRecordingStats()
    var selected by remember { mutableStateOf(setOf<String>()) }
    val saveZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> uri?.let { onExport(it, selected.sorted()) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Second-by-second BTC spot, KXBTC15M top of book, public trades and settlements, " +
                "recorded while Live signals runs (Settings → Record market data). " +
                recordingSummary(stats),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        if (stats.days.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                stats.days.asReversed().forEach { d ->
                    FilterChip(
                        selected = d.day in selected,
                        onClick = {
                            selected = if (d.day in selected) selected - d.day else selected + d.day
                        },
                        label = { Text("${d.day} · ${recordingSizeLabel(d.bytes)}") }
                    )
                }
            }
        }
        Button(
            onClick = {
                val tag = if (selected.size == 1) selected.first() else "${selected.size.takeIf { it > 0 } ?: "all"}-days"
                saveZip.launch("diphunter-recordings-$tag.zip")
            },
            enabled = stats.days.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (selected.isEmpty()) "Export recordings (all days)" else "Export recordings (${selected.size} selected)") }
    }
}
