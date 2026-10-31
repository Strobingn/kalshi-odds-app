package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun SwarmScalpSettingsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember(ctx) { SavedScalpControls(ctx) }
    DisposableEffect(store) { onDispose { store.close() } }
    val saved by store.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ScalpModels.Model?>(null) }
    var global by remember { mutableStateOf(false) }
    fun back() { if (selected != null) selected = null else if (global) global = false else onClose() }
    val app = ctx.applicationContext as? com.dirk.kalshiodds.KalshiOddsApp
    fun note(before: ScalpControls, after: ScalpControls) { runCatching { app?.container?.aiWatcher?.noteControls(before, after) } }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val model = selected
        if (model != null) ScalpStrategySettingsContent(model, saved, onBack = { selected = null },
            onChange = { next -> val current = store.state.value; val savedNext = current.copy(strategySettings = current.strategySettings + (model.code to next)); note(current, savedNext); store.save(savedNext) },
            onEnabled = { enabled -> val current = store.state.value; val savedNext = current.copy(disabledStrategies = if (enabled) current.disabledStrategies - model.code else current.disabledStrategies + model.code); note(current, savedNext); store.save(savedNext) },
            onBenchOverride = { v -> val cur = store.state.value; store.save(cur.copy(benchOverrides = if (v == null) cur.benchOverrides - model.code else cur.benchOverrides + (model.code to v))) })
        else if (global) SwarmScalpSettingsContent(saved, onBack = { global = false }) { next -> val current = store.state.value; note(current, next); store.save(next); global = false }
        else ScalpSettingsChooser(onClose, { global = true }, { selected = it })
    }
}


/** The arena gear opens the strategy picker first; global risk controls are one tap away. */
@Composable
internal fun ScalpSettingsChooser(onBack: () -> Unit = {}, onGlobal: () -> Unit = {}, onStrategy: (ScalpModels.Model) -> Unit = {}) {
    val c = DipTheme.colors
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.accentBlue) }
                Text("Strategy settings", color = c.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text("Choose any strategy to adjust its sliders or its AI learns switch. Changes apply to its next order.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        item(key = "global") { OutlinedButton(onClick = onGlobal, modifier = Modifier.fillMaxWidth()) { Text("Global controls & limits") } }
        items(ScalpModels.ALL, key = { it.code }) { model ->
            ArenaCard {
                TextButton(onClick = { onStrategy(model) }, modifier = Modifier.fillMaxWidth()) {
                    Text(model.label, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text("Tune →", color = c.accentBlue)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwarmScalpSettingsContent(initial: ScalpControls, onBack: () -> Unit = {}, onSave: (ScalpControls) -> Unit = {}) {
    var draft by remember { mutableStateOf(initial) }
    var errors by remember { mutableStateOf(emptySet<String>()) }
    val c = DipTheme.colors
    fun number(label: String, valid: Boolean) { errors = if (valid) errors - label else errors + label }
    Scaffold(containerColor = c.bg, topBar = {
        TopAppBar(title = { Text("Scalping settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = { TextButton(onClick = { onSave(draft.normalized()) }, enabled = errors.isEmpty()) { Text("Save") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, titleContentColor = c.textPrimary))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("V2 paper scalpers", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, fontWeight = FontWeight.Bold)
                Text("Swarm-style controls for every V2 strategy. Save applies entry controls immediately; risk parameters are fixed when each new trade starts. Originals keep their own rules.", color = c.textSecondary) }
            item { SwarmCard("Run controls") {
                SwarmToggle("Allow new entries", draft.enabled) { draft = draft.copy(enabled = it) }
                SwarmToggle("Kill switch · block new entries", draft.killSwitch) { draft = draft.copy(killSwitch = it) }
                SwarmToggle("Adaptive tuning · learning always runs", draft.adaptive) { draft = draft.copy(adaptive = it) }
                Text("Pausing entries keeps existing stops and exits running. Trade-count limits are unlimited when set to 0.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            } }
            item { SwarmCard("Peak trail and protective stop") {
                SwarmToggle("Use peak trail + percent stop", draft.peakTrailEnabled) { draft = draft.copy(peakTrailEnabled = it) }
                SwarmNumber("Peak trailing distance (¢)", draft.peakTrailCents.toDouble(), 1.0..20.0, draft.peakTrailEnabled, ::number) { draft = draft.copy(peakTrailCents = it.toInt()) }
                SwarmNumber("Stop loss below fill price (%)", draft.stopLossPercent.toDouble(), 1.0..99.0, draft.peakTrailEnabled, ::number) { draft = draft.copy(stopLossPercent = it.toInt()) }
                Text("Active from entry; no profit threshold. A 3¢ trail holds at 90¢, triggers at 87¢, then moves to 92¢ if the bid rises to 95¢. The percent stop uses the actual fill price before fees. Both use the held side's bid; exits fill at later available bids and may slip.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text("Applies to new V2 trades. Existing trades keep saved rules. Optional profit targets, hold timers and the final-minute exit check still apply.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            } }
            item { SwarmCard("Entry and exit parameters") {
                Text("Global maximum entry is ${draft.maxAskCents}¢. No scalper buys above ${PaperFleetDefaults.BUY_CEILING}¢ except late window, which buys up to ${PaperFleetDefaults.LATE_BUY_MAX}¢. A per-strategy band can sit under that.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                SwarmToggle("Use my parameters", draft.manual) { draft = draft.copy(manual = it) }
                Text(if (draft.manual) "Your values override adaptive entry/target settings and legacy exits when peak mode is off." else "Strategy entries and learned adjustments are active. Peak-mode exits are controlled above.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                SwarmNumber("Maximum entry price (¢)", draft.maxAskCents.toDouble(), 1.0..PaperFleetDefaults.BUY_CEILING.toDouble(), draft.manual, ::number) { draft = draft.copy(maxAskCents = it.toInt()) }
                SwarmNumber("Contracts per entry", draft.contracts.toDouble(), PaperClip.MIN.toDouble()..1000.0, draft.manual, ::number) { draft = draft.copy(contracts = it.toInt()) }
                SwarmNumber("Stop-loss distance (¢)", draft.stopCents.toDouble(), 1.0..99.0, draft.manual && !draft.peakTrailEnabled, ::number) { draft = draft.copy(stopCents = it.toInt()) }
                SwarmNumber("Net take-profit (¢/contract; 0 = off)", draft.takeProfitCents.toDouble(), 0.0..99.0, draft.manual, ::number) { draft = draft.copy(takeProfitCents = it.toInt()) }
                SwarmNumber("Trailing distance (¢)", draft.trailCents.toDouble(), 1.0..20.0, draft.manual && !draft.peakTrailEnabled, ::number) { draft = draft.copy(trailCents = it.toInt()) }
                SwarmNumber("Arm trail at net gain (¢/contract)", draft.armCents.toDouble(), 1.0..99.0, draft.manual && !draft.peakTrailEnabled, ::number) { draft = draft.copy(armCents = it.toInt()) }
                SwarmNumber("Max hold (seconds; 0 = no hold timer)", draft.maxHoldSeconds.toDouble(), 0.0..3600.0, true, ::number) { draft = draft.copy(maxHoldSeconds = it.toInt()) }
                Text("Net take-profit and legacy trail arming include fees. Peak-mode distance and percent stop use prices. Every position keeps its saved protective exits.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            } }
            item { SwarmCard("Research strategies (shared)") {
                val r = draft.research
                Text("Window exposure cap: ${if (r.windowCapUsd <= 0.0) "$0 unlimited" else "$${Math.round(r.windowCapUsd)}"} across all Research strategies. Contracts and re-entries per 15-minute window are unlimited unless you set a limit. Each new entry still needs a fresh trigger.", color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = r.windowCapUsd.toFloat().coerceIn(0f, 2000f), onValueChange = { draft = draft.copy(research = r.copy(windowCapUsd = Math.round(it).toDouble())) }, valueRange = 0f..2000f)
                Text("Kelly fraction: ${Math.round(r.kellyFraction * 100)}% of Kelly · reference bankroll $${Math.round(r.kellyBankrollUsd)}", color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = r.kellyFraction.toFloat(), onValueChange = { draft = draft.copy(research = r.copy(kellyFraction = Math.round(it * 20) / 20.0)) }, valueRange = 0.05f..1f)
                Text("Maker bid sits ${Math.round(r.makerEdgeCents)}¢ below fair value", color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = r.makerEdgeCents.toFloat(), onValueChange = { draft = draft.copy(research = r.copy(makerEdgeCents = Math.round(it).toDouble())) }, valueRange = 0f..15f)
                Text("Late favourite window: last ${r.lateMinS / 60.0}–${r.lateMaxS / 60.0} min", color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = r.lateMaxS.toFloat(), onValueChange = { draft = draft.copy(research = r.copy(lateMaxS = (Math.round(it / 30f) * 30).coerceAtLeast(r.lateMinS))) }, valueRange = 60f..600f)
            } }
            item { SwarmCard("Sizing and optional limits") {
                Text("Winners bet bigger: ${draft.greenContracts} contracts per trade once a scalper is net positive after fees (20+ closes), filled by walking real book depth", color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(value = draft.greenContracts.toFloat(), onValueChange = { draft = draft.copy(greenContracts = Math.round(it)) }, valueRange = PaperClip.MIN.toFloat()..500f)
                SwarmNumber("Max stake including fee ($; 0 = no cap)", draft.maxStakeUsd, 0.0..100000.0, true, ::number) { draft = draft.copy(maxStakeUsd = it) }
                SwarmNumber("Entries per hour (0 = unlimited)", draft.maxTradesPerHour.toDouble(), 0.0..10000.0, true, ::number) { draft = draft.copy(maxTradesPerHour = it.toInt()) }
                SwarmNumber("Open positions (0 = unlimited)", draft.maxOpen.toDouble(), 0.0..1000.0, true, ::number) { draft = draft.copy(maxOpen = it.toInt()) }
                SwarmNumber("Daily net loss limit ($; 0 = off)", draft.maxDailyLossUsd, 0.0..100000.0, true, ::number) { draft = draft.copy(maxDailyLossUsd = it) }
                Text("Daily loss uses realized V2 results after fees and resets at midnight Eastern. Counts and limits survive restarts.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            } }
            item { SwarmCard("Reality check & alerts") {
                Text("Auto-bench is off. A losing scalper keeps trading. Only Force bench, on that scalper's settings, stops new paper trades.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                SwarmNumber("Estimated slippage ($ cents/contract/leg)", draft.slipCents, 0.0..10.0, true, ::number) { draft = draft.copy(slipCents = it) }
                SwarmToggle("Alert: hot streak (5 wins in a row)", draft.alertHotStreak) { draft = draft.copy(alertHotStreak = it) }
                SwarmToggle("Alert: big single-trade loss", draft.alertBigLoss) { draft = draft.copy(alertBigLoss = it) }
                SwarmNumber("Big loss threshold ($)", draft.bigLossUsd, 0.5..10000.0, draft.alertBigLoss, ::number) { draft = draft.copy(bigLossUsd = it) }
                SwarmToggle("Alert: strong AI call (≥70% UP/DOWN)", draft.alertStrongAi) { draft = draft.copy(alertStrongAi = it) }
                Text("Paper only. Each alert fires once per event.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            } }
            item { SwarmCard("Optional dip filter") {
                SwarmNumber("Feature window (seconds)", draft.windowSeconds.toDouble(), 10.0..600.0, true, ::number) { draft = draft.copy(windowSeconds = it.toInt()) }
                SwarmNumber("Min dip below EMA (¢; 0 = off)", draft.dipCents, 0.0..30.0, true, ::number) { draft = draft.copy(dipCents = it) }
                Text("Adds a pullback requirement to the original entry signal. A positive threshold can suppress momentum entries; 0 preserves each strategy's own signal.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("V2 strategies", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                TextButton(onClick = { draft = draft.copy(disabledStrategies = emptySet()) }) { Text("Enable all") }
            } }
            items(ScalpModels.V2_MODELS, key = { it.code }) { model ->
                SwarmCard { SwarmToggle(model.label, model.code !in draft.disabledStrategies) { enabled ->
                    draft = draft.copy(disabledStrategies = if (enabled) draft.disabledStrategies - model.code else draft.disabledStrategies + model.code)
                } }
            }
            item { OutlinedButton(onClick = { draft = ScalpControls(); errors = emptySet() }, modifier = Modifier.fillMaxWidth()) { Text("Restore defaults · unlimited entries") } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun SwarmCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = DipTheme.colors
    Surface(color = c.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary) }
            content()
        }
    }
}
@Composable private fun SwarmToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), color = DipTheme.colors.textPrimary)
        Switch(checked, onCheckedChange = onChange)
    }
}
@Composable private fun SwarmNumber(label: String, value: Double, range: ClosedFloatingPointRange<Double>, enabled: Boolean,
                                   validity: (String, Boolean) -> Unit, onChange: (Double) -> Unit) {
    var raw by rememberSaveable(label) { mutableStateOf(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()) }
    LaunchedEffect(value) { if (raw.toDoubleOrNull() != value) raw = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString() }
    val valid = raw.toDoubleOrNull()?.let { it.isFinite() && it in range && (label.contains("$") || label.startsWith("Min dip") || it % 1.0 == 0.0) } == true
    LaunchedEffect(valid, enabled) { validity(label, !enabled || valid) }
    OutlinedTextField(value = raw, onValueChange = { text ->
        raw = text
        val n = text.toDoubleOrNull()
        if (n != null && n.isFinite() && n in range && (label.contains("$") || label.startsWith("Min dip") || n % 1.0 == 0.0)) onChange(n)
    }, label = { Text(label) }, enabled = enabled, singleLine = true, isError = enabled && !valid,
        supportingText = if (enabled && !valid) ({ Text("Enter ${range.start}–${range.endInclusive}") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}
