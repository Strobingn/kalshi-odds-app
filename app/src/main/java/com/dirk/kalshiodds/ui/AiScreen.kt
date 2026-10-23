package com.dirk.kalshiodds.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.signal.scalper.AiChat
import com.dirk.kalshiodds.signal.scalper.ChatMsg
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * The AI tab: Dashboard (charts of every AI call, the scalping winners and losers, the Paper AI's bets), Chat (one thread
 * per AI) and Keys (Groq / Mistral keys, a real Test button, the on/off switches). Paper only throughout.
 */
@Composable
fun AiScreen(vm: AiViewModel) {
    val c = DipTheme.colors
    val dash by vm.dashboard.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(if (dash.keys.any { it.second } || keys.saved.any { it.second }) 0 else 2) }
    var which by rememberSaveable { mutableStateOf(AiChat.SCALPING) }
    val llm by vm.localLlm.collectAsStateWithLifecycle()
    val llmTest by vm.llmTest.collectAsStateWithLifecycle()
    val llmTesting by vm.llmTesting.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val reviews by vm.reviewList.collectAsStateWithLifecycle()
    val autoReview by vm.autoReview.collectAsStateWithLifecycle()
    val report by vm.modelReport.collectAsStateWithLifecycle()
    val pickModel = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importModel(uri) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AiHeader(onRunNow = vm::runNow)
        AiSectionTabs(section) { section = it }
        when (section) {
            0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                AiDashboardContent(dash, onOpenKeys = { section = 2 })
                report?.let {
                    Spacer(Modifier.height(12.dp))
                    ModelReportCard(it)
                }
            }
            1 -> AiChatContent(
                which = which, msgs = msgs.filter { it.thread == which }, busy = busy,
                onWhich = { which = it }, onSend = { vm.send(which, it) },
                onApply = vm::apply, onCancel = vm::cancel, onClear = { vm.clear(which) }
            )
            2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                AiKeysContent(
                    keys, onDraft = vm::setDraft, onSave = vm::saveKeys, onTest = vm::testKeys, onClear = vm::clearKey,
                    onScalping = vm::setScalpingOn, onPaper = vm::setPaperOn, onLearning = vm::setLearning
                )
                Spacer(Modifier.height(12.dp))
                OnPhoneModelCard(
                    llm, llmTest, llmTesting,
                    onDownload = vm::downloadModel,
                    onPickFile = { pickModel.launch(arrayOf("application/octet-stream", "*/*")) },
                    onCancel = vm::cancelTransfer, onSelect = vm::selectModel, onDelete = vm::deleteModel,
                    onPreferLocal = vm::setPreferLocal, onTest = vm::testLocal, speeds = speeds
                )
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                AiReviewsContent(
                    review, reviews, autoReview, onAuto = vm::setAutoReview, onRunToday = vm::reviewToday,
                    onRunDay = vm::reviewDay, onCancel = vm::cancelReview, yesterday = vm.yesterday()
                )
            }
        }
    }
}

/** Title row: compact, so the chat keeps its room when the keyboard is up. */
@Composable
fun AiHeader(onRunNow: () -> Unit) {
    val c = DipTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("AI", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRunNow) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.accentBlue)
            Spacer(Modifier.width(4.dp))
            Text("Run now", color = c.accentBlue, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun AiSectionTabs(selected: Int, onSelect: (Int) -> Unit) {
    val c = DipTheme.colors
    TabRow(
        selectedTabIndex = selected,
        containerColor = c.bg,
        contentColor = c.textPrimary,
        indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[selected]), color = c.accentBlue) }
    ) {
        listOf("Dashboard", "Chat", "Keys", "Reviews").forEachIndexed { i, t ->
            Tab(
                selected = selected == i, onClick = { onSelect(i) },
                text = { Text(t, fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal, color = if (selected == i) c.textPrimary else c.textSecondary) }
            )
        }
    }
}

private val SUGGESTIONS = mapOf(
    AiChat.SCALPING to listOf(
        "Which scalpers are winning right now?",
        "What would you change in the settings now?",
        "Why are the losing strategies losing?"
    ),
    AiChat.PAPER to listOf(
        "What's your read on this window?",
        "Why did you take your last bet?",
        "How is your betting record going?"
    )
)

/**
 * Chat: one thread per AI. Suggestions show only while the thread is empty; the input bar sits on the keyboard.
 * Stateless apart from the typed draft.
 */
@Composable
fun AiChatContent(
    which: String,
    msgs: List<ChatMsg>,
    busy: Boolean,
    onWhich: (String) -> Unit,
    onSend: (String) -> Unit,
    onApply: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onClear: () -> Unit
) {
    val c = DipTheme.colors
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, busy, which) {
        val last = msgs.size + (if (busy) 1 else 0) - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    val accent = if (which == AiChat.SCALPING) c.accentBlue else c.accentPurple
    val send = { q: String -> if (q.isNotBlank() && !busy) { onSend(q.trim()); draft = "" } }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThreadSwitch(which, onWhich, Modifier.weight(1f))
            IconButton(onClick = onClear, enabled = msgs.isNotEmpty()) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear this chat", tint = if (msgs.isNotEmpty()) c.textSecondary else c.border)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty() && !busy) {
                ChatEmptyState(which, accent, onPick = send)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(msgs, key = { it.id }) { m ->
                        Bubble(m, which, accent, onApply = { onApply(m.id) }, onCancel = { onCancel(m.id) })
                    }
                    if (busy) item(key = "typing") { TypingBubble(which, accent) }
                }
            }
        }
        ChatInputBar(draft, { draft = it }, enabled = !busy, accent = accent, placeholder = "Message the ${if (which == AiChat.SCALPING) "Scalping" else "Paper"} AI") { send(draft) }
    }
}

/** Full-width two-way switch between the threads. */
@Composable
private fun ThreadSwitch(which: String, onWhich: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = DipTheme.colors
    Row(
        modifier.height(40.dp).background(c.surface, RoundedCornerShape(20.dp)).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(3.dp)
    ) {
        for ((id, label, color) in listOf(Triple(AiChat.SCALPING, "Scalping AI", c.accentBlue), Triple(AiChat.PAPER, "Paper AI", c.accentPurple))) {
            val on = which == id
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(if (on) color.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(17.dp))
                    .clickable { onWhich(id) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(if (on) color else c.textSecondary, CircleShape))
                    Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) color else c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(which: String, accent: Color, onPick: (String) -> Unit) {
    val c = DipTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(which, accent, 44.dp)
            Column {
                Text(if (which == AiChat.SCALPING) "Scalping AI" else "Paper AI", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Text(
                    if (which == AiChat.SCALPING) "Tunes the scalping algorithms. Its suggested changes wait for your Apply."
                    else "Places the paper bets. Ask what it sees and why it bet.",
                    fontSize = 12.sp, color = c.textSecondary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("TRY ASKING", fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
        SUGGESTIONS[which].orEmpty().forEach { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, c.border, RoundedCornerShape(14.dp))
                    .clickable { onPick(q) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(q, Modifier.weight(1f), fontSize = 14.sp, color = c.textPrimary)
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Rounded input with the send button inside the bar. */
@Composable
private fun ChatInputBar(text: String, onText: (String) -> Unit, enabled: Boolean, accent: Color, placeholder: String, onSend: () -> Unit) {
    val c = DipTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder, color = c.textSecondary) },
            shape = RoundedCornerShape(24.dp),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surfaceAlt,
                unfocusedContainerColor = c.surfaceAlt,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = c.textPrimary,
                unfocusedTextColor = c.textPrimary,
                cursorColor = accent
            )
        )
        val canSend = enabled && text.isNotBlank()
        FilledIconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, disabledContainerColor = c.surfaceAlt)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (canSend) c.bg else c.textSecondary)
        }
    }
}

@Composable
private fun Avatar(which: String, color: Color, size: androidx.compose.ui.unit.Dp = 28.dp) {
    Box(Modifier.size(size).background(color.copy(alpha = 0.22f), CircleShape).border(1.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(if (which == AiChat.SCALPING) "S" else "P", color = color, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
private fun TypingBubble(which: String, accent: Color) {
    val c = DipTheme.colors
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(which, accent)
        Row(
            Modifier.background(c.surface, RoundedCornerShape(16.dp)).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            for (i in 0..2) Box(Modifier.size(7.dp).alpha(if (phase.toInt() == i) 1f else 0.35f).background(accent, CircleShape))
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, which: String, accent: Color, onApply: () -> Unit, onCancel: () -> Unit) {
    val c = DipTheme.colors
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                m.text,
                Modifier
                    .widthIn(max = 300.dp)
                    .background(accent.copy(alpha = 0.24f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 15.sp, color = c.textPrimary
            )
        }
        "change" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                m.text,
                Modifier.background(c.surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 12.sp, color = if (m.text.startsWith("Applied")) c.up else c.textSecondary
            )
        }
        "proposal" -> ProposalCard(m, accent, onApply, onCancel)
        else -> {
            // "[groq openai/gpt-oss-120b] answer" → caption + answer
            val tag = Regex("^\\[([^\\]]*)\\]\\s*").find(m.text)
            val body = if (tag != null) m.text.substring(tag.range.last + 1) else m.text
            val error = m.role == "error"
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(which, if (error) c.down else accent)
                Column(Modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        body,
                        Modifier
                            .background(if (error) c.downContainer else c.surface, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                            .border(1.dp, if (error) c.down else c.border, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = 15.sp, color = c.textPrimary, lineHeight = 21.sp
                    )
                    tag?.let { Text(it.groupValues[1].substringAfterLast('/'), fontSize = 10.sp, color = c.textSecondary, modifier = Modifier.padding(start = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(m: ChatMsg, accent: Color, onApply: () -> Unit, onCancel: () -> Unit) {
    val c = DipTheme.colors
    val v = m.value?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }.orEmpty()
    Column(
        Modifier
            .padding(start = 36.dp)
            .widthIn(max = 300.dp)
            .background(c.surface, RoundedCornerShape(16.dp))
            .border(1.dp, if (m.status == "pending") accent else c.border, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("SUGGESTED CHANGE", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = accent)
        Text(AiDashboard.pretty(m.strategy.orEmpty()), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.param.orEmpty(), fontSize = 13.sp, color = c.textSecondary)
            Text("→", fontSize = 13.sp, color = c.textSecondary)
            Text(v, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        when (m.status) {
            "pending" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = c.bg)) { Text("Apply", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onCancel) { Text("Dismiss", color = c.textSecondary) }
            }
            "applied" -> Text("✓ Applied", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.up)
            "rejected" -> Text("Refused by the hard bounds", fontSize = 12.sp, color = c.down)
            else -> Text(m.status.orEmpty().replaceFirstChar { it.uppercase() }, fontSize = 12.sp, color = c.textSecondary)
        }
    }
}

/** Keys and switches. Stateless. */
@Composable
fun AiKeysContent(
    s: AiKeysState,
    onDraft: (String, String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClear: (String) -> Unit,
    onScalping: (Boolean) -> Unit,
    onPaper: (Boolean) -> Unit,
    onLearning: (Boolean) -> Unit
) {
    val c = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AiCard("API keys", "Free keys work. Stored encrypted on this phone, never uploaded anywhere else.") {
            for ((name, draft) in listOf("Groq" to s.groqDraft, "Mistral" to s.mistralDraft)) {
                val saved = s.saved.firstOrNull { it.first == name }?.second == true
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft, onValueChange = { onDraft(name, it) }, modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text(if (saved) "$name key · saved ✓ (type to replace)" else "$name API key") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (saved) TextButton(onClick = { onClear(name) }) { Text("Remove", color = c.down, fontSize = 12.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f).height(48.dp)) { Text("Save keys") }
                OutlinedButton(onClick = onTest, enabled = !s.testing, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(if (s.testing) "Testing…" else "Test keys")
                }
            }
            if (s.message.isNotBlank()) Text(s.message, fontSize = 12.sp, color = c.textSecondary)
            s.testLines.forEach { line ->
                val ok = line.contains(": OK")
                Row(
                    Modifier.fillMaxWidth().background(if (ok) c.upContainer else c.downContainer, RoundedCornerShape(10.dp)).padding(10.dp)
                ) { Text(line, fontSize = 12.sp, color = if (ok) c.up else c.textPrimary) }
            }
        }
        AiCard("The two AIs", "Separate prompts, memory and logs. Neither can place a real order.") {
            SwitchRow("Scalping AI", "Changes the scalping algorithms every 60 s, aggressively, inside hard bounds", s.scalpingOn, onScalping)
            SwitchRow("Paper AI", "Places paper bets every minute (up to 50 contracts)", s.paperOn, onPaper)
            SwitchRow("Learning mode", "Small retired strategies bet on paper too; the 17 big losers stay out", s.learningOn, onLearning)
        }
        AiCard("Provider rotation", "All AIs share one provider for 500 answered calls, then switch to the next one with a key") {
            Text("Groq → Mistral → Groq …", fontSize = 12.sp, color = c.textPrimary)
            if (s.rotation.isNotBlank()) Text(s.rotation, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.accentBlue)
            Text("If the current one fails or hits its free limit, that call goes to the next provider at once. Groq models are tried in order: gpt-oss-120b, qwen3.8-27b, gpt-oss-20b; a retired model is skipped.", fontSize = 11.sp, color = c.textSecondary)
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = DipTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            Text(sub, fontSize = 11.sp, color = c.textSecondary)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
