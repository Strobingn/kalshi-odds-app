package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.llm.AiAdjustmentLog
import com.dirk.kalshiodds.llm.AiUsageCharts
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
    val lines: List<String>,
    val tweakBars: List<Pair<String, Int>> = emptyList()
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
        if (model.tweakBars.isNotEmpty()) {
            Text("Tweaks", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            TweakBars(model.tweakBars)
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
    val bars = log.groupingBy { it.scalper }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
    return AiWatchPanelModel(cfg.followsWindow, LlmUsageText.todayLine(summary), shown, log.map { AiAdjustmentLog.line(it) }, bars)
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

@Composable
private fun TweakBars(bars: List<Pair<String, Int>>) {
    val up = Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.SuccessFill)
    val down = Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.ErrorFill)
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val n = bars.size
        if (n == 0) return@Canvas
        val max = bars.maxOf { it.second }.coerceAtLeast(1).toFloat()
        val slot = size.width / n
        bars.forEachIndexed { i, (_, count) ->
            val h = size.height * count / max
            drawRoundRect(if (i % 2 == 0) up else down, Offset(i * slot + slot * 0.2f, size.height - h), Size(slot * 0.6f, h), CornerRadius(4f, 4f))
        }
    }
}

/** Charts beside the usage card. Numbers come only from recorded calls. */
@Composable
fun AiUsageChartsCard(store: AiWatchStore) {
    val tick by store.summary.collectAsStateWithLifecycle()
    val cfg by store.config.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val rows = remember(tick, now / 60_000) { store.hourBuckets() }
    val prices = remember(tick) { store.prices() }
    val dollars = remember(rows, prices, now / 60_000) { AiUsageCharts.dollarsToday(rows, prices, now) }
    val calls = remember(rows, now / 60_000) { AiUsageCharts.callsToday(rows, now) }
    val tokens = remember(rows, now / 60_000) { AiUsageCharts.tokensLast7(rows, now) }
    val spent = store.todaySpend(now)
    val projection = AiUsageCharts.projection(spent, now, cfg.budgetOrNull())
    val colors = MaterialTheme.colorScheme
    val up = Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.SuccessFill)
    val down = Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.ErrorFill)
    FieldCard {
        Text("AI usage charts", color = colors.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (projection.overPace && projection.budgetUsd != null) {
            Text(
                "On pace for ${LlmUsageText.money(projection.projectedUsd)} today, over the ${LlmUsageText.money(projection.budgetUsd)} budget.",
                color = colors.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text("Projected today ${LlmUsageText.money(projection.projectedUsd)} at the current pace.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text("Estimated $ per hour today", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
        if (dollars.all { it.dollars == 0.0 && it.cumulative == 0.0 }) Text("No calls recorded today.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        HourBars(dollars.map { it.dollars }, dollars.map { it.cumulative }, projection.budgetUsd, up, down)
        Text("Calls per hour by trigger", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
        StackedCalls(calls)
        Text("Tokens in vs out, last 7 days", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
        tokens.forEach { day ->
            Text("${day.day}: in ${LlmUsageText.tokens(day.prompt)} · out ${LlmUsageText.tokens(day.completion)}", color = colors.onSurface, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HourBars(dollars: List<Double>, cumulative: List<Double>, budget: Double?, up: Color, down: Color) {
    Canvas(Modifier.fillMaxWidth().height(88.dp)) {
        if (dollars.isEmpty()) return@Canvas
        val max = maxOf(dollars.maxOrNull() ?: 0.0, cumulative.maxOrNull() ?: 0.0, budget ?: 0.0, 0.01)
        val slot = size.width / dollars.size
        dollars.forEachIndexed { i, v ->
            val h = (v / max).toFloat() * size.height
            drawRoundRect(if (v > 0) up else down.copy(alpha = 0.35f), Offset(i * slot + 2f, size.height - h), Size((slot - 4f).coerceAtLeast(1f), h.coerceAtLeast(1f)), CornerRadius(3f, 3f))
        }
        var prev: Offset? = null
        cumulative.forEachIndexed { i, v ->
            val x = i * slot + slot / 2f
            val y = size.height - (v / max).toFloat() * size.height
            val cur = Offset(x, y)
            prev?.let { drawLine(down, it, cur, 3f, StrokeCap.Round) }
            prev = cur
        }
        if (budget != null && budget > 0) {
            val y = size.height - (budget / max).toFloat() * size.height
            drawLine(Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.StatusUrgent), Offset(0f, y), Offset(size.width, y), 2f)
        }
    }
}

@Composable
private fun StackedCalls(calls: List<AiUsageCharts.HourCalls>) {
    val palette = listOf(
        Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.SuccessFill),
        Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.ErrorFill),
        Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.OnSurface),
        Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.OnSurfaceVariant),
        Color(com.dirk.kalshiodds.ui.theme.FieldSwatch.Dark.Primary)
    )
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        if (calls.isEmpty()) return@Canvas
        val max = calls.maxOf { it.bySource.values.sum() }.coerceAtLeast(1).toFloat()
        val slot = size.width / calls.size
        calls.forEachIndexed { i, hour ->
            var y = size.height
            AiUsageCharts.TRIGGERS.forEachIndexed { s, src ->
                val n = (hour.bySource[src] ?: 0).toFloat()
                if (n <= 0f) return@forEachIndexed
                val h = n / max * size.height
                y -= h
                drawRect(palette[s % palette.size], Offset(i * slot + 2f, y), Size((slot - 4f).coerceAtLeast(1f), h))
            }
        }
    }
}
