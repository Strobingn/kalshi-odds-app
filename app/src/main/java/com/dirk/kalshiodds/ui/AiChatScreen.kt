package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.llm.ChatMessage
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** 1.0.4: the AI bottom tab. Persistent chat, always-visible input bar, confirm-gated PAPER settings tool. */
@Composable
fun AiChatScreen(viewModel: OddsViewModel) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) { // keep the leaderboard fresh for the chat context, like the Scalp tab does
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) viewModel.setScalpTabVisible(true)
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.setScalpTabVisible(false)
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) viewModel.setScalpTabVisible(true)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.setScalpTabVisible(false) }
    }
    val app = LocalContext.current.applicationContext as? KalshiOddsApp
    val container = app?.let { runCatching { it.container }.getOrNull() }
    val chat = container?.aiChat
    val st by viewModel.scalpTab.collectAsStateWithLifecycle()
    val messages = chat?.messages?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val busy = chat?.busy?.collectAsStateWithLifecycle()?.value ?: false
    val status = container?.llmStrategy?.status?.collectAsStateWithLifecycle()?.value ?: "unavailable"
    val settings = container?.llmSettings
    val ready = settings?.chatReady == true
    val providerLabel = settings?.let { com.dirk.kalshiodds.llm.LlmProviders.spec(it.providerId).label }.orEmpty()
    val onSend: (String) -> Unit = { q ->
            val rows = KashiArenaData.rows(st, true, KashiArenaData.Sort.NET) + KashiArenaData.rows(st, false, KashiArenaData.Sort.NET)
            val board = rows.sortedByDescending { it.netUsd }.take(25).joinToString("\n") {
                String.format(Locale.US, "%s [%s]: net $%.2f, %d trades, %d wins", it.model.label, it.model.code, it.netUsd, it.roundTrips, it.wins)
            }
            // The current KXBTC15M window is attached inside the controller at send time, from live marks.
            val ctx = "Recent scalper results (net):\n" + board + "\nLLM paper strategy status (separate; not a chat answer): $status"
            chat?.send(q, ctx) ?: Unit
    }
    // 1.0.9: "Ask the AI why" — a tapped trade's prefilled question is sent once.
    val pendingAsk by com.dirk.kalshiodds.decision.insights.AskAiWhy.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingAsk, chat, busy) {
        if (pendingAsk != null && chat != null && !busy) com.dirk.kalshiodds.decision.insights.AskAiWhy.take()?.let(onSend)
    }
    val endpoint = settings?.chatEndpoint()
    AiChatContent(
        messages = messages, busy = busy, strategyStatus = status, hasKey = ready, providerLabel = providerLabel,
        initialKey = endpoint?.apiKey.orEmpty(), initialModel = endpoint?.model.orEmpty(), initialBase = endpoint?.baseUrl.orEmpty(),
        onSaveKey = { k, m, b -> settings?.saveSlot(settings.providerId, k, m, b) },
        onSend = onSend,
        onApply = { chat?.applyProposal(it) }, onCancel = { chat?.cancelProposal(it) }, onClear = { chat?.clear() },
        unavailable = chat == null
    )
}

/** Stateless AI tab (Paparazzi-tested). The input bar is pinned below a weighted list and lifted by imePadding. */
@Composable
fun AiChatContent(
    messages: List<ChatMessage>, busy: Boolean, strategyStatus: String, hasKey: Boolean,
    initialKey: String = "", initialModel: String = "", initialBase: String = "",
    providerLabel: String = "",
    onSaveKey: (String, String, String) -> Unit = { _, _, _ -> }, onSend: (String) -> Unit = {},
    onApply: (Long) -> Unit = {}, onCancel: (Long) -> Unit = {}, onClear: () -> Unit = {}, unavailable: Boolean = false
) {
    val c = DipTheme.colors
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(!hasKey) }
    val list = rememberLazyListState()
    val itemCount = messages.size + if (busy) 1 else 0
    LaunchedEffect(itemCount) { if (itemCount > 0) list.animateScrollToItem(itemCount - 1) }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear chat history?") },
        text = { Text("Deletes every message, including the settings-change log, from this phone. Settings themselves are not changed.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, modifier = Modifier.testTag("AI_CLEAR_CONFIRM")) { Text("Clear", color = c.down) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(dipContentInsets()).testTag("AI_TAB")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI", color = c.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("PAPER ONLY · never places real-money orders", color = c.spot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { showKey = !showKey }) { Icon(Icons.Outlined.Key, "LLM key settings", tint = c.accentBlue) }
            IconButton(onClick = { confirmClear = true }, enabled = messages.isNotEmpty(), modifier = Modifier.testTag("AI_CLEAR")) {
                Icon(Icons.Outlined.DeleteSweep, "Clear chat history", tint = if (messages.isNotEmpty()) c.down else c.textSecondary)
            }
        }
        Text("Paper strategy: $strategyStatus", color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
            modifier = Modifier.padding(horizontal = 16.dp))
        if (showKey) KeyFields(initialKey, initialModel, initialBase, providerLabel) { k, m, b -> onSaveKey(k, m, b); showKey = false }

        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("AI_MESSAGES"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty() && !busy) item(key = "empty") {
                Text(if (unavailable) "AI chat is unavailable on this device." else
                    "Ask why a strategy is winning or losing, or ask me to change a paper setting (stops, cooldowns, bankroll, per-strategy params). Every change needs your Apply.",
                    color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            }
            items(messages, key = { "m-${it.id}" }) { m -> MessageBubble(m, onApply, onCancel) }
            if (busy) item(key = "busy") {
                Row(Modifier.padding(8.dp).testTag("AI_LOADING"), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accentBlue)
                    Spacer(Modifier.width(8.dp)); Text("Thinking… (grok-4 can take up to 2 min)", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().imePadding().testTag("AI_INPUT_BAR")) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, placeholder = { Text(if (hasKey) "Ask about trades, results or settings" else "Save an LLM key first (key icon)") },
                    maxLines = 4, modifier = Modifier.weight(1f).testTag("AI_INPUT"))
                IconButton(enabled = !busy && input.isNotBlank(), onClick = { val q = input; input = ""; onSend(q) }, modifier = Modifier.testTag("AI_SEND")) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (!busy && input.isNotBlank()) c.accentBlue else c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun KeyFields(k0: String, m0: String, b0: String, providerLabel: String = "", onSave: (String, String, String) -> Unit) {
    var key by remember { mutableStateOf(k0) }; var model by remember { mutableStateOf(m0) }; var base by remember { mutableStateOf(b0) }
    val c = DipTheme.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (providerLabel.isNotBlank()) Text("Chat and window watcher: $providerLabel. Change provider in Settings.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(key, { key = it }, label = { Text("LLM API key (stored encrypted on this phone)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(base, { base = it }, label = { Text("API base URL (OpenAI-compatible)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onSave(key, model, base) }) { Text("Save key settings") }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, onApply: (Long) -> Unit, onCancel: (Long) -> Unit) {
    val c = DipTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val mine = m.role == ChatMessage.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        when (m.role) {
            ChatMessage.PROPOSAL -> Column(Modifier.fillMaxWidth(0.92f).border(1.dp, c.accentBlue, shape).background(c.surface, shape).padding(12.dp).testTag("AI_PROPOSAL")) {
                Text("Settings change (paper)", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(m.key.orEmpty(), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text("${m.oldValue} → ${m.newValue}", color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
                when (m.status) {
                    ChatMessage.PENDING -> Row {
                        Button(onClick = { onApply(m.id) }, colors = ButtonDefaults.buttonColors(containerColor = c.upButton, contentColor = c.onUp)) { Text("Apply") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onCancel(m.id) }) { Text("Cancel", color = c.down) }
                    }
                    ChatMessage.APPLIED -> Text("Applied", color = c.up, fontWeight = FontWeight.SemiBold)
                    else -> Text(m.status.orEmpty().replaceFirstChar { it.uppercase() }, color = c.textSecondary)
                }
            }
            else -> {
                val (bg, fg) = when (m.role) {
                    ChatMessage.USER -> c.accentBlue to c.onAccentBlue
                    ChatMessage.ERROR -> c.downContainer to c.downInk
                    ChatMessage.CHANGE -> (if (m.status == ChatMessage.APPLIED) c.upContainer else c.surfaceAlt) to c.textPrimary
                    else -> c.surface to c.textPrimary
                }
                Text(m.text, color = fg, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(max = 320.dp).background(bg, shape).padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(if (m.role == ChatMessage.ERROR) "AI_ERROR" else "AI_MSG"))
            }
        }
    }
}
