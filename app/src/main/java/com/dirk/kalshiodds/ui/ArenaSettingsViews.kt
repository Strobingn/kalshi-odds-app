package com.dirk.kalshiodds.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.dirk.kalshiodds.signal.scalper.ScalpKnobs
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalpTriggers
import com.dirk.kalshiodds.signal.scalper.ScalpTuning
import com.dirk.kalshiodds.signal.scalper.Tuned
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.roundToInt

/**
 * One setting as a slider: the label and value above, snapped to [step];
 * it saves when you let go. [text] formats the value.
 */
@Composable
internal fun KnobSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    tag: String,
    changed: Boolean,
    text: (Double) -> String,
    onSet: (Double) -> Unit
) {
    val colors = DipTheme.colors
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(min, max).toFloat()) }
    fun snap(x: Float): Double = (min + Math.round((x - min) / step) * step).coerceIn(min, max)
    Column(Modifier.testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                text(snap(v)),
                style = ArenaUi.mono(MaterialTheme.typography.bodyMedium),
                fontWeight = FontWeight.SemiBold,
                color = if (changed) colors.accentBlue else colors.textPrimary
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

private fun cents(v: Double): String = "${v.roundToInt()}¢"

/**
 * Everything you can change on one strategy. Saved on the phone at once and
 * used from its next decision; a filled scalp keeps the take-profit it was
 * given, but stops and time-outs follow the new values. [modelTheta] is the
 * ML scalper's own cut, shown as its trigger's default.
 */
@Composable
internal fun StrategySettingsCard(st: ScalpStrategy, tuning: ScalpTuning, modelTheta: Double?) {
    val colors = DipTheme.colors
    var k by remember(st) { mutableStateOf(tuning.knobs(st)) }
    fun save(n: ScalpKnobs) {
        k = n
        tuning.set(st, n)
    }
    val t = Tuned(st, k, tuning.defaultContracts)
    val d = Tuned(st, ScalpKnobs(), tuning.defaultContracts)
    ArenaCard(Modifier.testTag("STRATEGY_SETTINGS")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Muted(if (k.enabled) "Running · changes count from its next order" else "Switched off · it opens nothing new")
            }
            Switch(checked = k.enabled, onCheckedChange = { save(k.copy(enabled = it)) }, modifier = Modifier.testTag("STRATEGY_ENABLED"))
        }
        ScalpTriggers.of(st)?.let { spec ->
            val def = if (spec.default.isNaN()) (modelTheta ?: 0.0) else spec.default
            KnobSlider(
                spec.label, t.trigger(def), spec.min, spec.max, spec.step, "KNOB_TRIGGER", k.trigger != null,
                { ScalpTriggers.text(spec, it) }
            ) { save(k.copy(trigger = if (kotlin.math.abs(it - def) < spec.step / 2) null else it)) }
        }
        KnobSlider(
            "Contracts an order", t.contracts.toDouble(), 1.0, ScalpTuning.MAX_CONTRACTS.toDouble(), 1.0, "KNOB_CONTRACTS",
            k.contracts != null, { "${it.roundToInt()}" }
        ) { save(k.copy(contracts = it.roundToInt().takeIf { c -> c != d.contracts })) }
        if (!st.hold) {
            KnobSlider(
                "Take profit", t.targetDollars * 100, 1.0, 30.0, 1.0, "KNOB_TARGET", k.targetCents != null, ::cents
            ) { save(k.copy(targetCents = it.takeIf { c -> c.roundToInt() != (d.targetDollars * 100).roundToInt() })) }
        }
        KnobSlider(
            if (st.hold) "Stop loss (0 = hold to the close)" else "Stop loss",
            t.stopDollars * 100, if (st.hold) 0.0 else 1.0, 50.0, 1.0, "KNOB_STOP", k.stopCents != null, ::cents
        ) { save(k.copy(stopCents = it.takeIf { c -> c.roundToInt() != (d.stopDollars * 100).roundToInt() })) }
        if (!st.hold) {
            KnobSlider(
                "Time-out", t.timeoutMs / 1000.0, 5.0, 600.0, 5.0, "KNOB_TIMEOUT", k.timeoutSeconds != null, { "${it.roundToInt()} s" }
            ) { save(k.copy(timeoutSeconds = it.roundToInt().takeIf { s -> s.toLong() * 1000L != d.timeoutMs })) }
        }
        KnobSlider(
            "Open at once, per side", t.maxPerSide.toDouble(), 1.0, 20.0, 1.0, "KNOB_MAXOPEN", k.maxPerSide != null, { "${it.roundToInt()}" }
        ) { save(k.copy(maxPerSide = it.roundToInt().takeIf { n -> n != d.maxPerSide })) }
        KnobSlider(
            "Lowest price it buys", t.entryMin * 100, 1.0, 99.0, 1.0, "KNOB_MIN", k.entryMinCents != null, ::cents
        ) { save(k.copy(entryMinCents = it.takeIf { c -> c.roundToInt() != (d.entryMin * 100).roundToInt() })) }
        KnobSlider(
            "Highest price it buys", t.entryMax * 100, 1.0, 99.0, 1.0, "KNOB_MAX", k.entryMaxCents != null, ::cents
        ) { save(k.copy(entryMaxCents = it.takeIf { c -> c.roundToInt() != (d.entryMax * 100).roundToInt() })) }
        KnobSlider(
            "Looks every", t.cadenceSeconds.toDouble(), 1.0, 30.0, 1.0, "KNOB_CADENCE", k.cadenceSeconds != null, { "${it.roundToInt()} s" }
        ) { save(k.copy(cadenceSeconds = it.roundToInt().takeIf { n -> n != d.cadenceSeconds })) }
        if (!k.isDefault) {
            OutlinedButton(onClick = { save(ScalpKnobs()) }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("STRATEGY_DEFAULTS")) {
                Text("Back to its defaults")
            }
        }
    }
}

/**
 * The arena's own settings page: every strategy's on / off switch, reset
 * every setting, and reset the paper record. Paper only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArenaSettingsPage(
    tuning: ScalpTuning,
    message: String?,
    onResetPaper: (() -> Unit)?,
    onOpenStrategy: (ScalpStrategy) -> Unit,
    onBack: () -> Unit
) {
    val colors = DipTheme.colors
    var rev by remember { mutableIntStateOf(0) }
    var askPaper by remember { mutableStateOf(false) }
    var askSettings by remember { mutableStateOf(false) }
    val changed = remember(rev) { tuning.changed() }
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("Arena settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("ARENA_SETTINGS_BACK")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the arena")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accentBlue
                )
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).testTag("ARENA_SETTINGS"),
            contentPadding = PaddingValues(start = ArenaUi.GUTTER, end = ArenaUi.GUTTER, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ArenaUi.GAP)
        ) {
            item(key = "paper") {
                ArenaCard {
                    Text("Paper record", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(
                        "Start every strategy again from \$0: all paper trades, totals and charts are cleared. " +
                            "The trade files are archived first. Your settings stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    if (onResetPaper != null) {
                        OutlinedButton(
                            onClick = { askPaper = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("ARENA_RESET_PAPER")
                        ) { Text("Reset the paper record", color = colors.down) }
                    }
                    message?.let { Muted(it) }
                }
            }
            item(key = "settings") {
                ArenaCard {
                    Text("Strategy settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(
                        if (changed.isEmpty()) "Every strategy runs on its defaults. Tap one to change its sizes, exits, price band and trigger."
                        else "${changed.size} strateg${if (changed.size == 1) "y has" else "ies have"} your settings. Tap one to change it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    if (changed.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { askSettings = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("ARENA_RESET_SETTINGS")
                        ) { Text("Put every strategy back to its defaults") }
                    }
                }
            }
            items(ScalpStrategy.values().toList(), key = { "set-${it.name}" }) { st ->
                val k = remember(rev, st) { tuning.knobs(st) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ArenaUi.ROW_SHAPE)
                        .background(colors.surface)
                        .clickable { onOpenStrategy(st) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("ARENA_SETTING_${st.name}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(st.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1)
                        val t = Tuned(st, k, tuning.defaultContracts)
                        val exits = if (st.hold) {
                            if (t.stopDollars > 0) "hold · stop ${cents(t.stopDollars * 100)}" else "hold to the close"
                        } else {
                            "+${cents(t.targetDollars * 100)} / −${cents(t.stopDollars * 100)} / ${t.timeoutMs / 1000} s"
                        }
                        Text(
                            "${t.contracts} ct · $exits" + if (k.isDefault || (k.copy(enabled = true).isDefault)) "" else " · changed",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (k.copy(enabled = true).isDefault) colors.textSecondary else colors.accentBlue,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = k.enabled,
                        onCheckedChange = { on ->
                            tuning.set(st, k.copy(enabled = on))
                            rev++
                        },
                        modifier = Modifier.testTag("ARENA_SWITCH_${st.name}")
                    )
                }
            }
        }
    }
    if (askPaper && onResetPaper != null) {
        AlertDialog(
            onDismissRequest = { askPaper = false },
            title = { Text("Reset the paper record?") },
            text = { Text("Every strategy's paper trades and totals start again from \$0. The trade files are archived first.") },
            confirmButton = {
                TextButton(onClick = {
                    askPaper = false
                    onResetPaper()
                }, modifier = Modifier.testTag("ARENA_RESET_PAPER_CONFIRM")) { Text("Reset", color = colors.down) }
            },
            dismissButton = { TextButton(onClick = { askPaper = false }) { Text("Cancel") } }
        )
    }
    if (askSettings) {
        AlertDialog(
            onDismissRequest = { askSettings = false },
            title = { Text("Put every strategy back to its defaults?") },
            text = { Text("Every size, exit, price band, trigger and on / off switch you changed goes back to how it shipped.") },
            confirmButton = {
                TextButton(onClick = {
                    askSettings = false
                    tuning.resetAll()
                    rev++
                }) { Text("Reset settings") }
            },
            dismissButton = { TextButton(onClick = { askSettings = false }) { Text("Cancel") } }
        )
    }
}
