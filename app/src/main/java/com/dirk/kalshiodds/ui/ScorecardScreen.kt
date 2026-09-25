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
import androidx.compose.material3.Button
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
import java.util.Locale
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(viewModel: ScorecardViewModel, onBack: () -> Unit) {
    val colors = DipTheme.colors
    val ui by viewModel.snapshot.collectAsStateWithLifecycle()
    val snap = ui.metrics

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("Scorecard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accentBlue
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
                color = colors.textSecondary
            )
            Button(onClick = viewModel::exportResults, modifier = Modifier.fillMaxWidth()) {
                Text("Export results")
            }
            Button(onClick = viewModel::getLatestModel, modifier = Modifier.fillMaxWidth()) {
                Text("Get latest model")
            }
            ui.exportMessage?.let {
                Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium)
            }
            ui.modelNote?.let {
                Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.sitOut) {
                Text(
                    "SIT OUT — ${ui.autoTuneNote.ifBlank { "the model is not beating the market on settled history." }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accentOrange.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
            }
            CalibrationBanner(snap)
            AdapterBanner(ui.adapter)
            GuardBanner(ui.guardrails)
            MuteBanner(ui.allowlist)
            HonestCard(snap.honest)
            WindowCard("Today", snap.daily)
            WindowCard("Rolling 7 days", snap.rolling)
            WindowCard("All time", snap.allTime)
            snap.policy?.let { PolicyCard(it) }
            ui.extendedLine?.let { ExtendedAiCard(it) }
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
                    color = colors.textSecondary
                )
            }
            Text(
                "Open ${snap.openCount} · void ${snap.voidCount} · settled ${snap.sampleCount}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CalibrationBanner(snap: ScorecardMetrics.Snapshot) {
    val colors = DipTheme.colors
    val ready = snap.calibrationReady
    val color = if (ready) colors.accentGreen else colors.accentOrange
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
private fun AdapterBanner(adapter: com.dirk.kalshiodds.signal.feedback.OnlineAdapter.State) {
    val colors = DipTheme.colors
    val color = if (adapter.ready) colors.accentGreen else colors.accentOrange
    val text = if (adapter.ready) {
        String.format(
            Locale.US,
            "On-device adapter · %d settlements · slope=%.2f intercept=%+.2f. Blend weights reweighted from your outcomes (not just temperature).",
            adapter.sampleCount,
            adapter.slope,
            adapter.intercept
        )
    } else {
        val need = (com.dirk.kalshiodds.signal.config.SignalConstants.MIN_ADAPTER_SAMPLES - adapter.sampleCount)
            .coerceAtLeast(0)
        "Adapter cold start — $need more settlements before blend weights move. Defaults stay in force."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

@Composable
private fun GuardBanner(g: com.dirk.kalshiodds.signal.feedback.Guardrails.State) {
    val colors = DipTheme.colors
    val color = if (g.paused) colors.accentOrange else colors.textSecondary
    Text(
        text = if (g.paused) {
            g.banner ?: "alerts paused — streak guard"
        } else {
            String.format(
                Locale.US,
                "Guardrails live · streak %d · proxy P&L %+.2f · drawdown $%.2f",
                g.consecutiveWrong,
                g.rollingPnl,
                g.drawdown
            )
        },
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = if (g.paused) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

@Composable
private fun MuteBanner(a: com.dirk.kalshiodds.signal.feedback.Allowlist.State) {
    val colors = DipTheme.colors
    val muted = a.buckets.filter { it.muted }
    val text = if (muted.isEmpty()) {
        "No series/regimes muted. Auto-mute needs ${com.dirk.kalshiodds.signal.config.SignalConstants.MIN_MUTE_SAMPLES}+ rolling samples below the floor."
    } else {
        "Muted: " + muted.joinToString { "${it.label} ${(it.hitRate * 100).toInt()}% (${it.hits}/${it.total})" }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted.isEmpty()) colors.textSecondary else colors.accentOrange,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentOrange.copy(alpha = if (muted.isEmpty()) 0.06f else 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

@Composable
private fun HonestCard(h: ScorecardMetrics.Honest) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("Honest scorecard · model vs market", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        if (!h.enoughData) {
            Text(
                "Not enough data yet — ${h.n}/${ScorecardMetrics.MIN_HONEST_SAMPLES} settled signals. Numbers below are provisional.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accentOrange,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            Text(
                String.format(Locale.US, "%d settled signals", h.n),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Model Brier", h.modelBrier?.let { String.format(Locale.US, "%.3f", it) } ?: "—")
            Stat("Market Brier", h.marketBrier?.let { String.format(Locale.US, "%.3f", it) } ?: "—")
            Stat("Hit rate", h.hitRate?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—")
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Edge if right", h.avgEdgeWhenRight?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
            Stat("Edge if wrong", h.avgEdgeWhenWrong?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
            Stat("N", if (h.n == 0) "—" else h.n.toString())
        }
        if (h.perAsset.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            h.perAsset.forEach { row ->
                Text(
                    "${row.label}  ${row.stats.label}  Brier ${row.stats.brier?.let { String.format(Locale.US, "%.3f", it) } ?: "—"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
        if (h.perCoin.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Per coin", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, fontWeight = FontWeight.Bold)
            h.perCoin.forEach { b -> BreakdownRow(b) }
        }
        if (h.perTimeOfDay.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Time of day (4-hour ET)", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, fontWeight = FontWeight.Bold)
            h.perTimeOfDay.forEach { b -> BreakdownRow(b) }
        }
    }
}

@Composable
private fun BreakdownRow(b: ScorecardMetrics.Breakdown) {
    val colors = DipTheme.colors
    val line = if (!b.enoughData) {
        "${b.label}  ${b.honestLabel}"
    } else {
        String.format(
            Locale.US,
            "%s  %s  hit %s  Brier %.3f vs mkt %.3f  P&L %+.2f",
            b.label,
            b.n,
            b.hitRate?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—",
            b.modelBrier ?: 0.0,
            b.marketBrier ?: 0.0,
            b.pnlUsd ?: 0.0
        )
    }
    Text(
        line,
        style = MaterialTheme.typography.labelMedium,
        color = if (b.enoughData) colors.textSecondary else colors.accentOrange,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun WindowCard(title: String, stats: ScorecardMetrics.WindowStats) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            text = stats.hitRate?.let { String.format(Locale.US, "%.0f%% hit", it * 100.0) } ?: "No samples",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = if ((stats.hitRate ?: 0.0) >= 0.5) colors.accentGreen else MaterialTheme.colorScheme.onBackground,
            lineHeight = 32.sp
        )
        Text(
            "${stats.label} settled",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
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
private fun PolicyCard(policy: com.dirk.kalshiodds.signal.ml.PolicyEval.Scorecard) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("Counterfactual policy", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            String.format(Locale.US, "If every alert @ $%.0f", policy.stakeUsd),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(policy.note, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        PolicyLine("All alerts", policy.allAlerts)
        Spacer(Modifier.height(6.dp))
        PolicyLine("Uncertainty-gated", policy.gated)
    }
}

@Composable
private fun ExtendedAiCard(line: String) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("Extended AI (advisory)", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            "Learned from settlements — RL sizer never places an order",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun PolicyLine(title: String, line: com.dirk.kalshiodds.signal.ml.PolicyEval.Line) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("N", if (line.n == 0) "—" else "${line.hits}/${line.n}")
        Stat("ROI", line.roi?.let { String.format(Locale.US, "%+.1f%%", it * 100.0) } ?: "—")
        Stat("P&L", if (line.n == 0) "—" else String.format(Locale.US, "%+.2f", line.totalPnl))
        Stat("Brier", line.brier?.let { String.format(Locale.US, "%.3f", it) } ?: "—")
    }
}

@Composable
private fun SeriesRow(row: ScorecardMetrics.SeriesStats) {
    val colors = DipTheme.colors
    val s = row.stats
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(row.series, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Column {
            Text(
                s.hitRate?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = colors.accentBlue,
                fontWeight = FontWeight.Bold
            )
            Text(s.label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
    }
}
