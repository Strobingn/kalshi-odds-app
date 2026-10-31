package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.roundToInt

/**
 * Status under the Trading / Force trade / Force bench chips.
 * Trading means no manual override. It never means auto-freeze.
 */
@Suppress("UNUSED_PARAMETER")
internal fun benchStatusLine(override: String?, autoBenched: Boolean = false, frozen: Boolean = false, autoBenchEnabled: Boolean = false): String = when (override) {
    "ACTIVE" -> "Bench · forced trading"
    "BENCHED" -> "Bench · forced benched"
    else -> "Bench · trading · no manual override"
}

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
    onChange: (ScalpStrategySettings) -> Unit = {}, onEnabled: (Boolean) -> Unit = {}, onBenchOverride: (String?) -> Unit = {}) {
    val c = DipTheme.colors
    val settings = controls.settings(model.code)
    fun set(next: ScalpStrategySettings) = onChange(next.manual())
    val spec = ScalpV2.spec(model.code)
    val enabled = model.code !in controls.disabledStrategies
    val ceiling = PaperFleetDefaults.buyCeiling(model.code)
    val factoryMax = PaperFleetDefaults.buyMax(model.code)
    val low = settings.minEntryCents.coerceIn(PaperFleetDefaults.BUY_MIN, ceiling - 1)
    val high = (settings.maxEntryCents ?: factoryMax).coerceIn(low + 1, ceiling)
    val buyKnobHi = ceiling
    val late = LateWindowScalper.matches(model.code)
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            // 1.0.9 manual bench override (Auto · Force trade · Force bench). Observed, so a tap updates the line.
            val ov = controls.benchOverrides[model.code]
            Text(benchStatusLine(ov), color = if (ov == "BENCHED") c.spot else c.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("BENCH_STATUS"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Trading", "ACTIVE" to "Force trade", "BENCHED" to "Force bench").forEach { (v, label) ->
                    FilterChip(selected = ov == v, onClick = { onBenchOverride(v) }, label = { Text(label) },
                        modifier = Modifier.testTag(if (v == null) "BENCH_AUTO" else "BENCH_$v"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI learns", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(if (settings.aiLearns) {
                        if (late) "After every close it does not move this window. The buy band stays 15–${factoryMax}¢ and take profit stays 25¢ (sell target cannot exceed ${PaperFleetDefaults.PAYOUT_CENTS}¢). It never freezes or benches."
                        else "After every close it moves clip, stop, and the entry window. The step scales with that trade's P&L. The buy band stays 15–${PaperFleetDefaults.BUY_MAX}¢ and take profit stays 25¢. It never raises the buy max above ${PaperFleetDefaults.BUY_MAX}¢. It never freezes or benches. A change you make by hand resets the baseline."
                    } else "Off · sliders stay exactly where you set them", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.aiLearns, onCheckedChange = { onChange(settings.copy(aiLearns = it).manual()) }, modifier = Modifier.testTag("STRATEGY_AI_LEARNS"))
            }
            if (settings.aiNote.isNotBlank()) Text("Last adjusted: ${settings.aiNote}", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            when {
                ScalpModels.isResearch(model) -> {
                    StrategyKnob("Edge needed after fees", settings.edgeCents ?: Research.defaultEdge(model.code), 0..30, "KNOB_EDGE", { "$it¢" }) { set(settings.copy(edgeCents = it)) }
                    StrategyKnob("Contracts an order", PaperClip.sized(settings.contracts ?: controls.research.clip), PaperClip.MIN..1000, "KNOB_CONTRACTS") { set(settings.copy(contracts = it)) }
                    StrategyKnob("Lowest price it buys", low, PaperFleetDefaults.BUY_MIN..high, "KNOB_MIN", { "$it¢" }) { set(settings.copy(minEntryCents = it)) }
                    StrategyKnob("Highest price it buys", high, low..buyKnobHi, "KNOB_MAX", { "$it¢" }) { set(settings.copy(maxEntryCents = it)) }
                    Text("Research strategy: not bound by the 3¢ trail, window-start or >95% hold rules. Shared research limits (window exposure cap, Kelly, maker edge, late window) are in Global controls.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                ScalpModels.isV2(model) -> {
                    StrategyKnob("Until minute", settings.untilMinute, 1..14, "KNOB_UNTIL", { "minute $it" }) { set(settings.copy(untilMinute = it)) }
                    StrategyKnob("Contracts an order", PaperClip.sized((settings.contracts ?: if (controls.manual) controls.contracts else spec.clip).coerceAtLeast(1)), PaperClip.MIN..1000, "KNOB_CONTRACTS") { set(settings.copy(contracts = it)) }
                    if (controls.peakTrailEnabled) {
                        StrategyKnob("Stop loss below fill", settings.stopLossPercent ?: controls.stopLossPercent, 1..99, "KNOB_STOP", { "$it%" }) { set(settings.copy(stopLossPercent = it)) }
                    } else {
                        StrategyKnob("Stop loss", settings.stopCents ?: if (controls.manual) controls.stopCents else ((spec.stop ?: ScalpV2.stopFor(spec.base)) * 100).roundToInt(), 1..99, "KNOB_STOP", { "$it¢" }) { set(settings.copy(stopCents = it)) }
                    }
                    StrategyKnob("Trailing stop", settings.trailCents ?: if (controls.peakTrailEnabled) controls.peakTrailCents else if (controls.manual) controls.trailCents else (spec.trail * 100).roundToInt(), 1..20, "KNOB_TRAIL", { "$it¢" }) { set(settings.copy(trailCents = it)) }
                    StrategyKnob("Open at once, per side", settings.maxOpenPerSide, 0..20, "KNOB_MAXOPEN", { if (it == 0) "unlimited" else "$it" }) { set(settings.copy(maxOpenPerSide = it)) }
                    StrategyKnob("Lowest price it buys", low, PaperFleetDefaults.BUY_MIN..high, "KNOB_MIN", { "$it¢" }) { set(settings.copy(minEntryCents = it)) }
                    StrategyKnob("Highest price it buys", high, low..buyKnobHi, "KNOB_MAX", { "$it¢" }) { set(settings.copy(maxEntryCents = it)) }
                    StrategyKnob("Entry checks every", settings.cadenceSeconds, 0..30, "KNOB_CADENCE", { if (it == 0) "Live" else "$it s" }) { set(settings.copy(cadenceSeconds = it)) }
                    StrategyKnob("Take profit after fees", settings.takeProfitCents ?: if (controls.manual) controls.takeProfitCents else 0, 0..PaperFleetDefaults.PROFIT_HEAL_ABOVE, "KNOB_TARGET", { if (it == 0) "Off" else "$it¢ / ct" }) { set(settings.copy(takeProfitCents = it)) }
                    StrategyKnob("Maximum hold", settings.maxHoldSeconds ?: controls.maxHoldSeconds, 0..3600, "KNOB_HOLD", { if (it == 0) "No timer" else "$it s" }) { set(settings.copy(maxHoldSeconds = it)) }
                    Text("Applies only to this V2 strategy. Overrides take priority over global and learned defaults; a chosen price floor also keeps the inherited ceiling from falling below it. Filled positions keep their saved stops. Entry checks never slow protective exits.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("${ceiling}¢ buy ceiling · stop loss always active · contracts and re-entries per 15-minute window stay unlimited unless you set a limit. Each new entry still needs a fresh trigger. Until minute limits new entries; the final-minute exit policy still applies. Open-at-once is unlimited at 0 and does not create extra entry signals.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    StrategyKnob("Until minute", settings.untilMinute, 1..14, "KNOB_UNTIL", { "minute $it" }) { set(settings.copy(untilMinute = it)) }
                    StrategyKnob("Lowest price it buys", low, PaperFleetDefaults.BUY_MIN..high, "KNOB_MIN", { "$it¢" }) { set(settings.copy(minEntryCents = it)) }
                    StrategyKnob("Highest price it buys", high, low..buyKnobHi, "KNOB_MAX", { "$it¢" }) { set(settings.copy(maxEntryCents = it)) }
                    StrategyKnob("Stop loss below fill", settings.stopLossPercent ?: 0, 0..99, "KNOB_STOP", { if (it == 0) "Strategy default" else "$it%" }) { set(settings.copy(stopLossPercent = it.takeIf { v -> v > 0 })) }
                    StrategyKnob("Take profit after fees", settings.takeProfitCents ?: 0, 0..PaperFleetDefaults.PROFIT_HEAL_ABOVE, "KNOB_TARGET", { if (it == 0) "Off" else "$it¢ / ct" }) { set(settings.copy(takeProfitCents = it)) }
                    StrategyKnob("Maximum hold", settings.maxHoldSeconds ?: 0, 0..3600, "KNOB_HOLD", { if (it == 0) "No timer" else "$it s" }) { set(settings.copy(maxHoldSeconds = it)) }
                    Text("Original strategy: these add to its built-in rules (3¢ trail, window-start entries, >95% hold rule stay on).", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { onChange(PaperFleetDefaults.factory(model.code)) }, modifier = Modifier.fillMaxWidth()) { Text("Back to its defaults") }
        } }
    }
}
