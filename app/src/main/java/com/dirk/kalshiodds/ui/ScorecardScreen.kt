package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(viewModel: ScorecardViewModel, onBack: () -> Unit) {
    val ui by viewModel.snapshot.collectAsStateWithLifecycle()
    ScorecardScreen(ui = ui, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(
    ui: ScorecardUi,
    onBack: () -> Unit
) {
    val colors = DipTheme.colors
    val view = ui.view

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                ScorecardCopy.SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            if (view.showsEmptyState) {
                Text(
                    ScorecardCopy.NO_SETTLED,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                SummaryCard(view.summary)
            }
            SectionCard(ScorecardCopy.COINS_TITLE) {
                view.coins.forEach { BucketRow(it) }
            }
            SectionCard(ScorecardCopy.TIME_TITLE) {
                view.timeOfDay.forEach { BucketRow(it) }
            }
            if (view.recent.isNotEmpty()) {
                RecentPicksCard(view.recent)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SummaryCard(summary: HomeScorecardSummary) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            ScorecardCopy.recordLine(summary),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeutralStat("Win rate", ScorecardCopy.winRateLine(summary))
            NeutralStat("Paper P&L", ScorecardCopy.paperPnlLine(summary))
            NeutralStat("Settled", ScorecardCopy.settledCountLine(summary))
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
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (bucket.settledCount <= 0) {
                ScorecardCopy.EM_DASH
            } else {
                "${bucket.wins}-${bucket.losses} · ${ScorecardCopy.percentOrDash(bucket.hitRate)} · ${bucket.settledCount}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
    }
}

@Composable
private fun RecentPicksCard(picks: List<ScorecardCopy.RecentPick>) {
    val colors = DipTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                ScorecardCopy.RECENT_TITLE,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${picks.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide recent settled picks" else "Show recent settled picks",
                    tint = colors.textSecondary
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            picks.forEach { RecentPickRow(it) }
        }
    }
}

@Composable
private fun RecentPickRow(pick: ScorecardCopy.RecentPick) {
    val colors = DipTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(pick.coin, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            pick.side,
            style = MaterialTheme.typography.bodyMedium,
            color = SideColor.of(ScorecardCopy.sideHeadline(pick.side), colors),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (pick.won) ScorecardCopy.WON else ScorecardCopy.LOST,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
        Text(
            pick.ticker,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NeutralStat(label: String, value: String) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
