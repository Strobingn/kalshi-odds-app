package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.decision.BtcDirection
import com.dirk.kalshiodds.decision.BtcScalpTuner
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat tab. Grok 4.7 Fast (public model grok-4.7, low reasoning) can tighten
 * Bitcoin paper scalp settings. It cannot place a Kalshi order or raise size.
 */
@Composable
fun ScalpChatScreen() {
    val colors = DipTheme.colors
    val context = LocalContext.current
    val container = remember { KalshiOddsApp.from(context).container }
    val scope = rememberCoroutineScope()
    val cfg = BtcDirection.config()
    var keyDraft by remember { mutableStateOf(container.extraSecrets.xaiApiKey) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("Questions get a normal answer. A tune only tightens paper settings and never places an order.") }
    var turns by remember { mutableStateOf(listOf(GrokChat.Turn("assistant", "I'm ${GrokChat.DISPLAY_NAME}. Ask a question about the Bitcoin scalps, or tap Tune BTC now."))) }

    fun applyPatch(patch: BtcScalpTuner.Patch) {
        val next = BtcScalpTuner.apply(container.scalpControls.state.value, patch)
        if (patch.changesAnything) container.scalpControls.save(next)
        note = patch.note
        turns = turns + GrokChat.Turn("assistant", patch.note)
    }

    fun tuneNow() {
        if (busy) return
        busy = true
        scope.launch {
            val patch = withContext(Dispatchers.IO) {
                BtcScalpTuner.propose(loadBtcTrades(container), container.scalpControls.state.value)
            }
            applyPatch(patch)
            busy = false
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        val key = keyDraft.trim()
        container.extraSecrets.xaiApiKey = key
        draft = ""
        val history = turns + GrokChat.Turn("user", text)
        turns = history
        busy = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { GrokChat.complete(key, history) }
            }
            reply.onSuccess { result ->
                val shown = result.text.trim().ifBlank { "No answer came back." }
                turns = history + GrokChat.Turn("assistant", shown)
                val obj = result.patch
                if (obj != null) {
                    val current = container.scalpControls.state.value
                    val patch = BtcScalpTuner.fromChat(
                        current,
                        obj.optIntOrNull("maxAskCents"),
                        obj.optIntOrNull("contracts"),
                        obj.optIntOrNull("stopLossPercent"),
                        obj.optIntOrNull("trailCents"),
                        obj.optStringSet("disable")
                    )
                    if (patch.changesAnything) {
                        container.scalpControls.save(BtcScalpTuner.apply(current, patch))
                    }
                    note = patch.note
                }
            }.onFailure { err ->
                turns = history + GrokChat.Turn("assistant", err.message ?: "Chat failed")
            }
            busy = false
        }
    }

    Scaffold(containerColor = colors.bg, contentWindowInsets = dipContentInsets()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Bitcoin scalp chat", color = colors.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${GrokChat.DISPLAY_NAME} · model ${GrokChat.MODEL} · reasoning ${GrokChat.REASONING_EFFORT}. " +
                    "BTC forecast horizon ${if (cfg.horizonS < 0) "to close" else "${cfg.horizonS}s"}. " +
                    "No side bias: UP buys YES, DOWN buys NO, and only if that ask is under 50¢ with at least 90s left. " +
                    "Sell when the call turns down, 2¢ under the fill, or inside the last 60s.",
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(note, color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("xAI API key") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { tuneNow() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Tune BTC now") }
                OutlinedButton(
                    onClick = {
                        container.extraSecrets.xaiApiKey = keyDraft.trim()
                        note = if (keyDraft.isBlank()) "Key cleared." else "Key saved on this phone."
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save key") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(turns) { turn ->
                    Text(
                        (if (turn.role == "user") "You" else GrokChat.DISPLAY_NAME) + "\n" + turn.text,
                        color = if (turn.role == "user") colors.textPrimary else colors.accentBlue,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Message") }
            )
            Button(onClick = { send() }, enabled = !busy && draft.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (busy) "Working…" else "Send")
            }
        }
    }
}

private fun loadBtcTrades(container: com.dirk.kalshiodds.AppContainer): List<BtcScalpTuner.Trade> =
    ScalpModels.V2_MODELS.flatMap { model ->
        container.scalp.pageTrades(model.code, limit = 40).mapNotNull { trade ->
            val net = trade.netUsd
            if (trade.state != ScalpState.CLOSED || net == null || !BtcDirection.isBitcoin(trade.ticker)) null
            else BtcScalpTuner.Trade(trade.ticker, model.code, net)
        }
    }

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

private fun JSONObject.optStringSet(name: String): Set<String> {
    val arr = optJSONArray(name) ?: JSONArray()
    return buildSet {
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
    }
}
