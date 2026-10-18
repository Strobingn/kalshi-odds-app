package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * Kalshi Scalper AI chat entry point inside the Scalping arena. 1.0.4: the chat itself moved to its own
 * bottom tab ([AiChatScreen]) with persistent history; this card (and the arena's "AI chat" pill) opens it.
 * The LLM can never place real-money orders.
 */
@Composable
internal fun LlmChatPanel(@Suppress("UNUSED_PARAMETER") rows: List<ScalpModels.Row>) {
    val c = DipTheme.colors
    val openAi = LocalOpenAiTab.current
    ArenaCard {
        Text("AI chat", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("The AI now has its own tab with saved chat history. It explains results and can propose paper setting changes you confirm. It can never place real-money orders.",
            color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Button(onClick = openAi, modifier = Modifier.fillMaxWidth()) { Text("Open AI tab") }
    }
}
