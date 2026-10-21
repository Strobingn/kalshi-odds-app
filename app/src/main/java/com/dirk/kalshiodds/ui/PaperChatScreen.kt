package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.llm.PaperChatClient
import com.dirk.kalshiodds.llm.PaperChatKeys
import com.dirk.kalshiodds.llm.PaperChatProvider
import com.dirk.kalshiodds.llm.PaperChatException
import com.dirk.kalshiodds.llm.PaperTuning
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Research chat only: sends a bounded snapshot on explicit tap, never sends an order. */
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
    var status by remember { mutableStateOf("Enter a key for the selected provider; it remains on this device.") }
    var busy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(keys) { provider = withContext(Dispatchers.IO) { keys.selectedProvider() } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Home", tint = colors.accentBlue) }
                Text("AI research chat", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, modifier = Modifier.padding(top = 10.dp))
            }
        }
        item { ScalpModeTabs(selected = 2, onSelect = onSelectTab) }
        item {
            Text("With paper trading enabled and saved keys, AI reviews the current BTC window roughly every 60 seconds while this screen's app process is running. On a rate limit it switches providers if the other key is saved. Provider limits and charges depend on your plan. No live-order access.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        item { Text(state.paperAiReview, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall) }
        item { Text("Paper exit adjustment history:\n${tuning.audit()}", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperChatProvider.values().forEach { option ->
                    OutlinedButton(enabled = !busy && provider != option, onClick = {
                        provider = option
                        inputKey = ""
                        history = emptyList()
                        scope.launch {
                            status = runCatching { withContext(Dispatchers.IO) { keys.select(option) }; "Selected ${option.label}." }
                                .getOrElse { "Could not save provider selection." }
                        }
                    }) { Text(if (provider == option) "${option.label} ✓" else option.label) }
                }
            }
        }
        item { Text("Model: ${provider.model}", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedTextField(value = inputKey, onValueChange = { inputKey = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("${provider.label} API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        item {
            Button(enabled = inputKey.isNotBlank() && !busy, onClick = {
                val value = inputKey
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { keys.save(provider, value) }
                        inputKey = ""
                        status = "${provider.label} key saved on this device for future updates."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "Could not securely save the key. It was not saved in plaintext." }
                    finally { busy = false }
                }
            }) { Text("Save key") }
        }
        item { Text(status, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall) }
        item {
            Text("A timestamped market and paper summary is sent when you ask. Don't include secrets in chat. Answers are experimental; history stays in memory for this session.", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        history.forEach { (role, message) ->
            item { Text("${if (role == "user") "You" else "AI"}: $message", color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            OutlinedTextField(value = question, onValueChange = { question = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Ask about the paper experiment") })
        }
        item {
            Button(enabled = !busy && question.isNotBlank(), onClick = {
                val sentQuestion = question.trim()
                val selected = provider
                val market = state.snapshot?.allMarkets?.firstOrNull { it.ticker.startsWith("KXBTC15M", true) }
                scope.launch {
                    busy = true
                    try {
                        val snapshot = "Captured at ${System.currentTimeMillis()} ms UTC; BTC ticker=${market?.ticker ?: "unavailable"}; YES probability=${market?.yesProbabilityPercent ?: "unavailable"}%; paper open=${state.paper.openCount}; paper realized P&L=${state.paper.realizedPnlUsd}; V3 settled=${state.v3.settled.size}.\n" + withContext(Dispatchers.Default) { tuning.snapshot(state.paper.fills) }
                        val answer = client.askWithFallback(keys, selected, sentQuestion, snapshot, history)
                        provider = answer.provider
                        val reply = withContext(Dispatchers.IO) { tuning.applyResponse(answer.text, state.paper.fills, automatic = false) }
                        history = (history + listOf("user" to sentQuestion, "assistant" to reply)).takeLast(12)
                        question = ""
                        status = "${answer.provider.label} replied; model ${answer.provider.model}."
                    } catch (e: CancellationException) { throw e }
                    catch (e: PaperChatException) { status = e.message ?: "Request failed." }
                    catch (_: Exception) { status = "Request failed. Check your saved key and connection; re-enter the key if the device Keystore was reset." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Working…" else "Ask AI") }
        }
    }
}
