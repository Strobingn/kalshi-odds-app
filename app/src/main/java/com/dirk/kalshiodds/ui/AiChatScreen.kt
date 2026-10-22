package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val live = container?.aiWatch?.live?.collectAsStateWithLifecycle()?.value
    val liveOn = container?.aiWatch?.config?.collectAsStateWithLifecycle()?.value?.liveFeed ?: true
    AiChatContent(
        messages = messages, busy = busy, strategyStatus = status, hasKey = ready, providerLabel = providerLabel,
        initialKey = endpoint?.apiKey.orEmpty(), initialModel = endpoint?.model.orEmpty(), initialBase = endpoint?.baseUrl.orEmpty(),
        onSaveKey = { k, m, b -> settings?.saveSlot(settings.providerId, k, m, b) },
        onSend = onSend,
        onApply = { chat?.applyProposal(it) }, onCancel = { chat?.cancelProposal(it) }, onClear = { chat?.clear() },
        unavailable = chat == null,
        live = live, liveOn = liveOn, onLiveFeed = { on -> container?.aiWatch?.setLiveFeed(on) }
    )
}

/** Stateless AI tab (Paparazzi-tested). The input bar is pinned below a weighted list and lifted by imePadding. */
@Composable
fun AiChatContent(
    messages: List<ChatMessage>, busy: Boolean, strategyStatus: String, hasKey: Boolean,
    initialKey: String = "", initialModel: String = "", initialBase: String = "",
    providerLabel: String = "",
    onSaveKey: (String, String, String) -> Unit = { _, _, _ -> }, onSend: (String) -> Unit = {},
    onApply: (Long) -> Unit = {}, onCancel: (Long) -> Unit = {}, onClear: () -> Unit = {}, unavailable: Boolean = false,
    live: com.dirk.kalshiodds.llm.AiLiveSnapshot? = null, liveOn: Boolean = true, onLiveFeed: (Boolean) -> Unit = {}
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

    var statusOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(dipContentInsets()).testTag("AI_TAB")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI", color = c.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("PAPER ONLY · never places real-money orders", color = c.spot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { showKey = !showKey }) { Icon(Icons.Outlined.Key, "LLM key settings", tint = c.accentBlue) }
            IconButton(onClick = { confirmClear = true }, enabled = messages.isNotEmpty(), modifier = Modifier.testTag("AI_CLEAR")) {
                Icon(Icons.Outlined.DeleteSweep, "Clear chat history", tint = if (messages.isNotEmpty()) c.down else c.textSecondary)
            }
        }
        TextButton(onClick = { statusOpen = !statusOpen }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (statusOpen) "Hide status" else "Paper strategy: $strategyStatus")
        }
        if (statusOpen) {
            Text("Paper strategy: $strategyStatus", color = c.textSecondary, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (live != null) AiLiveFeedCard(live, liveOn, onLiveFeed)
        }
        if (showKey) KeyFields(initialKey, initialModel, initialBase, providerLabel) { k, m, b -> onSaveKey(k, m, b); showKey = false }

        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("AI_MESSAGES"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        val inputBar = Modifier.fillMaxWidth().imePadding().then(if (LocalDipTabBar.current) Modifier else Modifier.navigationBarsPadding())
        Surface(color = c.surface, modifier = inputBar.testTag("AI_INPUT_BAR")) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, placeholder = { Text(if (hasKey) "Ask about trades, results or settings" else "Save an LLM key first (key icon)") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 4, modifier = Modifier.weight(1f).testTag("AI_INPUT"))
                IconButton(enabled = !busy && input.isNotBlank(), onClick = { val q = input; input = ""; onSend(q) }, modifier = Modifier.testTag("AI_SEND")) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (!busy && input.isNotBlank()) c.accentBlue else c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun AiLiveFeedCard(live: com.dirk.kalshiodds.llm.AiLiveSnapshot, on: Boolean, onToggle: (Boolean) -> Unit) {
    val c = DipTheme.colors
    val left = ((live.closeMs - live.atMs).coerceAtLeast(0L)) / 1000L
    val age = live.ageMs(System.currentTimeMillis()) / 1000L
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).border(1.dp, c.border, RoundedCornerShape(12.dp)).background(c.surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live • updated ${age}s ago", color = c.up, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("AI live feed", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Switch(checked = on, onCheckedChange = onToggle)
        }
        Text(live.ticker.ifBlank { "KXBTC15M" } + " · ${left / 60}:${"%02d".format(left % 60)} left", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        val dist = live.distanceUsd?.let { String.format(Locale.US, "%+,.2f", it) } ?: "—"
        Text("Strike ${fmtPx(live.strike)} · BTC ${fmtPx(live.btc)} · ${dist}", color = c.textPrimary, style = MaterialTheme.typography.bodySmall)
        Text(com.dirk.kalshiodds.llm.AiLiveFeed.cardOddsLine(live), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        val rows = com.dirk.kalshiodds.llm.AiLiveFeed.positionRows(live.positions)
        if (rows.isEmpty()) Text("No open paper scalps", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        else rows.take(6).forEach { p ->
            val color = when {
                p.flat -> c.textSecondary
                p.pnlUsd >= 0 -> c.up
                else -> c.down
            }
            Text(p.text, color = color, style = MaterialTheme.typography.labelSmall)
        }
        LiveMiniChart(live)
    }
}

@Composable
private fun LiveMiniChart(live: com.dirk.kalshiodds.llm.AiLiveSnapshot) {
    val c = DipTheme.colors
    val pts = live.points
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        if (pts.size < 2) return@Canvas
        fun line(sel: (com.dirk.kalshiodds.llm.AiLivePoint) -> Double?, color: Color) {
            val vals = pts.map { sel(it) }
            val finite = vals.filterNotNull().filter { it.isFinite() }
            if (finite.size < 2) return
            val lo = finite.min(); val hi = finite.max(); val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
            var prev: Offset? = null
            vals.forEachIndexed { i, v ->
                if (v == null || !v.isFinite()) { prev = null; return@forEachIndexed }
                val x = i * size.width / (pts.size - 1).coerceAtLeast(1)
                val y = size.height - ((v - lo) / span).toFloat() * size.height
                val cur = Offset(x, y)
                prev?.let { drawLine(color, it, cur, strokeWidth = 3f, cap = StrokeCap.Round) }
                prev = cur
            }
        }
        line({ it.btc }, c.spot)
        line({ it.strike }, c.textSecondary)
        line({ it.up }, c.up)
        line({ it.down }, c.down)
    }
}

private fun fmtPx(v: Double?) = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "$%,.2f", it) } ?: "—"

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
            ChatMessage.PROPOSAL -> Column(Modifier.fillMaxWidth().border(1.dp, c.accentBlue, shape).background(c.surface, shape).padding(16.dp).testTag("AI_PROPOSAL")) {
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
                Column(
                    Modifier.fillMaxWidth().background(bg, shape).padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag(if (m.role == ChatMessage.ERROR) "AI_ERROR" else "AI_MSG"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(if (mine) "You" else "AI", color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(m.text, color = fg, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
                    Text(com.dirk.kalshiodds.domain.EtTime.clock(m.atMs), color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
