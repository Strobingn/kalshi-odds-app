package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.llm.AiAdjustmentLog
import com.dirk.kalshiodds.llm.AiWatchConfig
import com.dirk.kalshiodds.llm.AiWatchStore
import com.dirk.kalshiodds.llm.LlmProviders
import com.dirk.kalshiodds.llm.LlmSettings
import com.dirk.kalshiodds.llm.LlmUsageText
import com.dirk.kalshiodds.llm.ModelPrice
import com.dirk.kalshiodds.ui.components.FieldCard

/** Scalp-tab snapshot of the follower. Null on screenshot fixtures, so those layouts stay put. */
data class AiWatchPanelModel(
    val follows: Boolean,
    val usageLine: String,
    val notice: String,
    val lines: List<String>
)

@Composable
fun AiWatchPanel(model: AiWatchPanelModel, onFollows: (Boolean) -> Unit) {
    FieldCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI follows window", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    if (model.follows) "Paper settings only · adjusts with the live 15-minute window" else "Off · sliders stay where you set them",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = model.follows, onCheckedChange = onFollows)
        }
        if (model.usageLine.isNotBlank()) {
            Text(model.usageLine, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        if (model.notice.isNotBlank()) {
            Text(model.notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text("AI adjustments", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        if (model.lines.isEmpty()) {
            Text("No adjustments yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                model.lines.forEach { line ->
                    Text(line, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun rememberAiWatchPanel(store: AiWatchStore?): AiWatchPanelModel? {
    if (store == null) return null
    val cfg by store.config.collectAsStateWithLifecycle()
    val tick by store.summary.collectAsStateWithLifecycle()
    val notice by store.notice.collectAsStateWithLifecycle()
    val log by store.adjustments.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val summary = remember(tick, now / 60_000) { store.summaryAt(now) }
    val cap = cfg.budgetOrNull()
    val spent = store.todaySpend(now)
    val shown = if (cfg.followsWindow && cap != null && spent + 1e-9 >= cap) LlmUsageText.budgetNotice(spent, cap) else notice
    return AiWatchPanelModel(cfg.followsWindow, LlmUsageText.todayLine(summary), shown, log.map { AiAdjustmentLog.line(it) })
}

/** Settings card: usage, budget, provider, and the price table. FieldOps card on the settings page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiUsageSettingsCard(store: AiWatchStore, settings: LlmSettings) {
    val cfg by store.config.collectAsStateWithLifecycle()
    val tick by store.summary.collectAsStateWithLifecycle()
    val watcherNotice by store.notice.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val summary = remember(tick, now / 60_000) { store.summaryAt(now) }
    val cap = cfg.budgetOrNull()
    val spent = store.todaySpend(now)
    val notice = if (cfg.followsWindow && cap != null && spent + 1e-9 >= cap) LlmUsageText.budgetNotice(spent, cap) else watcherNotice
    var provider by remember { mutableStateOf(settings.providerId) }
    val slot = settings.readSlot(provider)
    var key by remember(provider) { mutableStateOf(slot.apiKey) }
    var model by remember(provider) { mutableStateOf(slot.model) }
    var base by remember(provider) { mutableStateOf(slot.baseUrl) }
    var interval by remember(cfg.intervalMs) { mutableStateOf((cfg.intervalMs / 1000).toString()) }
    var budgetOn by remember(cfg.budgetUsd) { mutableStateOf(cfg.budgetOrNull() != null) }
    var budget by remember(cfg.budgetUsd) { mutableStateOf(cfg.budgetUsd?.takeIf { it > 0.0 }?.toString().orEmpty()) }
    var prices by remember { mutableStateOf(store.prices()) }
    var addModel by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    FieldCard {
        Text("AI usage", color = colors.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Chat and the window watcher share this provider. The paper LLM strategy stays on the xAI key. Nothing here places orders or changes real money.",
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI follows window", modifier = Modifier.weight(1f), color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            Switch(checked = cfg.followsWindow, onCheckedChange = { store.setFollows(it) })
        }
        Text(LlmUsageText.period("Today", summary.today), color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
        Text(LlmUsageText.sources(summary.today), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(LlmUsageText.period("Last 7 days", summary.last7), color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
        Text(LlmUsageText.sources(summary.last7), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(LlmUsageText.period("All time", summary.allTime), color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
        Text(LlmUsageText.sources(summary.allTime), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        if (notice.isNotBlank()) Text(notice, color = colors.error, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily budget", modifier = Modifier.weight(1f), color = colors.onSurface)
            Switch(checked = budgetOn, onCheckedChange = { budgetOn = it })
        }
        if (budgetOn) {
            OutlinedTextField(
                budget, { budget = it },
                label = { Text("Budget $ / day (0 = off)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            interval, { interval = it },
            label = { Text("Follow interval (seconds)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Text("Provider for chat and the window watcher", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LlmProviders.ALL.forEach { spec ->
                TextButton(onClick = { provider = spec.id }) {
                    Text(spec.label, fontWeight = if (provider == spec.id) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        OutlinedTextField(
            key, { key = it },
            label = { Text("${LlmProviders.spec(provider).label} API key (encrypted on this phone)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(base, { base = it }, label = { Text("API base URL (OpenAI-compatible, https)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("$ / 1M tokens", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
        prices.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.model, modifier = Modifier.weight(1.4f), color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    row.inputPerMillion.toString(),
                    { prices = prices.replacePrice(index) { p -> p.copy(inputPerMillion = it.toDoubleOrNull() ?: p.inputPerMillion) } },
                    label = { Text("in") }, singleLine = true, modifier = Modifier.weight(0.8f)
                )
                OutlinedTextField(
                    row.outputPerMillion.toString(),
                    { prices = prices.replacePrice(index) { p -> p.copy(outputPerMillion = it.toDoubleOrNull() ?: p.outputPerMillion) } },
                    label = { Text("out") }, singleLine = true, modifier = Modifier.weight(0.8f)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(addModel, { addModel = it }, label = { Text("Add model id") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val name = addModel.trim()
                if (name.isNotEmpty() && prices.none { it.model.equals(name, true) }) prices = prices + ModelPrice(name, 0.0, 0.0)
                addModel = ""
            }) { Text("Add") }
        }
        TextButton(onClick = {
            settings.saveSlot(provider, key, model, base)
            val sec = interval.toLongOrNull() ?: (AiWatchConfig.DEFAULT_INTERVAL_MS / 1000)
            store.setIntervalMs(sec * 1000)
            store.setBudgetUsd(if (budgetOn) budget.toDoubleOrNull() else null)
            store.setPrices(prices)
        }) { Text("Save AI settings") }
    }
}

private fun List<ModelPrice>.replacePrice(index: Int, edit: (ModelPrice) -> ModelPrice): List<ModelPrice> =
    mapIndexed { i, row -> if (i == index) edit(row) else row }
