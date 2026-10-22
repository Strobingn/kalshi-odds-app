package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.scalper.AiChat
import com.dirk.kalshiodds.signal.scalper.ChatMsg
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat with one of the two AIs. The SCALPING AI thread can propose scalping-setting changes (each needs your Apply tap and is
 * clamped to hard bounds). The PAPER AI thread explains its paper bets. Neither can place an order or touch real money.
 * Providers (Groq, Mistral, Gemini) are the keys saved on the Data screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(onBack: () -> Unit) {
    val colors = DipTheme.colors
    val container = KalshiOddsApp.from(LocalContext.current).container
    val chat = container.aiChat
    val all by chat.messages.collectAsStateWithLifecycle()
    val busy by chat.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var which by remember { mutableStateOf(AiChat.SCALPING) }
    var draft by remember { mutableStateOf("") }
    val msgs = all.filter { it.thread == which }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size, which) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex) }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("AI chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = { chat.clear(which) }) { Text("Clear") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = which == AiChat.SCALPING, onClick = { which = AiChat.SCALPING }, label = { Text("Scalping AI") })
                FilterChip(selected = which == AiChat.PAPER, onClick = { which = AiChat.PAPER }, label = { Text("Paper AI") })
            }
            Text(
                if (which == AiChat.SCALPING) "Tunes the scalping algorithms. Can propose setting changes; you tap Apply."
                else "Places one paper bet per window. Ask it why. It cannot change settings.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs, key = { it.id }) { m -> Bubble(m, onApply = { scope.launch(Dispatchers.IO) { chat.apply(m.id) } }, onCancel = { chat.cancel(m.id) }) }
            }
            if (busy) Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the ${if (which == AiChat.SCALPING) "Scalping" else "Paper"} AI") },
                    maxLines = 3
                )
                Button(
                    enabled = draft.isNotBlank() && !busy,
                    onClick = {
                        val q = draft; draft = ""
                        scope.launch { withContext(Dispatchers.IO) { chat.send(which, q) } }
                    }
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, onApply: () -> Unit, onCancel: () -> Unit) {
    val colors = DipTheme.colors
    val mine = m.role == "user"
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (mine) colors.surface else colors.surfaceAlt, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val label = when (m.role) {
            "user" -> "You"; "ai" -> "AI"; "error" -> "Error"; "change" -> "Settings"; else -> "Proposal"
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Text(m.text, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        if (m.role == "proposal") {
            if (m.status == "pending") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApply) { Text("Apply") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Text(m.status.orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
    }
}
