package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.roundToInt

/** Same label/value sliders as Claude's per-strategy settings. Saves on release, not on every drag frame. */
@Composable
internal fun StrategyKnob(label: String, value: Int, range: IntRange, tag: String, text: (Int) -> String = { "$it" }, onSet: (Int) -> Unit) {
    val c = DipTheme.colors
    var current by remember(value, range.first, range.last) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    Column(Modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text(current.roundToInt()), color = c.textPrimary, style = ArenaUi.mono(MaterialTheme.typography.bodyLarge), fontWeight = FontWeight.SemiBold)
        }
        Slider(value = current, onValueChange = { current = it.roundToInt().toFloat() },
            onValueChangeFinished = { onSet(current.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..maxOf(range.first + 1, range.last).toFloat(),
            enabled = range.last > range.first,
            steps = (range.last - range.first - 1).takeIf { it in 1..200 } ?: 0,
            colors = SliderDefaults.colors(thumbColor = c.accentBlue, activeTrackColor = c.accentBlue),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    }
}

@Composable
internal fun ScalpStrategySettingsContent(model: ScalpModels.Model, controls: ScalpControls, onBack: () -> Unit = {},
    onChange: (ScalpStrategySettings) -> Unit = {}, onEnabled: (Boolean) -> Unit = {}) {
    val c = DipTheme.colors
    val settings = controls.settings(model.code)
    val spec = ScalpV2.spec(model.code)
    val enabled = model.code !in controls.disabledStrategies
    val high = settings.maxEntryCents ?: if (controls.manual) controls.maxAskCents else (spec.maxAsk * 100).roundToInt()
    val low = settings.minEntryCents.coerceAtMost(high)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "settings-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to strategy", tint = c.accentBlue) }
                Text(model.label, color = c.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        item(key = "strategy-settings") { ArenaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                    Text(if (enabled) "Running · changes count from its next order" else "Switched off · opens nothing new", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = onEnabled, modifier = Modifier.testTag("STRATEGY_ENABLED"))
            }
            if (!controls.enabled || controls.killSwitch) Text("Global entries are paused. Existing protective exits keep running.", color = c.spot, style = MaterialTheme.typography.bodySmall)
            StrategyKnob("Until minute", settings.untilMinute, 1..14, "KNOB_UNTIL", { "minute $it" }) { onChange(settings.copy(untilMinute = it)) }
            StrategyKnob("Contracts an order", settings.contracts ?: if (controls.manual) controls.contracts else spec.clip, 1..1000, "KNOB_CONTRACTS") { onChange(settings.copy(contracts = it)) }
            if (controls.peakTrailEnabled) {
                StrategyKnob("Stop loss below fill", settings.stopLossPercent ?: controls.stopLossPercent, 1..99, "KNOB_STOP", { "$it%" }) { onChange(settings.copy(stopLossPercent = it)) }
            } else {
                StrategyKnob("Stop loss", settings.stopCents ?: if (controls.manual) controls.stopCents else ((spec.stop ?: ScalpV2.stopFor(spec.base)) * 100).roundToInt(), 1..99, "KNOB_STOP", { "$it¢" }) { onChange(settings.copy(stopCents = it)) }
            }
            StrategyKnob("Trailing stop", settings.trailCents ?: if (controls.peakTrailEnabled) controls.peakTrailCents else if (controls.manual) controls.trailCents else (spec.trail * 100).roundToInt(), 1..20, "KNOB_TRAIL", { "$it¢" }) { onChange(settings.copy(trailCents = it)) }
            StrategyKnob("Open at once, per side", settings.maxOpenPerSide, 1..20, "KNOB_MAXOPEN") { onChange(settings.copy(maxOpenPerSide = it)) }
            StrategyKnob("Lowest price it buys", low, 1..high, "KNOB_MIN", { "$it¢" }) { onChange(settings.copy(minEntryCents = it)) }
            StrategyKnob("Highest price it buys", high, low..50, "KNOB_MAX", { "$it¢" }) { onChange(settings.copy(maxEntryCents = it)) }
            StrategyKnob("Entry checks every", settings.cadenceSeconds, 0..30, "KNOB_CADENCE", { if (it == 0) "Live" else "$it s" }) { onChange(settings.copy(cadenceSeconds = it)) }
            StrategyKnob("Take profit after fees", settings.takeProfitCents ?: if (controls.manual) controls.takeProfitCents else 0, 0..99, "KNOB_TARGET", { if (it == 0) "Off" else "$it¢ / ct" }) { onChange(settings.copy(takeProfitCents = it)) }
            StrategyKnob("Maximum hold", settings.maxHoldSeconds ?: controls.maxHoldSeconds, 0..3600, "KNOB_HOLD", { if (it == 0) "No timer" else "$it s" }) { onChange(settings.copy(maxHoldSeconds = it)) }
            Text("Applies only to this V2 strategy. Unchanged values inherit global settings and learned adjustments. Filled positions keep their saved stops. Entry checks never slow protective exits.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text("50¢ buy ceiling · stop loss always active · total trade count stays unlimited unless you set a global limit. Until minute limits new entries; the final-minute exit policy still applies. Open-position limits do not create extra entry signals.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onChange(ScalpStrategySettings()) }, modifier = Modifier.fillMaxWidth()) { Text("Back to its defaults") }
        } }
    }
}
