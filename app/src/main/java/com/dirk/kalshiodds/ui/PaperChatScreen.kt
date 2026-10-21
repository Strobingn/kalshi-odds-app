package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.llm.PaperChatClient
import com.dirk.kalshiodds.llm.PaperChatException
import com.dirk.kalshiodds.llm.PaperChatKeys
import com.dirk.kalshiodds.llm.PaperChatProvider
import com.dirk.kalshiodds.llm.PaperTuning
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PaperChatScreen(state: OddsUiState, onBack: () -> Unit, onSelectTab: (Int) -> Unit) {
    val colors = DipTheme.colors
    val context = LocalContext.current.applicationContext
    val keys = remember(context) { PaperChatKeys(context) }
    val client = remember { PaperChatClient() }
    val tuning = remember(context) { PaperTuning(context) }
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(PaperChatProvider.GROQ) }
    var inputKey by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Save a Groq or Mistral key in Provider settings.") }
    var busy by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showTrades by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(keys) { provider = withContext(Dispatchers.IO) { keys.selectedProvider() } }
    val feed = state.paperFeedChart
    val open = state.paper.fills.filter { !it.settled && it.source.startsWith("AI ") }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.accentBlue) }
            Text("Paper AI", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            TextButton(onClick = { settingsOpen = !settingsOpen }) { Text(if (settingsOpen) "Done" else "Settings") }
        }
        ScalpModeTabs(selected = 2, onSelect = onSelectTab)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ChatCard {
                    Text("LATEST AI REVIEW", style = MaterialTheme.typography.labelLarge, color = colors.accentBlue)
                    Text(state.paperAiReview, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                    Text("One-second on-device observations · AI review about every 60 seconds", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
            if (history.isNotEmpty()) {
                item { Text("Conversation · newest first", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary) }
                history.asReversed().forEach { (role, message) ->
                    item { ChatCard { Text(if (role == "assistant") "AI" else "YOU", color = colors.accentBlue, style = MaterialTheme.typography.labelLarge); Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary) } }
                }
            }
            item {
                ChatCard {
                    Text("LIVE WINDOW", style = MaterialTheme.typography.labelLarge, color = colors.accentBlue)
                    Text(state.paperLiveFeed.lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    val age = feed.lastOrNull()?.quoteAgeMs
                    Text(if (age == null) "Kalshi quote age unknown · spot freshness unknown" else "Last Kalshi tick: ${age / 1000}s ago · spot freshness unknown", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
            item { FeedChart("YES bid · gaps = stale or unknown", "¢", feed.map { if (it.quoteAgeMs != null && it.quoteAgeMs <= 10_000) it.yesBid?.times(100) else null }, colors.accentBlue) }
            item { FeedChart("NO bid · gaps = stale or unknown", "¢", feed.map { if (it.quoteAgeMs != null && it.quoteAgeMs <= 10_000) it.noBid?.times(100) else null }, MaterialTheme.colorScheme.error) }
            item { FeedChart("Bitcoin spot", "$", feed.map { it.spot }, MaterialTheme.colorScheme.tertiary) }
            item { FeedChart("Paper realized P&L", "$", feed.map { it.realizedPaper }, MaterialTheme.colorScheme.primary) }
            item { FeedChart("Open paper positions", "positions", feed.map { it.openPaper.toDouble() }, colors.accentBlue) }
            item {
                ChatCard {
                    Text("PAPER POSITIONS · ${open.size} open", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text("Realized after fees: ${String.format(Locale.US, "%+.2f", state.paper.realizedPnlUsd)} USD", color = colors.textSecondary)
                    TextButton(onClick = { showTrades = !showTrades }) { Text(if (showTrades) "Hide open positions" else "Show open positions") }
                    if (showTrades && open.isEmpty()) Text("No open AI paper positions.", color = colors.textSecondary)
                }
            }
            if (showTrades) open.forEach { f ->
                item(key = f.id) {
                    ChatCard { Text("${f.strategyVersion ?: "paper"} · ${f.side} · ${f.contracts} contracts\n${f.ticker} · entry ${String.format(Locale.US, "%.1f", f.limitPrice * 100)}¢ · peak bid ${String.format(Locale.US, "%.1f", f.highWaterMarkPrice * 100)}¢", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary) }
                }
            }
            if (settingsOpen) {
                item {
                    ChatCard {
                        Text("PROVIDER SETTINGS", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Text("A saved key is required. Free-tier availability and charges depend on your account. If one provider fails, the app tries the other saved key; failures never display keys.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        Row {
                            PaperChatProvider.values().forEach { option ->
                                TextButton(enabled = !busy && provider != option, onClick = {
                                    provider = option; inputKey = ""; history = emptyList()
                                    scope.launch { status = runCatching { withContext(Dispatchers.IO) { keys.select(option) }; "Selected ${option.label}." }.getOrElse { "Could not save provider selection." } }
                                }) { Text(if (provider == option) "${option.label} ✓" else option.label) }
                            }
                        }
                        Text("Model: ${provider.model}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        OutlinedTextField(value = inputKey, onValueChange = { inputKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("${provider.label} API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Button(enabled = inputKey.isNotBlank() && !busy, onClick = {
                            val value = inputKey
                            scope.launch {
                                busy = true
                                try { withContext(Dispatchers.IO) { keys.save(provider, value) }; inputKey = ""; status = "${provider.label} key saved securely on this device." }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { status = "Could not securely save the key." }
                                finally { busy = false }
                            }
                        }) { Text("Save key") }
                        Text("Changes: ${tuning.audit()}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            }
            item { Text("$status\nThe feed is device-local; sampled history and paper positions are sent with each request. No live-order access. Android may stop background monitoring.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = question, onValueChange = { question = it.take(2000) }, modifier = Modifier.weight(1f), label = { Text("Ask or adjust paper settings") }, maxLines = 3)
            Button(enabled = !busy && question.isNotBlank(), onClick = {
                val sentQuestion = question.trim(); val selected = provider
                scope.launch {
                    busy = true
                    try {
                        val snapshot = state.paperLiveFeed + "\n" + withContext(Dispatchers.Default) { tuning.snapshot(state.paper.fills) }
                        val answer = client.askWithFallback(keys, selected, sentQuestion, snapshot, history)
                        provider = answer.provider
                        val reply = withContext(Dispatchers.IO) { tuning.applyResponse(answer.text, state.paper.fills, automatic = false) }
                        history = (history + listOf("user" to sentQuestion, "assistant" to reply)).takeLast(12)
                        question = ""; status = "${answer.provider.label} replied · ${answer.provider.model}."
                    } catch (e: CancellationException) { throw e }
                    catch (e: PaperChatException) { status = e.message ?: "Request failed." }
                    catch (_: Exception) { status = "Request failed. Check your saved key, connection, and provider plan." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "…" else "Send") }
        }
    }
}

@Composable
private fun ChatCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun FeedChart(title: String, unit: String, values: List<Double?>, lineColor: Color) {
    val colors = DipTheme.colors
    val valid = values.filterNotNull().filter { it.isFinite() }
    ChatCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(valid.lastOrNull()?.let { String.format(Locale.US, "%s%.2f", if (unit == "$") "$" else "", it) + if (unit == "¢") "¢" else "" } ?: "Waiting", color = colors.textPrimary)
        }
        if (valid.size < 2) Text("Waiting for more observations", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        else {
            val min = valid.minOrNull() ?: 0.0
            val max = valid.maxOrNull() ?: min
            val span = (max - min).coerceAtLeast(0.000001)
            Canvas(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription = "$title history, ${valid.size} samples, lowest $min and highest $max" }) {
                var previous: Offset? = null
                values.forEachIndexed { index, value ->
                    if (value == null || !value.isFinite()) { previous = null } else {
                        val x = if (values.size <= 1) 0f else size.width * index / (values.size - 1)
                        val y = size.height - ((value - min) / span).toFloat() * (size.height - 8f) - 4f
                        val point = Offset(x, y)
                        previous?.let { drawLine(lineColor, it, point, strokeWidth = 3.dp.toPx()) }
                        previous = point
                    }
                }
            }
            Text("Low ${String.format(Locale.US, "%.2f", min)} · High ${String.format(Locale.US, "%.2f", max)} $unit · 15-second samples", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}
