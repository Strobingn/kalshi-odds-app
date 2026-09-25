package com.dirk.kalshiodds.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeScorecardSummary
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.theme.DipTheme

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
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    val headline = HomeCopy.thisWindowHeadline(decision, market, nowMs)
    val accent = SideColor.of(decision?.headline ?: BetCall.Headline.NO_BET, colors)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            HomeCopy.THIS_WINDOW,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            headline,
            style = MaterialTheme.typography.bodyMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun HomeScorecardLine(
    summary: HomeScorecardSummary,
    onOpenScorecard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    Text(
        HomeCopy.scorecardSummaryLine(summary),
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenScorecard)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
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
