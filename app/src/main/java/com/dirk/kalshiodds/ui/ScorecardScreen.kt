package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(viewModel: ScorecardViewModel, onBack: () -> Unit) {
    val ui by viewModel.snapshot.collectAsStateWithLifecycle()
    ScorecardScreen(
        ui = ui,
        onBack = onBack,
        onExport = viewModel::exportResults,
        onGetLatestModel = viewModel::getLatestModel
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(
    ui: ScorecardUi,
    onBack: () -> Unit,
    onExport: () -> Unit = {},
    onGetLatestModel: () -> Unit = {}
) {
    val colors = DipTheme.colors
    val view = ui.view
    val ledger = view.ledger
    val snap = ui.metrics

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(ScorecardCopy.TITLE) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    ScorecardCopy.SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
            item {
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text("Export results")
                }
            }
            item {
                Button(onClick = onGetLatestModel, modifier = Modifier.fillMaxWidth()) {
                    Text("Get latest model")
                }
            }
            ui.exportMessage?.let {
                item { Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium) }
            }
            ui.modelNote?.let {
                item { Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium) }
            }
            if (ui.sitOut) {
                item {
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
            }
            item { LedgerCard(ui.ledger, ui.ledgerRows) }
            snap?.let { metrics ->
                item { CalibrationBanner(metrics) }
                ui.adapter?.let { item { AdapterBanner(it) } }
                ui.guardrails?.let { item { GuardBanner(it) } }
                ui.allowlist?.let { item { MuteBanner(it) } }
                item { HonestCard(metrics.honest) }
                item { WindowCard("Today", metrics.daily) }
                item { WindowCard("Rolling 7 days", metrics.rolling) }
                item { WindowCard("All time", metrics.allTime) }
                metrics.policy?.let { item { PolicyCard(it) } }
            }
            ui.extendedLine?.let { item { ExtendedAiCard(it) } }
            if (snap != null && snap.perSeries.isNotEmpty()) {
                item {
                    Text(
                        "Per series",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(snap.perSeries, key = { "series-${it.series}" }) { SeriesRow(it) }
            }
            if (snap != null) {
                item {
                    Text(
                        "Open ${snap.openCount} · void ${snap.voidCount} · settled ${snap.sampleCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
            }
            if (view.showsEmptyState) {
                item {
                    Text(
                        ScorecardCopy.NO_SETTLED,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                item { RecordCard(ScorecardCopy.COMBINED_TITLE, ledger.combined) }
                item { RecordCard(ScorecardCopy.AI_TITLE, ledger.ai) }
                item { RecordCard(ScorecardCopy.MANUAL_TITLE, ledger.manual) }
                if (ledger.noBetWouldHave.settledCount > 0) {
                    item { RecordCard(ScorecardCopy.NO_BET_TITLE, ledger.noBetWouldHave, money = false) }
                }
                if (ledger.cumulativePnl.size >= 2) {
                    item { CumulativePnlCard(ledger.cumulativePnl) }
                }
                item { SectionCard(ScorecardCopy.SIDE_TITLE) { view.bySide.forEach { BucketRow(it) } } }
                item { SectionCard(ScorecardCopy.PRICE_TITLE) { view.byPrice.forEach { BucketRow(it) } } }
                item { SectionCard(ScorecardCopy.TIME_TITLE) { view.timeOfDay.forEach { BucketRow(it) } } }
                item { SectionCard(ScorecardCopy.CONF_TITLE) { view.byConfidence.forEach { BucketRow(it) } } }
                item {
                    Text(
                        ScorecardCopy.PICKS_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                }
                items(view.recent) { pick ->
                    SettledPickRow(pick)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun RecordCard(title: String, record: ScorecardLedger.Record, money: Boolean = true) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            if (record.settledCount <= 0) ScorecardCopy.EM_DASH else "${record.wins}-${record.losses}",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeutralStat("Win rate", ScorecardCopy.percentOrDash(record.hitRate))
            NeutralStat("Settled", if (record.settledCount <= 0) ScorecardCopy.EM_DASH else "${record.settledCount}")
            NeutralStat("Streak", record.streak)
        }
        if (money) {
            Spacer(Modifier.height(8.dp))
            val m = record.money
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SignedStat("Paper P&L", ScorecardLedger.signedUsd(m.pnlUsd), m.pnlUsd)
                WinStat("Won", String.format(Locale.US, "$%.2f", m.wonUsd))
                LossStat("Lost", String.format(Locale.US, "$%.2f", m.lostUsd))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WinStat("Avg win", m.avgWinUsd?.let { ScorecardLedger.signedUsd(it) } ?: ScorecardCopy.EM_DASH)
                LossStat("Avg loss", m.avgLossUsd?.let { ScorecardLedger.signedUsd(it) } ?: ScorecardCopy.EM_DASH)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WinStat("Biggest win", m.biggestWinUsd?.let { ScorecardLedger.signedUsd(it) } ?: ScorecardCopy.EM_DASH)
                LossStat("Biggest loss", m.biggestLossUsd?.let { ScorecardLedger.signedUsd(it) } ?: ScorecardCopy.EM_DASH)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun BucketRow(bucket: ScorecardCopy.Bucket) {
    val colors = DipTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            bucket.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (bucket.label.startsWith("UP") || bucket.label.contains("UP picks")) {
                colors.up
            } else if (bucket.label.startsWith("DOWN") || bucket.label.contains("DOWN picks")) {
                colors.down
            } else {
                colors.textPrimary
            },
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (bucket.settledCount <= 0) {
                ScorecardCopy.EM_DASH
            } else {
                "${bucket.wins}-${bucket.losses} · ${ScorecardCopy.percentOrDash(bucket.hitRate)} · ${bucket.settledCount}" +
                    " · ${ScorecardLedger.signedUsd(bucket.pnlUsd)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
    }
}

@Composable
private fun SettledPickRow(pick: ScorecardCopy.RecentPick) {
    val colors = DipTheme.colors
    val row = pick.row
    val side = row?.displaySide ?: pick.side
    val won = pick.won
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                row?.windowEt ?: pick.coin,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (won) ScorecardCopy.WON else ScorecardCopy.LOST,
                style = MaterialTheme.typography.bodyMedium,
                color = if (won) colors.up else colors.down,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                side,
                style = MaterialTheme.typography.bodyMedium,
                color = SideColor.of(ScorecardCopy.sideHeadline(side), colors),
                fontWeight = FontWeight.SemiBold
            )
            if (row != null) {
                if (row.entryNotRecorded) {
                    Text(
                        ScorecardLedger.ENTRY_NOT_RECORDED,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                } else {
                    Text(
                        row.entryAsk?.let { String.format(Locale.US, "%.0f¢", it * 100.0) } ?: "ask —",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
                Text(
                    row.aiPct?.let { String.format(Locale.US, "AI %.0f%%", it) } ?: "AI —",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Text(
                    row.marketPct?.let { String.format(Locale.US, "mkt %.0f%%", it) } ?: "mkt —",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
        if (row != null) {
            Text(
                buildString {
                    if (row.entryNotRecorded) {
                        append(ScorecardLedger.ENTRY_NOT_RECORDED)
                    } else {
                        append(row.contracts?.let { "$it ct" } ?: "— ct")
                        append(" · ")
                        append(row.stakeUsd?.let { String.format(Locale.US, "stake $%.2f", it) } ?: "stake —")
                        append(" · ")
                        append(row.feeUsd?.let { String.format(Locale.US, "fee $%.2f", it) } ?: "fee —")
                    }
                    append(" · ")
                    append(ScorecardLedger.signedUsd(row.pnlUsd))
                    row.strikeUsd?.let { append(String.format(Locale.US, " · strike $%,.0f", it)) }
                    row.finalUsd?.let { append(String.format(Locale.US, " · final $%,.0f", it)) }
                    append(" · ${row.source}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (won) colors.up else colors.down
            )
        }
        Text(
            pick.ticker,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
    }
}

@Composable
private fun CumulativePnlCard(points: List<Pair<Long, Double>>) {
    val colors = DipTheme.colors
    val last = points.last().second
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(ScorecardCopy.PNL_TITLE, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            ScorecardLedger.signedUsd(last),
            fontWeight = FontWeight.Bold,
            color = if (last >= 0) colors.up else colors.down
        )
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp)) {
            val ys = points.map { it.second }
            val minY = (ys.minOrNull() ?: 0.0) - 1.0
            val maxY = (ys.maxOrNull() ?: 0.0) + 1.0
            val spanY = (maxY - minY).coerceAtLeast(1.0)
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = size.width * i / (points.size - 1).coerceAtLeast(1).toFloat()
                val y = size.height * (1f - ((p.second - minY) / spanY).toFloat()).coerceIn(0f, 1f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                if (last >= 0) colors.up else colors.down,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
            val zero = size.height * (1f - ((0.0 - minY) / spanY).toFloat()).coerceIn(0f, 1f)
            drawLine(colors.textSecondary.copy(alpha = 0.25f), Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1f)
        }
    }
}

@Composable
private fun CalibrationBanner(snap: ScorecardMetrics.Snapshot) {
    val colors = DipTheme.colors
    val ready = snap.calibrationReady
    val color = if (ready) colors.textPrimary else colors.accentOrange
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
    val color = if (adapter.ready) colors.textPrimary else colors.accentOrange
    val text = if (adapter.ready) {
        String.format(
            Locale.US,
            "On-device adapter · %d settlements · slope=%.2f intercept=%+.2f. Blend weights reweighted from your outcomes (not just temperature).",
            adapter.sampleCount,
            adapter.slope,
            adapter.intercept
        )
    } else {
        val need = (SignalConstants.MIN_ADAPTER_SAMPLES - adapter.sampleCount).coerceAtLeast(0)
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
        "No series/regimes muted. Auto-mute needs ${SignalConstants.MIN_MUTE_SAMPLES}+ rolling samples below the floor."
    } else {
        "Muted: " + muted.joinToString { "${it.label} ${(it.hitRate * 100).roundToInt()}% (${it.hits}/${it.total})" }
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
                color = colors.textPrimary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            HomeCopy.pickedSideLine(h.hits, h.n.takeIf { it > 0 }),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeutralStat("Picked-side Brier", scorecardBrierValue(h.n, h.sideBrier))
            NeutralStat("P(UP) Brier", scorecardBrierValue(h.n, h.modelBrier))
            NeutralStat("Market Brier", scorecardBrierValue(h.n, h.marketBrier))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeutralStat("Hit rate", h.hitRate?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—")
            NeutralStat("Edge if right", h.avgEdgeWhenRight?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
            NeutralStat("Edge if wrong", h.avgEdgeWhenWrong?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
        }
        if (h.perAsset.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            h.perAsset.forEach { row ->
                val side = if (row.stats.showBrier) {
                    row.stats.brier?.let { String.format(Locale.US, "Picked-side Brier %.3f", it) } ?: "—"
                } else {
                    HomeCopy.NEED_20
                }
                Text(
                    "${row.label}  ${row.stats.label}  $side",
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
            "%s  %s  hit %s  P(UP) Brier %.3f vs mkt %.3f  P&L %+.2f",
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
            text = if (stats.total <= 0) "No samples" else HomeCopy.pickedSideLine(stats.hits, stats.total),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            lineHeight = 28.sp
        )
        Text(
            "${stats.label} settled",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeutralStat("Picked-side Brier", scorecardBrierValue(stats.total, stats.brier))
            NeutralStat("P(UP) Brier", scorecardBrierValue(stats.total, stats.pUpBrier))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeutralStat("Edge if right", stats.avgEdgeWhenRight?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
            NeutralStat("Edge if wrong", stats.avgEdgeWhenWrong?.let { String.format(Locale.US, "%+.1fpp", it) } ?: "—")
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
            color = colors.textPrimary
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
            color = colors.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
    }
}

@Composable
private fun PolicyLine(title: String, line: com.dirk.kalshiodds.signal.ml.PolicyEval.Line) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        NeutralStat("N", if (line.n == 0) "—" else "${line.hits}/${line.n}")
        NeutralStat("ROI", line.roi?.let { String.format(Locale.US, "%+.1f%%", it * 100.0) } ?: "—")
        NeutralStat("P&L", if (line.n == 0) "—" else String.format(Locale.US, "%+.2f", line.totalPnl))
        NeutralStat("Brier", line.brier?.let { String.format(Locale.US, "%.3f", it) } ?: "—")
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
            Text(row.label, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
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

private fun scorecardBrierValue(n: Int, value: Double?): String {
    if (n < ScorecardMetrics.MIN_BRIER_DISPLAY) return HomeCopy.NEED_20
    return value?.let { String.format(Locale.US, "%.3f", it) } ?: "—"
}

@Composable
private fun NeutralStat(label: String, value: String) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SignedStat(label: String, value: String, usd: Double) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (usd >= 0) colors.up else colors.down,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun WinStat(label: String, value: String) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.up, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LossStat(label: String, value: String) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.down, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Prediction ledger: every settled call scored against Kalshi's own price
 * on the same windows, with a day-resampled 95% range on the difference.
 */
@Composable
private fun LedgerCard(
    report: com.dirk.kalshiodds.prediction.ledger.LedgerReport.Result?,
    rows: Int
) {
    val colors = DipTheme.colors
    SectionCard("Prediction ledger · model vs Kalshi price") {
        if (report == null) {
            Text(
                "No settled calls in the ledger yet. Every call is saved here when its window settles, and kept.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            return@SectionCard
        }
        val verdictColor = when (report.verdict) {
            com.dirk.kalshiodds.prediction.ledger.LedgerReport.Verdict.MODEL_BETTER -> colors.up
            com.dirk.kalshiodds.prediction.ledger.LedgerReport.Verdict.MARKET_BETTER -> colors.down
            else -> colors.textPrimary
        }
        Text(report.headline, style = MaterialTheme.typography.bodyMedium, color = verdictColor, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeutralStat("Model Brier", String.format(Locale.US, "%.4f", report.modelBrier))
            NeutralStat("Kalshi Brier", String.format(Locale.US, "%.4f", report.marketBrier))
            NeutralStat("Calls · days", "${report.n} · ${report.days}")
        }
        Spacer(Modifier.height(6.dp))
        val range = if (report.diffLow != null && report.diffHigh != null) {
            String.format(Locale.US, " (95%% range %+.4f to %+.4f)", report.diffLow, report.diffHigh)
        } else ""
        Text(
            String.format(Locale.US, "Model − Kalshi: %+.4f%s. Below zero means the model was better.", report.diff, range),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        if (report.betN > 0 && report.betModelBrier != null && report.betMarketBrier != null) {
            Text(
                String.format(
                    Locale.US,
                    "Calls the app would have bet (%d): model %.4f vs Kalshi %.4f",
                    report.betN, report.betModelBrier, report.betMarketBrier
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Calibration: said → happened (calls)", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        val market = report.marketBuckets.associateBy { it.lowPct }
        report.modelBuckets.forEach { b ->
            val m = market[b.lowPct]
            Text(
                String.format(
                    Locale.US,
                    "%d–%d%%  model %.0f%% → %.0f%% (%d)%s",
                    b.lowPct, b.highPct, b.meanForecast * 100, b.actualYes * 100, b.n,
                    m?.let { String.format(Locale.US, " · Kalshi %.0f%% → %.0f%% (%d)", it.meanForecast * 100, it.actualYes * 100, it.n) } ?: ""
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "$rows windows saved · ${report.versions} model version(s). Needs ${com.dirk.kalshiodds.prediction.ledger.LedgerReport.MIN_DAYS}+ days before any verdict.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
    }
}
