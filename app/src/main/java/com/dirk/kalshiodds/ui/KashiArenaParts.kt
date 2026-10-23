package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * The Arena's building blocks, after the Swarm / Kashi scalp screens: one
 * spacing scale, borderless cards, a hero number with a label-over-value
 * grid, monospace figures, and two-line list rows with a P&L stripe.
 */
internal object ArenaUi {
    val GUTTER = 16.dp
    val GAP = 12.dp
    val CARD_PAD = 16.dp
    val CARD_SHAPE = RoundedCornerShape(20.dp)
    val ROW_SHAPE = RoundedCornerShape(14.dp)

    /** Figures line up in columns and read as numbers. */
    @Composable
    fun mono(base: TextStyle): TextStyle = base.copy(fontFamily = FontFamily.Monospace)
}

/** A borderless card; [accent] draws a 4 dp stripe down its left edge. */
@Composable
internal fun ArenaCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    val colors = DipTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(ArenaUi.CARD_SHAPE)
            .background(colors.surface)
            .height(IntrinsicSize.Min)
    ) {
        if (accent != null) Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(
            Modifier.weight(1f).padding(ArenaUi.CARD_PAD),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { content() }
    }
}

/** A list heading with an optional text action on the right. */
@Composable
internal fun ArenaSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val colors = DipTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            TextButton(onClick = onAction) { Text(action, color = colors.accentBlue) }
        }
    }
}

/** One label-over-value figure of a [KpiGrid]. */
internal data class Kpi(val label: String, val value: String, val color: Color? = null)

/** Figures three to a row, label above value. */
@Composable
internal fun KpiGrid(items: List<Kpi>, perRow: Int = 3) {
    val colors = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
                        Text(
                            k.value,
                            style = ArenaUi.mono(MaterialTheme.typography.titleMedium),
                            fontWeight = FontWeight.SemiBold,
                            color = k.color ?: colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** The big number at the top of a page: a caption, the net after fees, one line under it, then [kpis]. */
@Composable
internal fun HeroCard(caption: String, netUsd: Double?, sub: String, kpis: List<Kpi>, tag: String = "ARENA_HERO") {
    val colors = DipTheme.colors
    ArenaCard(Modifier.testTag(tag)) {
        Text(caption, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            netUsd?.let { ScalpTabCopy.money(it) } ?: "–",
            style = ArenaUi.mono(MaterialTheme.typography.headlineLarge).copy(fontSize = 34.sp),
            fontWeight = FontWeight.Bold,
            color = netUsd?.let { pnlColor(it) } ?: colors.textSecondary
        )
        Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        if (kpis.isNotEmpty()) {
            Box(Modifier.height(4.dp))
            KpiGrid(kpis)
        }
    }
}

/** Top-level choices. They scroll sideways so a sixth tab still shows its label. */
@Composable
internal fun <T> PillTabs(options: List<T>, selected: T, label: (T) -> String, tag: (T) -> String, onSelect: (T) -> Unit) {
    val colors = DipTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) colors.accentBlue.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(o) }
                    .testTag(tag(o)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.accentBlue else colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Small text pills that scroll sideways: a sub-choice inside a tab. */
@Composable
internal fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, tag: (T) -> String, onSelect: (T) -> Unit) {
    val colors = DipTheme.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) colors.textPrimary else colors.surface)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag(tag(o))
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.bg else colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable internal fun pnlColor(v: Double): Color = when { v > 0 -> DipTheme.colors.up; v < 0 -> DipTheme.colors.down; else -> DipTheme.colors.textSecondary }
