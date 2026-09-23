package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.Bg
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(viewModel: ScorecardViewModel, onBack: () -> Unit) {
    val snap by viewModel.snapshot.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Scorecard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Bg,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = AccentBlue
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Post-settlement track record. Analysis only — no orders. Voids are excluded.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            CalibrationBanner(snap)
            WindowCard("Today", snap.daily)
            WindowCard("Rolling 7 days", snap.rolling)
            WindowCard("All time", snap.allTime)
            if (snap.perSeries.isNotEmpty()) {
                Text(
                    "Per series",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp)
                )
                snap.perSeries.forEach { row ->
                    SeriesRow(row)
                }
            } else {
                Text(
                    "No settled samples yet. Watch crypto 15-minute contracts and wait for expiry — the first 20 outcomes unlock calibration.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Text(
                "Open ${snap.openCount} · void ${snap.voidCount} · settled ${snap.sampleCount}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CalibrationBanner(snap: ScorecardMetrics.Snapshot) {
    val ready = snap.calibrationReady
    val color = if (ready) AccentGreen else AccentOrange
    val text = if (ready) {
        String.format(
            Locale.US,
            "Calibrated · T=%.2f · %d samples. Displayed P(YES) and edge use this.",
            snap.temperature ?: 1.0,
            snap.calibrationSamples
        )
    } else {
        val need = (SignalConstants.MIN_CALIBRATION_SAMPLES - snap.calibrationSamples).coerceAtLeast(0)
        "Cold start — need $need more settlements before probabilities are recalibrated. Showing raw blend."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

@Composable
private fun WindowCard(title: String, stats: ScorecardMetrics.WindowStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            text = stats.hitRate?.let { String.format(Locale.US, "%.0f%% hit", it * 100.0) } ?: "No samples",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = if ((stats.hitRate ?: 0.0) >= 0.5) AccentGreen else MaterialTheme.colorScheme.onBackground,
            lineHeight = 32.sp
        )
        Text(
            "${stats.label} settled",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Brier", stats.brier?.let { String.format(Locale.US, "%.3f", it) } ?: "—")
            Stat("Edge if right", stats.avgEdgeWhenRight?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
            Stat("Edge if wrong", stats.avgEdgeWhenWrong?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
        }
    }
}

@Composable
private fun SeriesRow(row: ScorecardMetrics.SeriesStats) {
    val s = row.stats
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(row.series, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        Column {
            Text(
                s.hitRate?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = AccentBlue,
                fontWeight = FontWeight.Bold
            )
            Text(s.label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
    }
}
