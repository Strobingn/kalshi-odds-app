package com.dirk.kalshiodds.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.testTag
import com.dirk.kalshiodds.signal.latefav.LateFavoriteTotals
import com.dirk.kalshiodds.ui.AiBanner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.PaperEquity
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeScorecardSummary
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

@Composable
fun TradeModeChip(label: String, modifier: Modifier = Modifier) {
    val colors = DipTheme.colors
    val accent = colors.textSecondary
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun ThisWindowCard(
    market: MarketUiModel?,
    decision: BetCall.Decision?,
    nowMs: Long,
    scorecard: HomeScorecardSummary = HomeScorecardSummary.EMPTY,
    paper: PaperBookState = PaperBookState(),
    onOpenScorecard: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** The late-comeback and clear-lead paper records, for the banner's "record" figure. */
    comebackRecord: LateFavoriteTotals? = null,
    clearLeadRecord: LateFavoriteTotals? = null,
    /** Opens the $5 ticket on the called side (same as the card's Buy button). */
    onReview: ((MarketUiModel, String) -> Unit)? = null
) {
    val colors = DipTheme.colors
    val banner = AiBanner.of(decision, market, nowMs, comebackRecord, clearLeadRecord)
    val accent = SideColor.of(banner.headline, colors)
    val shape = RoundedCornerShape(18.dp)
    val equity = remember(paper.fills, paper.startingUsd) {
        PaperEquity.curve(paper.fills, paper.startingUsd)
    }
    Column(
        modifier
            .fillMaxWidth()
            .border(2.dp, if (banner.isBet) accent else colors.border, shape)
            .background(SideColor.container(banner.headline, colors), shape)
            .padding(16.dp)
            .testTag("AI_BANNER"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "${HomeCopy.THIS_WINDOW.uppercase()} · ${banner.window}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                when (banner.headline) {
                    BetCall.Headline.BET_UP -> Icons.Default.ArrowUpward
                    BetCall.Headline.BET_DOWN -> Icons.Default.ArrowDownward
                    BetCall.Headline.NO_BET -> Icons.Default.Schedule
                },
                contentDescription = null,
                tint = if (banner.isBet) accent else colors.textSecondary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                banner.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (banner.isBet) accent else colors.textPrimary,
                modifier = Modifier.testTag("AI_BANNER_TITLE")
            )
        }
        Text(
            "${banner.rule} · ${banner.reason}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        banner.figures.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { f ->
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
                        Text(
                            f.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2
                        )
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        val side = banner.side
        if (banner.isBet && onReview != null && market != null && side != null) {
            Button(
                onClick = { onReview(market, side) },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = SideColor.on(banner.headline, colors)),
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("AI_BANNER_REVIEW")
            ) {
                Text(
                    "Review the \$5 ${if (banner.headline == BetCall.Headline.BET_DOWN) "DOWN" else "UP"} ticket",
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (equity.size >= 2) {
            val last = equity.last()
            Text(
                "Paper equity",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                fontWeight = FontWeight.Bold
            )
            Text(
                String.format(Locale.US, "$%.2f", last.equityUsd),
                style = MaterialTheme.typography.titleMedium,
                color = if (last.pnlUsd >= 0.0) colors.up else colors.down,
                fontWeight = FontWeight.Bold
            )
            RunningSeriesChart(
                points = equity.map { it.tMs to it.equityUsd },
                baseline = paper.startingUsd,
                heightDp = 96
            )
        }
        Text(banner.footer, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        HomeScorecardLine(summary = scorecard, onOpenScorecard = onOpenScorecard)
    }
}

@Composable
fun HomeScorecardLine(
    summary: HomeScorecardSummary,
    onOpenScorecard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenScorecard)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            HomeCopy.scorecardSummaryLine(summary),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = HomeCopy.SCORECARD_CONTENT_DESCRIPTION,
            tint = colors.textSecondary
        )
    }
}

@Composable
fun CollapsibleHomeSection(
    title: String,
    count: Int,
    autoExpand: Boolean,
    infoTitle: String,
    infoBody: String,
    modifier: Modifier = Modifier,
    composeWhenCollapsed: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = DipTheme.colors
    var expanded by remember { mutableStateOf(autoExpand) }
    LaunchedEffect(autoExpand) {
        if (autoExpand) expanded = true
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$title ($count)",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                InfoHintButton(infoTitle, infoBody)
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = colors.textSecondary
            )
        }
        if (composeWhenCollapsed) {
            Box(Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.height(0.dp))) {
                content()
            }
        } else {
            AnimatedVisibility(visible = expanded) {
                content()
            }
        }
    }
}

@Composable
fun InfoHintButton(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Info, contentDescription = "About $title")
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("OK") }
            }
        )
    }
}
