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

/** Status under the Auto / Force trade / Force bench chips. Auto-off does not say paused. */
internal fun benchStatusLine(override: String?, autoBenched: Boolean, frozen: Boolean = false, autoBenchEnabled: Boolean = true): String = when (override) {
    "ACTIVE" -> "Bench · forced trading"
    "BENCHED" -> "Bench · forced benched"
    else -> if (!autoBenchEnabled) "Bench · auto off"
        else if (frozen) "Bench · auto · ${com.dirk.kalshiodds.decision.insights.ScalpFreeze.BADGE} (no new paper trades) · Force trade unfreezes"
        else if (autoBenched) "Bench · auto · BENCHED (shadow only)" else "Bench · auto"
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
    val inheritedHigh = if (controls.manual) controls.maxAskCents else (spec.maxAsk * 100).roundToInt()
    val high = maxOf(settings.minEntryCents, settings.maxEntryCents ?: inheritedHigh).coerceAtMost(50)
    val low = settings.minEntryCents.coerceAtMost(high)
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
            val benched by com.dirk.kalshiodds.decision.insights.BenchRegistry.benched.collectAsState()
            val benchedNow = model.code in benched
            val frozenNow = com.dirk.kalshiodds.decision.insights.ScalpFreeze.isFrozen(com.dirk.kalshiodds.decision.insights.BenchRegistry.decisions.collectAsState().value[model.code])
            Text(benchStatusLine(ov, benchedNow, frozenNow, controls.autoBench), color = if (ov == "BENCHED" || (controls.autoBench && benchedNow)) c.spot else c.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("BENCH_STATUS"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Auto", "ACTIVE" to "Force trade", "BENCHED" to "Force bench").forEach { (v, label) ->
                    FilterChip(selected = ov == v, onClick = { onBenchOverride(v) }, label = { Text(label) },
                        modifier = Modifier.testTag(if (v == null) "BENCH_AUTO" else "BENCH_$v"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI learns", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(if (settings.aiLearns) "Gently moves one slider from this strategy's own paper trades (every 3 closes, 1¢ at a time, bounded). Your changes always win." else "Off · sliders stay exactly where you set them", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.aiLearns, onCheckedChange = { onChange(settings.copy(aiLearns = it).manual()) }, modifier = Modifier.testTag("STRATEGY_AI_LEARNS"))
            }
            if (settings.aiNote.isNotBlank()) Text("Last adjusted: ${settings.aiNote}", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            when {
                ScalpModels.isResearch(model) -> {
                    StrategyKnob("Edge needed after fees", settings.edgeCents ?: Research.defaultEdge(model.code), 0..30, "KNOB_EDGE", { "$it¢" }) { set(settings.copy(edgeCents = it)) }
                    StrategyKnob("Contracts an order", settings.contracts ?: controls.research.clip, 1..1000, "KNOB_CONTRACTS") { set(settings.copy(contracts = it)) }
                    val lateShown = if (Research.isLate(model.code)) Research.lateBand(model.code, null) else null
                    val researchMin = if (settings.minEntryCents > 1) settings.minEntryCents else lateShown?.let { Math.round(it.lo * 100).toInt() } ?: settings.minEntryCents
                    val researchMax = settings.maxEntryCents ?: lateShown?.let { Math.round(it.hi * 100).toInt() } ?: 99
                    StrategyKnob("Lowest price it buys", researchMin, 1..99, "KNOB_MIN", { "$it¢" }) { set(settings.copy(minEntryCents = it)) }
                    StrategyKnob("Highest price it buys", researchMax, researchMin..99, "KNOB_MAX", { "$it¢" }) { set(settings.copy(maxEntryCents = it)) }
                    Text("Research strategy: not bound by the 3¢ trail, window-start or >95% hold rules. Shared research limits (window exposure cap, Kelly, maker edge, late window) are in Global controls.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                ScalpModels.isV2(model) -> {
                    StrategyKnob("Until minute", settings.untilMinute, 1..14, "KNOB_UNTIL", { "minute $it" }) { set(settings.copy(untilMinute = it)) }
                    StrategyKnob("Contracts an order", settings.contracts ?: if (controls.manual) controls.contracts else spec.clip, 1..1000, "KNOB_CONTRACTS") { set(settings.copy(contracts = it)) }
                    if (controls.peakTrailEnabled) {
                        StrategyKnob("Stop loss below fill", settings.stopLossPercent ?: controls.stopLossPercent, 1..99, "KNOB_STOP", { "$it%" }) { set(settings.copy(stopLossPercent = it)) }
                    } else {
                        StrategyKnob("Stop loss", settings.stopCents ?: if (controls.manual) controls.stopCents else ((spec.stop ?: ScalpV2.stopFor(spec.base)) * 100).roundToInt(), 1..99, "KNOB_STOP", { "$it¢" }) { set(settings.copy(stopCents = it)) }
                    }
                    StrategyKnob("Trailing stop", settings.trailCents ?: if (controls.peakTrailEnabled) controls.peakTrailCents else if (controls.manual) controls.trailCents else (spec.trail * 100).roundToInt(), 1..20, "KNOB_TRAIL", { "$it¢" }) { set(settings.copy(trailCents = it)) }
                    StrategyKnob("Open at once, per side", settings.maxOpenPerSide, 1..20, "KNOB_MAXOPEN") { set(settings.copy(maxOpenPerSide = it)) }
                    StrategyKnob("Lowest price it buys", low, 1..high, "KNOB_MIN", { "$it¢" }) { set(settings.copy(minEntryCents = it)) }
                    StrategyKnob("Highest price it buys", high, low..55, "KNOB_MAX", { "$it¢" }) { set(settings.copy(maxEntryCents = it)) }
                    StrategyKnob("Entry checks every", settings.cadenceSeconds, 0..30, "KNOB_CADENCE", { if (it == 0) "Live" else "$it s" }) { set(settings.copy(cadenceSeconds = it)) }
                    StrategyKnob("Take profit after fees", settings.takeProfitCents ?: if (controls.manual) controls.takeProfitCents else 0, 0..99, "KNOB_TARGET", { if (it == 0) "Off" else "$it¢ / ct" }) { set(settings.copy(takeProfitCents = it)) }
                    StrategyKnob("Maximum hold", settings.maxHoldSeconds ?: controls.maxHoldSeconds, 0..3600, "KNOB_HOLD", { if (it == 0) "No timer" else "$it s" }) { set(settings.copy(maxHoldSeconds = it)) }
                    Text("Applies only to this V2 strategy. Overrides take priority over global and learned defaults; a chosen price floor also keeps the inherited ceiling from falling below it. Filled positions keep their saved stops. Entry checks never slow protective exits.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("55¢ buy ceiling · stop loss always active · total trade count stays unlimited unless you set a global limit. Until minute limits new entries; the final-minute exit policy still applies. Open-position limits do not create extra entry signals.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    StrategyKnob("Until minute", settings.untilMinute, 1..14, "KNOB_UNTIL", { "minute $it" }) { set(settings.copy(untilMinute = it)) }
                    val shownMin = FairGapTune.effectiveMinCents(model.code, settings)
                    val shownMax = settings.maxEntryCents ?: FairGapTune.defaultMaxCents(model.code) ?: 55
                    StrategyKnob("Lowest price it buys", shownMin, 1..55, "KNOB_MIN", { "$it¢" }) { set(settings.copy(minEntryCents = it)) }
                    StrategyKnob("Highest price it buys", shownMax, shownMin.coerceAtMost(55)..55, "KNOB_MAX", { "$it¢" }) { set(settings.copy(maxEntryCents = it)) }
                    StrategyKnob("Stop loss below fill", settings.stopLossPercent ?: 0, 0..99, "KNOB_STOP", { if (it == 0) "Strategy default" else "$it%" }) { set(settings.copy(stopLossPercent = it.takeIf { v -> v > 0 })) }
                    StrategyKnob("Take profit after fees", settings.takeProfitCents ?: 0, 0..99, "KNOB_TARGET", { if (it == 0) "Off" else "$it¢ / ct" }) { set(settings.copy(takeProfitCents = it)) }
                    StrategyKnob("Maximum hold", settings.maxHoldSeconds ?: 0, 0..3600, "KNOB_HOLD", { if (it == 0) "No timer" else "$it s" }) { set(settings.copy(maxHoldSeconds = it)) }
                    Text("Original strategy: these add to its built-in rules (3¢ trail, window-start entries, >95% hold rule stay on).", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { onChange(ScalpStrategySettings()) }, modifier = Modifier.fillMaxWidth()) { Text("Back to its defaults") }
        } }
    }
}
