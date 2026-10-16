package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Kalshi Scalper AI chat: ask about trades/results. Read-only explanations; never places real orders. */
@Composable
internal fun LlmChatPanel(rows: List<ScalpModels.Row>) {
    val c = DipTheme.colors
    val app = LocalContext.current.applicationContext as? KalshiOddsApp
    val container = app?.let { runCatching { it.container }.getOrNull() }
    val settings = container?.llmSettings
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(settings?.apiKey.orEmpty()) }
    var model by remember { mutableStateOf(settings?.model.orEmpty()) }
    var base by remember { mutableStateOf(settings?.baseUrl.orEmpty()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<Pair<String, String>>() }
    val llmStatus = container?.llmStrategy?.status?.collectAsState()?.value ?: "unavailable"
    ArenaCard {
        Text("AI chat", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Explains trades and results. Paper strategy status: $llmStatus. The LLM can never place real-money orders.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(key, { key = it }, label = { Text("LLM API key (stored encrypted on this phone)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(base, { base = it }, label = { Text("API base URL (OpenAI-compatible)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { settings?.apiKey = key; settings?.model = model; settings?.baseUrl = base }) { Text("Save key settings") }
        log.forEach { (who, msg) -> Text("$who: $msg", color = if (who == "You") c.textPrimary else c.textSecondary, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(input, { input = it }, label = { Text("Ask about trades or results") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && input.isNotBlank() && settings?.hasKey == true, onClick = {
            val q = input; input = ""; log += "You" to q; busy = true
            val ctx = rows.sortedByDescending { it.netUsd }.take(25).joinToString("\n") {
                String.format(Locale.US, "%s: net $%.2f, %d trades, %d wins", it.model.label, it.netUsd, it.roundTrips, it.wins)
            }
            scope.launch {
                val a = runCatching { withContext(Dispatchers.IO) { container!!.llmClient.chat(listOf(
                    "system" to "You explain a PAPER Kalshi BTC 15-minute scalping app's results. Be concise and plain. You cannot place orders.",
                    "user" to "Leaderboard (net after fees):\n$ctx\n\nQuestion: $q")) } }.getOrElse { "error: ${it.message}" }
                log += "AI" to a; busy = false
            }
        }) { Text(if (settings?.hasKey == true) (if (busy) "Thinking…" else "Ask") else "Needs key") }
    }
}
