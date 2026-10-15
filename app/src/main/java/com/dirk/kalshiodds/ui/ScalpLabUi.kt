package com.dirk.kalshiodds.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.ScalpControlSpec
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs

/** Presentation only: ranking uses realized P&L after fees, never an invented equity curve. */
object ScalpLabUi {
    enum class Filter(val label: String) { ALL("All"), OPEN("Open"), AHEAD("Ahead"), BEHIND("Behind"), V2("V2") }
    enum class Page(val label: String) { MODELS("Models"), COMPARE("Compare"), LADDER("Ladder") }

    fun ranked(rows: Map<ScalpModels.Model, ScalpModels.Row>, live: Map<ScalpModels.Model, ScalpModels.Row>,
        filter: Filter = Filter.ALL, query: String = ""): List<ScalpModels.Model> {
        val q = query.trim()
        return ScalpModels.ALL.filter { m ->
            val r = rows[m]
            (q.isEmpty() || m.label.contains(q, true) || m.code.contains(q, true) || m.blurb.contains(q, true)) &&
                when (filter) {
                    Filter.ALL -> true
                    Filter.OPEN -> (live[m]?.open ?: 0) > 0
                    Filter.AHEAD -> (r?.roundTrips ?: 0) > 0 && r!!.netUsd > 0
                    Filter.BEHIND -> (r?.roundTrips ?: 0) > 0 && r!!.netUsd < 0
                    Filter.V2 -> ScalpModels.isV2(m)
                }
        }.sortedWith(compareByDescending<ScalpModels.Model> { (rows[it]?.roundTrips ?: 0) > 0 }
            .thenByDescending { rows[it]?.netUsd ?: 0.0 }.thenBy { it.ordinal })
    }
}

/** Grok-style rounded surfaces, scoped to Scalp so other tabs keep their existing design. */
@Composable
internal fun ScalpLabCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = DipTheme.colors
    Surface(modifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)),
        shape = RoundedCornerShape(16.dp), color = c.surface, border = BorderStroke(1.dp, c.border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun ScalpLabChip(text: String, fg: Color, bg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
internal fun ScalpLabOverview(rows: Map<ScalpModels.Model, ScalpModels.Row>, live: Map<ScalpModels.Model, ScalpModels.Row>, today: Boolean) {
    val c = DipTheme.colors
    val traded = rows.values.filter { it.roundTrips > 0 }
    val net = traded.sumOf { it.netUsd }
    val trips = traded.sumOf { it.roundTrips }
    val wins = traded.sumOf { it.wins }
    val ahead = traded.count { it.netUsd > 0 }
    val behind = traded.count { it.netUsd < 0 }
    ScalpLabCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scalp lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Text("${ScalpModels.ALL.size} models · paper only", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            }
            ScalpLabChip(if (trips == 0) "WAITING" else if (net >= 0) "AHEAD" else "BEHIND",
                if (trips == 0) c.textSecondary else if (net >= 0) c.up else c.down,
                if (trips == 0) c.surfaceAlt else if (net >= 0) c.upContainer else c.downContainer)
        }
        Text(if (trips == 0) "—" else ScalpTabCopy.money(net), style = MaterialTheme.typography.headlineLarge,
            color = if (trips == 0) c.textSecondary else if (net >= 0) c.up else c.down, fontWeight = FontWeight.Bold)
        Text((if (today) "Today (ET)" else "All-time") + " · realized P&L after fees", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScalpLabChip("${live.values.sumOf { it.open }} open", c.accentBlue, c.surfaceAlt)
            ScalpLabChip("$ahead ahead", c.up, c.upContainer)
            ScalpLabChip("$behind behind", c.down, c.downContainer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScalpLabChip("$trips trades", c.textPrimary, c.surfaceAlt)
            ScalpLabChip("${ScalpTabCopy.pct(if (trips > 0) wins.toDouble() / trips else null)} wins", c.textPrimary, c.surfaceAlt)
        }
        Text("Totals across parallel model experiments. Tap a model for its sliders, trades and equity chart.",
            style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}

@Composable
internal fun ScalpLabModelCard(rank: Int, m: ScalpModels.Model, row: ScalpModels.Row?, live: ScalpModels.Row?,
    saved: ScalpControlSpec.Config?, onClick: () -> Unit) {
    val c = DipTheme.colors
    val traded = (row?.roundTrips ?: 0) > 0
    val net = row?.netUsd ?: 0.0
    val ink = if (!traded) c.textSecondary else if (net >= 0) c.up else c.down
    ScalpLabCard(onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScalpLabChip("$rank", ink, ink.copy(alpha = 0.14f))
            Column(Modifier.weight(1f)) {
                Text(m.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(m.code, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
            Text(if (traded) ScalpTabCopy.money(net) else "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val open = live?.open ?: 0
            ScalpLabChip(if (open > 0) "OPEN $open" else "FLAT", if (open > 0) c.accentBlue else c.textSecondary, c.surfaceAlt)
            ScalpLabChip("${row?.roundTrips ?: 0} trades", c.textPrimary, c.surfaceAlt)
            ScalpLabChip("${ScalpTabCopy.pct(row?.winRate)} wins", c.textSecondary, c.surfaceAlt)
        }
        if (saved != null) Text(if (saved.enabled) "Manual settings · ${saved.revision}" else "Entries paused · existing trades still managed",
            style = MaterialTheme.typography.labelSmall, color = if (saved.enabled) c.accentBlue else c.accentOrange)
        Text(m.blurb, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (traded) "${ScalpTabCopy.money(net / row!!.roundTrips)} / trade" else "Waiting for trades",
                style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            Text("Settings & history →", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.accentBlue)
        }
    }
}

/** Realized results from the same period as the list. Empty books do not look profitable. */
@Composable
internal fun ScalpLabComparison(rows: Map<ScalpModels.Model, ScalpModels.Row>, onOpenAlgo: (ScalpModels.Model) -> Unit) {
    val c = DipTheme.colors
    val ranked = rows.values.filter { it.roundTrips > 0 }.sortedByDescending { it.netUsd }
    val selected = (ranked.take(4) + ranked.takeLast(4)).distinctBy { it.model }
    val max = selected.maxOfOrNull { abs(it.netUsd) }?.coerceAtLeast(0.01) ?: 1.0
    ScalpLabCard {
        Text("P&L comparison", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
        Text("Top and bottom models · realized after fees", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        if (ranked.isEmpty()) Text("Comparison appears after the first closed trade.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        selected.forEach { row ->
            val ink = if (row.netUsd >= 0) c.up else c.down
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenAlgo(row.model) }.padding(vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.model.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ScalpTabCopy.money(row.netUsd), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ink)
                }
                Canvas(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "${row.model.label}: ${ScalpTabCopy.money(row.netUsd)} after fees" }) {
                    val mid = size.width / 2
                    val width = (mid * abs(row.netUsd) / max).toFloat()
                    drawRect(c.surfaceAlt)
                    if (width > 0) drawRect(ink, Offset(if (row.netUsd >= 0) mid else mid - width, 0f), Size(width, size.height))
                    drawLine(c.border, Offset(mid, 0f), Offset(mid, size.height), 1.dp.toPx())
                }
            }
        }
        Text("A positive result alone does not establish an edge. Open a model to see sample size and confidence intervals.",
            style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}
