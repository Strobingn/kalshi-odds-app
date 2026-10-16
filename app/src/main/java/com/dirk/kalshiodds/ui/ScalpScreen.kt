package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.dirk.kalshiodds.signal.scalp.ScalpSettings
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalp.ScalpAccount
import com.dirk.kalshiodds.signal.scalp.ScalpLabState
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs

/**
 * All 20 paper scalps. Read-only except Reset. Nothing on this screen
 * can place a Kalshi order.
 */
@Composable
fun ScalpScreen(
    state: ScalpLabState,
    onReset: () -> Unit,
    onResetOne: (String) -> Unit = {},
    onExport: () -> Unit = {}
) {
    val colors = DipTheme.colors
    val ctx = LocalContext.current
    remember(ctx) {
        loadScalpSettings(ctx.getSharedPreferences("scalp_settings", Context.MODE_PRIVATE))
        true
    }
    val ranked = state.accounts.sortedByDescending { it.pnlUsd() }
    val total = state.accounts.sumOf { it.pnlUsd() }
    var showSettings by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Scalp", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                String.format(Locale.US, "%+.0f combined · %d books", total, state.accounts.size),
                style = MaterialTheme.typography.labelMedium,
                color = if (total >= 0.0) colors.up else colors.down
            )
        }
        state.tape?.let { tape -> item { LivePriceChart(tape) } }
        item {
            OutlinedButton(onClick = { showSettings = !showSettings }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showSettings) "Hide settings" else "Settings")
            }
        }
        if (showSettings) {
            item { ScalpSettingsCard() }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Reset all") }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export") }
            }
        }
        itemsIndexed(ranked, key = { _, a -> a.id }) { i, a ->
            CompactRow(i + 1, a) { onResetOne(a.id) }
        }
    }
}

@Composable
private fun CompactRow(rank: Int, account: ScalpAccount, onReset: () -> Unit) {
    val colors = DipTheme.colors
    val pnl = account.pnlUsd()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(String.format("%d", rank), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                String.format(Locale.US, "%d trades · %.0f%%", account.trades, if (account.trades > 0) 100.0 * account.wins / account.trades else 0.0),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }
        Text(
            String.format(Locale.US, "%+.0f", pnl),
            style = MaterialTheme.typography.labelLarge,
            color = if (pnl >= 0.0) colors.up else colors.down,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun loadScalpSettings(prefs: android.content.SharedPreferences) {
    ScalpSettings.enabled = prefs.getBoolean("on", ScalpSettings.enabled)
    ScalpSettings.lastMinutes = prefs.getInt("lastMin", ScalpSettings.lastMinutes).coerceIn(1, 14)
    ScalpSettings.minAsk = prefs.getFloat("minPx", ScalpSettings.minAsk.toFloat()).toDouble()
    ScalpSettings.maxAsk = prefs.getFloat("maxPx", ScalpSettings.maxAsk.toFloat()).toDouble()
    ScalpSettings.fraction = prefs.getFloat("frac2", ScalpSettings.fraction.toFloat()).toDouble()
    ScalpSettings.stopLoss = prefs.getFloat("stop2", ScalpSettings.stopLoss.toFloat()).toDouble()
    ScalpSettings.cadenceMs = prefs.getInt("cadence", (ScalpSettings.cadenceMs / 1000L).toInt()).toLong() * 1000L
}

@Composable
private fun ScalpSettingsCard() {
    val colors = DipTheme.colors
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("scalp_settings", Context.MODE_PRIVATE) }
    val on = remember { mutableStateOf(prefs.getBoolean("on", ScalpSettings.enabled)) }
    val lastMin = remember { mutableStateOf(prefs.getInt("lastMin", ScalpSettings.lastMinutes).toDouble()) }
    val lo = remember { mutableStateOf(prefs.getFloat("minPx", ScalpSettings.minAsk.toFloat()).toDouble() * 100.0) }
    val hi = remember { mutableStateOf(prefs.getFloat("maxPx", ScalpSettings.maxAsk.toFloat()).toDouble() * 100.0) }
    val frac = remember { mutableStateOf(prefs.getFloat("frac2", ScalpSettings.fraction.toFloat()).toDouble() * 100.0) }
    val stop = remember { mutableStateOf(prefs.getFloat("stop2", ScalpSettings.stopLoss.toFloat()).toDouble() * 100.0) }
    val every = remember { mutableStateOf(prefs.getInt("cadence", (ScalpSettings.cadenceMs / 1000L).toInt()).toDouble()) }
    fun push() {
        ScalpSettings.enabled = on.value
        ScalpSettings.lastMinutes = lastMin.value.toInt().coerceIn(1, 14)
        ScalpSettings.minAsk = lo.value / 100.0
        ScalpSettings.maxAsk = hi.value / 100.0
        ScalpSettings.fraction = frac.value / 100.0
        ScalpSettings.stopLoss = stop.value / 100.0
        ScalpSettings.cadenceMs = every.value.toLong() * 1000L
        prefs.edit()
            .putBoolean("on", on.value)
            .putInt("lastMin", ScalpSettings.lastMinutes)
            .putFloat("minPx", (lo.value / 100.0).toFloat())
            .putFloat("maxPx", (hi.value / 100.0).toFloat())
            .putFloat("frac2", (frac.value / 100.0).toFloat())
            .putFloat("stop2", (stop.value / 100.0).toFloat())
            .putInt("cadence", every.value.toInt())
            .apply()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RuleCard(lastMin.value, lo.value, hi.value, frac.value, stop.value, every.value)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(
                        if (on.value) "Running · changes count from its next order" else "Switched off · it opens nothing new",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
                Switch(checked = on.value, onCheckedChange = { on.value = it; push() })
            }
            KnobSlider("Buys in the last", lastMin.value, 1.0, 14.0, 1.0, { "${it.toInt()} min" }) {
                lastMin.value = it; push()
            }
            KnobSlider("Size of each bet", frac.value, 5.0, 50.0, 1.0, { "${it.toInt()}%" }) {
                frac.value = it; push()
            }
            KnobSlider("Stop loss (0 = hold to the close)", stop.value, 0.0, 30.0, 1.0, { "${it.toInt()}¢" }) {
                stop.value = it; push()
            }
            KnobSlider("Lowest price it buys", lo.value, 1.0, 99.0, 1.0, { "${it.toInt()}¢" }) {
                lo.value = it
                if (hi.value + 1e-9 < it) hi.value = it
                push()
            }
            KnobSlider("Highest price it buys", hi.value, 1.0, 99.0, 1.0, { "${it.toInt()}¢" }) {
                hi.value = it
                if (lo.value > it + 1e-9) lo.value = it
                push()
            }
            KnobSlider("Looks every", every.value, 0.0, 30.0, 1.0, { if (it.toInt() == 0) "every quote" else "${it.toInt()} s" }) {
                every.value = it; push()
            }
            Text(
                "One open bet per book. It buys the favourite between those prices.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun RuleCard(lastMin: Double, lo: Double, hi: Double, frac: Double, stop: Double, every: Double) {
    val stopLine = if (stop.toInt() == 0) {
        "Holds to the close. No stop."
    } else {
        "Sells if the bid falls ${stop.toInt()}¢ under the entry, gives back 3¢, or the last minute starts."
    }
    val look = if (every.toInt() == 0) "Looks at every quote." else "Looks every ${every.toInt()} s."
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DipTheme.colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("How it trades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = DipTheme.colors.textPrimary)
        Text(
            "Its entry, exit and limits, with your settings.",
            style = MaterialTheme.typography.bodySmall,
            color = DipTheme.colors.textSecondary
        )
        RuleLine("When", "The last ${lastMin.toInt()} min. It buys the favourite.")
        RuleLine("•", "Pays ${lo.toInt()}¢ to ${hi.toInt()}¢.")
        RuleLine("•", "Each bet is ${frac.toInt()}% of that book's cash.")
        RuleLine("•", stopLine)
        RuleLine("•", look)
    }
}

@Composable
private fun RuleLine(mark: String, line: String) {
    val colors = DipTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(mark, style = MaterialTheme.typography.labelMedium, color = colors.accentBlue, modifier = Modifier.width(36.dp))
        Text(line, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
    }
}

@Composable
private fun KnobSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    text: (Double) -> String,
    onSet: (Double) -> Unit
) {
    val colors = DipTheme.colors
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(min, max).toFloat()) }
    fun snap(x: Float): Double = (min + Math.round((x - min) / step) * step).coerceIn(min, max)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                text(snap(v)),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
        }
        val steps = (((max - min) / step).roundToInt() - 1).coerceIn(0, 2_000)
        Slider(
            value = v,
            onValueChange = { v = snap(it).toFloat() },
            onValueChangeFinished = { onSet(snap(v)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = if (steps <= 200) steps else 0,
            colors = SliderDefaults.colors(thumbColor = colors.accentBlue, activeTrackColor = colors.accentBlue)
        )
    }
}

@Composable
private fun LivePriceChart(tape: com.dirk.kalshiodds.signal.scalp.ScalpTape) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Live Kalshi", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            String.format(Locale.US, "YES %.0f¢ · NO %.0f¢ · %d min left", tape.yesBid * 100, tape.noBid * 100, tape.tteMs / 60_000),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            drawLine(colors.border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            val yes = tape.yesBids.map { it.toFloat() }
            val no = tape.noBids.map { it.toFloat() }
            if (yes.size < 2) return@Canvas
            val all = yes + no
            val min = all.min()
            val max = all.max()
            val span = (max - min).coerceAtLeast(0.01f)
            fun path(points: List<Float>): Path {
                val p = Path()
                val last = points.lastIndex
                points.forEachIndexed { i, v ->
                    val x = size.width * i / last
                    val y = size.height * (1f - ((v - min) / span).coerceIn(0f, 1f))
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                return p
            }
            drawPath(path(yes), colors.up, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path(no), colors.down, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ScalpRow(rank: Int, account: ScalpAccount, onReset: () -> Unit = {}) {
    val colors = DipTheme.colors
    val pnl = account.pnlUsd()
    val pos = account.position
    val winRate = if (account.trades > 0) 100.0 * account.wins / account.trades else 0.0
    val pnlColor = if (pnl >= 0.0) colors.up else colors.down
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (pos != null) pnlColor.copy(alpha = 0.45f) else colors.border, RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(pnlColor.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$rank",
                        style = MaterialTheme.typography.labelLarge,
                        color = pnlColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (pos != null) {
                            Chip("OPEN", colors.up, colors.upContainer)
                        } else {
                            Chip("FLAT", colors.textSecondary, colors.surfaceAlt)
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    String.format(Locale.US, "%+.2f", pnl),
                    style = MaterialTheme.typography.titleMedium,
                    color = pnlColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "cash $%,.0f", account.cashUsd),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (account.cashUsd < 0.0) colors.down else colors.textSecondary
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(
                String.format(Locale.US, "%d trades", account.trades),
                colors.textPrimary,
                colors.surfaceAlt
            )
            Chip(
                if (account.trades > 0) String.format(Locale.US, "%.0f%%", winRate) else "no trades",
                if (winRate >= 50.0 && account.trades > 0) colors.up else colors.textSecondary,
                colors.surfaceAlt
            )
            if (account.sizeScale < 0.99) {
                Chip(
                    String.format(Locale.US, "size %.0f%%", account.sizeScale * 100.0),
                    colors.accentOrange,
                    colors.accentOrange.copy(alpha = 0.16f)
                )
            }
        }
        ScalpSpark(account)
        if (pos != null) {
            val off = ((pos.peakBid - pos.lastBid) * 100.0).coerceAtLeast(0.0)
            Text(
                String.format(
                    Locale.US,
                    "%s %d ct · in %.0f¢ · bid %.0f¢ · high %.0f¢ · %.0f¢ off",
                    pos.side,
                    pos.contracts,
                    pos.entry * 100.0,
                    pos.lastBid * 100.0,
                    pos.peakBid * 100.0,
                    off
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                String.format(Locale.US, "Stake $%,.0f · sells at 2¢ off the high", pos.stakeUsd),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        } else {
            Text(
                account.lastNote.ifBlank { "Flat. Waiting for a quote." },
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (account.lesson.isNotEmpty()) {
            Text(
                account.lesson,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentOrange,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            account.rule,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            Text("Reset", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HourChart(accounts: List<ScalpAccount>) {
    val colors = DipTheme.colors
    val byHour = IntArray(24)
    accounts.forEach { a ->
        val hour = ((a.trades * 3) % 24)
        byHour[hour] += a.wins - (a.trades - a.wins)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Best / worst hours", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(60.dp)) {
            val max = byHour.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
            val w = size.width / 24f
            byHour.forEachIndexed { i, v ->
                val h = (abs(v).toFloat() / max) * size.height
                val color = if (v >= 0) colors.up else colors.down
                drawRect(color, topLeft = Offset(i * w, size.height - h), size = androidx.compose.ui.geometry.Size(w - 2f, h))
            }
        }
    }
}

@Composable
private fun Chip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
