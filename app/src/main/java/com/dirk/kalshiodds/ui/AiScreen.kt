package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(vm: AiViewModel) {
    val c = DipTheme.colors
    val dash by vm.dashboard.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(if (dash.keys.any { it.second } || keys.saved.any { it.second }) 0 else 2) }
    var which by rememberSaveable { mutableStateOf(AiChat.SCALPING) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = { Text("AI", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = vm::runNow) { Text("Run now") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, titleContentColor = c.textPrimary)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AiSectionTabs(section) { section = it }
            when (section) {
                0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AiDashboardContent(dash, onOpenKeys = { section = 2 })
                }
                1 -> AiChatContent(
                    which = which, msgs = msgs.filter { it.thread == which }, busy = busy,
                    onWhich = { which = it }, onSend = { vm.send(which, it) },
                    onApply = vm::apply, onCancel = vm::cancel, onClear = { vm.clear(which) }
                )
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AiKeysContent(
                        keys, onDraft = vm::setDraft, onSave = vm::saveKeys, onTest = vm::testKeys, onClear = vm::clearKey,
                        onScalping = vm::setScalpingOn, onPaper = vm::setPaperOn, onLearning = vm::setLearning
                    )
                }
            }
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
        listOf("Dashboard", "Chat", "Keys").forEachIndexed { i, t ->
            Tab(
                selected = selected == i, onClick = { onSelect(i) },
                text = { Text(t, fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal, color = if (selected == i) c.textPrimary else c.textSecondary) }
            )
        }
    }
}

private val SUGGESTIONS = mapOf(
    AiChat.SCALPING to listOf("Which scalpers are winning?", "What would you change now?", "Why are the losers losing?"),
    AiChat.PAPER to listOf("What's your read on this window?", "Why did you take your last bet?", "How is your record?")
)

/** Chat: thread switch, messages, Apply/Cancel cards, quick questions and the input. Stateless apart from the draft. */
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
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, which) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }
    val scalpColor = c.accentBlue
    val paperColor = c.accentPurple
    val accent = if (which == AiChat.SCALPING) scalpColor else paperColor

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ThreadChip("Scalping AI", which == AiChat.SCALPING, scalpColor) { onWhich(AiChat.SCALPING) }
            ThreadChip("Paper AI", which == AiChat.PAPER, paperColor) { onWhich(AiChat.PAPER) }
            Box(Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Clear", color = c.textSecondary) }
        }
        Text(
            if (which == AiChat.SCALPING) "Tunes the scalping algorithms. It can propose changes; nothing changes until you tap Apply."
            else "Places the paper bets. Ask it what it sees and why it bet. It can't change settings.",
            fontSize = 11.sp, color = c.textSecondary
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msgs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (which == AiChat.SCALPING) "Ask the Scalping AI" else "Ask the Paper AI", fontWeight = FontWeight.Bold, color = accent, fontSize = 16.sp)
                    Text("It sees the live 15-minute window, every strategy's results and its own history.", fontSize = 12.sp, color = c.textSecondary)
                }
            }
            items(msgs, key = { it.id }) { m -> Bubble(m, accent, onApply = { onApply(m.id) }, onCancel = { onCancel(m.id) }) }
            if (busy) item { Text("Thinking…", fontSize = 12.sp, color = accent, modifier = Modifier.padding(start = 4.dp)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SUGGESTIONS[which].orEmpty().forEach { q ->
                OutlinedButton(onClick = { if (!busy) onSend(q) }, enabled = !busy) { Text(q, fontSize = 11.sp) }
            }
        }
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message the ${if (which == AiChat.SCALPING) "Scalping" else "Paper"} AI") }, maxLines = 4
            )
            Button(
                enabled = draft.isNotBlank() && !busy,
                onClick = { val q = draft; draft = ""; onSend(q) },
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) { Text("Send") }
        }
    }
}

@Composable
private fun ThreadChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label, fontWeight = FontWeight.SemiBold) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = 0.22f), selectedLabelColor = color)
    )
}

@Composable
private fun Bubble(m: ChatMsg, accent: Color, onApply: () -> Unit, onCancel: () -> Unit) {
    val c = DipTheme.colors
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        val bg = when (m.role) {
            "user" -> accent.copy(alpha = 0.20f)
            "error" -> c.downContainer
            "proposal" -> c.surfaceAlt
            "change" -> c.surface
            else -> c.surface
        }
        Column(
            Modifier
                .widthIn(max = 330.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .border(1.dp, if (m.role == "proposal") accent else c.border, RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val label = when (m.role) { "user" -> "You"; "ai" -> "AI"; "error" -> "Error"; "change" -> "Settings"; else -> "Proposed change" }
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (m.role == "error") c.down else c.textSecondary)
            Text(m.text, fontSize = 14.sp, color = c.textPrimary)
            if (m.role == "proposal") {
                if (m.status == "pending") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onApply, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Apply") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                } else {
                    Text(m.status.orEmpty().uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (m.status == "applied") c.up else c.textSecondary)
                }
            }
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
