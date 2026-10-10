package com.dirk.kalshiodds.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.crash.CrashLog
import com.dirk.kalshiodds.ui.theme.DipTheme

object CrashLogCopy {
    const val TITLE = "Crash log"
    const val EMPTY = "No crashes recorded."
    fun announce(headline: String) = "The app crashed last time: $headline"
}

fun shareCrashText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Kalshi Trader crash log")
        putExtra(Intent.EXTRA_TEXT, text.take(400_000))
    }
    context.startActivity(Intent.createChooser(send, "Share crash log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 0.3.47 More → Crash log: list, view, share/export and clear saved uncaught exceptions. */
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val colors = DipTheme.colors
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val files = remember(version) { CrashLog.attach(context); CrashLog.files() }
    var open by remember { mutableStateOf<String?>(null) }
    DecisionScaffold(CrashLogCopy.TITLE, onBack) {
        item {
            Row {
                OutlinedButton(onClick = { shareCrashText(context, CrashLog.exportAll()) }, enabled = files.isNotEmpty()) { Text("Share / export all") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { CrashLog.clear(); version++ }, enabled = files.isNotEmpty()) { Text("Clear") }
            }
        }
        if (files.isEmpty()) item { Text(CrashLogCopy.EMPTY, color = colors.textSecondary) }
        files.forEachIndexed { i, f ->
            item(key = "c$i|${f.name}") {
                DecisionCard {
                    Text(CrashLog.headline(f), color = colors.textPrimary, style = MaterialTheme.typography.bodySmall)
                    Text(f.name, color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                    Row {
                        OutlinedButton(onClick = { open = if (open == f.name) null else f.name }) { Text(if (open == f.name) "Hide" else "View") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { shareCrashText(context, CrashLog.read(f)) }) { Text("Share") }
                    }
                    if (open == f.name) {
                        Text(CrashLog.read(f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}
