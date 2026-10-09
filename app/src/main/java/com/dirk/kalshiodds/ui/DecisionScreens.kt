package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecisionScaffold(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val colors = DipTheme.colors
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary
                )
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).background(colors.bg),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
internal fun DecisionCard(content: @Composable () -> Unit) {
    val colors = DipTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) { content() }
}

/** In-app calibration report: reliability buckets, Brier and logloss vs the market, per regime/coin/global. */
@Composable
fun CalibrationReportScreen(viewModel: DecisionViewModel, onBack: () -> Unit) {
    val colors = DipTheme.colors
    val ui by viewModel.calibration.collectAsState()
    DecisionScaffold("Calibration report", onBack) {
        item {
            DecisionCard {
                ui.header.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
                Row {
                    TextButton(onClick = { viewModel.refreshCalibration() }, enabled = !ui.busy) { Text("Refresh") }
                    TextButton(onClick = { viewModel.refreshCalibration(refit = true) }, enabled = !ui.busy) { Text("Refit now") }
                }
            }
        }
        if (ui.rows.isEmpty()) {
            item {
                Text(
                    "No calibrated buckets yet. Every regime is NO BET until it has enough settled ledger rows.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
        }
        items(ui.rows, key = { it.id }) { row ->
            DecisionCard {
                Text(row.title, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(row.status, color = if (row.approved) colors.up else colors.down, style = MaterialTheme.typography.labelLarge)
                Text(row.metrics, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                row.reliability.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
        }
    }
}

/** Strategy ladder: paper → shadow → limited live. Promotion is gated; live orders still need Approve + REAL MONEY. */
@Composable
fun StrategyLadderScreen(viewModel: DecisionViewModel, onBack: () -> Unit) {
    val colors = DipTheme.colors
    val ui by viewModel.ladder.collectAsState()
    DecisionScaffold("Strategy ladder", onBack) {
        item { Text(ui.rules, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
        items(ui.statuses, key = { it.id.key }) { s ->
            DecisionCard {
                Text(DecisionCopy.ladderTitle(s), fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(DecisionCopy.ladderNeed(s), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Text(s.reason, style = MaterialTheme.typography.bodySmall, color = if (s.eligibleForNext) colors.up else colors.textSecondary)
                Row {
                    OutlinedButton(onClick = { viewModel.promote(s) }, enabled = s.eligibleForNext) { Text("Promote") }
                    TextButton(onClick = { viewModel.demote(s.id) }, enabled = s.stage != com.dirk.kalshiodds.decision.StrategyLadder.Stage.PAPER) {
                        Text("Back to paper")
                    }
                }
            }
        }
        item {
            Text("Ladder entries (${ui.entries.size}, all kept)", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        }
        items(ui.entries, key = { it.id }) { e ->
            val result = when {
                !e.settled -> "open"
                e.pnlUsd != null -> String.format(Locale.US, "%+.2f", e.pnlUsd)
                else -> e.outcome ?: "settled"
            }
            Text(
                String.format(Locale.US, "%s · %s %s %d @ %.0f¢ (fee $%.2f) · %s · %s", e.strategy, e.ticker, e.side, e.contracts, e.price * 100, e.feeUsd, e.stage.lowercase(), result),
                style = MaterialTheme.typography.labelMedium,
                color = if (e.side.equals("NO", true)) colors.down else colors.up
            )
        }
    }
}
