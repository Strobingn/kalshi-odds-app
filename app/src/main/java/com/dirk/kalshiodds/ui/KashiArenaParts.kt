@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/**
 * Arena dashboard chrome from the Scalper Arena mock.
 * Dark pages use #0b0f17 / #131a26. UP and DOWN stay on [DipTheme]
 * so FieldOps reds and greens are unchanged. Light mode keeps the palette.
 */
internal object ArenaChrome {
    private val bgDark = Color(0xFF0B0F17)
    private val surfaceDark = Color(0xFF131A26)
    private val surfaceAltDark = Color(0xFF1B2434)
    private val borderDark = Color(0xFF263248)

    @Composable
    private fun dark(): Boolean = DipTheme.colors.bg.luminance() < 0.5f

    @Composable fun page(): Color = if (dark()) bgDark else DipTheme.colors.bg
    @Composable fun surface(): Color = if (dark()) surfaceDark else DipTheme.colors.surface
    @Composable fun surfaceAlt(): Color = if (dark()) surfaceAltDark else DipTheme.colors.surfaceAlt
    @Composable fun border(): Color = if (dark()) borderDark else DipTheme.colors.border
    @Composable fun well(): Color = if (dark()) bgDark else DipTheme.colors.bg
}

/**
 * Spacing, rounded cards, a hero number, tabular monospace figures,
 * and a P&L stripe down the left edge.
 */
internal object ArenaUi {
    val GUTTER = 16.dp
    val GAP = 12.dp
    val CARD_PAD = 16.dp
    val CARD_SHAPE = RoundedCornerShape(18.dp)
    val ROW_SHAPE = RoundedCornerShape(14.dp)

    /** JetBrains-Mono-style figures: monospace and tabular digits. */
    @Composable
    fun mono(base: TextStyle): TextStyle = base.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        letterSpacing = (-0.2).sp
    )
}

/** Rounded card with a hairline border. [accent] draws a 4 dp stripe down the left edge. */
@Composable
internal fun ArenaCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    val shape = ArenaUi.CARD_SHAPE
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ArenaChrome.surface())
            .border(1.dp, ArenaChrome.border(), shape)
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

/** Figures three to a row, each in a raised tile, label above value. */
@Composable
internal fun KpiGrid(items: List<Kpi>, perRow: Int = 3) {
    val colors = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ArenaChrome.surfaceAlt())
                            .padding(horizontal = 11.dp, vertical = 9.dp)
                    ) {
                        Text(
                            k.label.uppercase(Locale.US),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = 0.4.sp
                        )
                        Text(
                            k.value,
                            style = ArenaUi.mono(MaterialTheme.typography.titleMedium),
                            fontWeight = FontWeight.Bold,
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
internal fun HeroCard(
    caption: String,
    netUsd: Double?,
    sub: String,
    kpis: List<Kpi>,
    tag: String = "ARENA_HERO",
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = DipTheme.colors
    val stripe = netUsd?.let { pnlColor(it) } ?: colors.up
    ArenaCard(Modifier.testTag(tag), accent = stripe) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Text(
                    netUsd?.let { ScalpTabCopy.money(it) } ?: "–",
                    style = ArenaUi.mono(MaterialTheme.typography.headlineLarge).copy(fontSize = 34.sp, letterSpacing = (-0.5).sp),
                    fontWeight = FontWeight.Bold,
                    color = netUsd?.let { pnlColor(it) } ?: colors.textSecondary
                )
            }
            trailing?.invoke()
        }
        Text(sub, style = ArenaUi.mono(MaterialTheme.typography.bodySmall), color = colors.textSecondary)
        if (kpis.isNotEmpty()) KpiGrid(kpis)
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
            .background(ArenaChrome.surface())
            .border(1.dp, ArenaChrome.border(), RoundedCornerShape(50))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent)
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
                    .background(if (on) colors.accentBlue.copy(alpha = 0.16f) else ArenaChrome.surfaceAlt())
                    .border(1.dp, if (on) colors.accentBlue.copy(alpha = 0.35f) else ArenaChrome.border(), RoundedCornerShape(50))
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag(tag(o))
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.accentBlue else colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable internal fun pnlColor(v: Double): Color = when { v > 0 -> DipTheme.colors.up; v < 0 -> DipTheme.colors.down; else -> DipTheme.colors.textSecondary }

internal enum class ArenaTone { UP, DOWN, LIVE, HFT, WIN, SPOT }

/** Pill for live, side, HFT, and kept-winner marks. Ink stays on the theme UP/DOWN colors. */
@Composable
internal fun ArenaBadge(text: String, tone: ArenaTone) {
    val c = DipTheme.colors
    val ink = when (tone) {
        ArenaTone.UP, ArenaTone.WIN, ArenaTone.LIVE -> c.up
        ArenaTone.DOWN -> c.down
        ArenaTone.HFT -> c.accentBlue
        ArenaTone.SPOT -> c.spot
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(ink.copy(alpha = 0.14f))
            .border(1.dp, ink.copy(alpha = 0.30f), RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (tone == ArenaTone.LIVE) Box(Modifier.size(7.dp).clip(CircleShape).background(ink))
        Text(text, color = ink, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Round color keys under a chart. */
@Composable
internal fun ChartLegend(items: List<Pair<Color, String>>) {
    val c = DipTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Text(label, color = c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
