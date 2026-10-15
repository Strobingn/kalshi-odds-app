package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.ScalpControlSpec
import com.dirk.kalshiodds.ui.components.FieldCard
import java.util.Locale

@Composable
fun ScalpControlsCard(code: String, saved: ScalpControlSpec.Config?, onApply: (ScalpControlSpec.Config) -> Unit, onReset: () -> Unit) {
    var draft by remember(code, saved) { mutableStateOf(saved ?: ScalpControlSpec.defaults(code)) }
    var notice by remember(code) { mutableStateOf<String?>(null) }
    var expanded by remember(code) { mutableStateOf("Entry") }
    val error = draft.error()
    val changed = draft != (saved ?: ScalpControlSpec.defaults(code))
    FieldCard {
        Text("Algorithm settings", style = MaterialTheme.typography.titleMedium)
        Text(if (saved == null) "Model defaults" else "Manual settings ${saved.revision}", style = MaterialTheme.typography.labelMedium)
        Text("Save applies to new trades only. Open trades keep their entry settings. Manual exits use the stop, net target, trail and timing below; they replace the model's exit rules.", style = MaterialTheme.typography.bodySmall)
        if (code.startsWith("v2-")) Text("V2 follows its original algorithm's signals. Adjust the original's signal controls to change those signals.", style = MaterialTheme.typography.bodySmall)
        else Text("Entry filters apply to candidates produced by this model's signal rules.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Allow new entries", Modifier.weight(1f))
            Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it); notice = null },
                modifier = Modifier.semantics { contentDescription = "Allow new paper entries for $code" })
        }
        val keys = remember(code) { ScalpControlSpec.keys(code) }
        listOf("Signal", "Entry", "Exits", "Execution").filter { group -> keys.any { it.group == group } }.forEach { group ->
            TextButton(onClick = { expanded = if (expanded == group) "" else group }) {
                Text("${if (expanded == group) "−" else "+"} $group")
            }
            if (expanded == group) keys.filter { it.group == group }.forEach { key ->
                val value = draft[key]
                val label = String.format(Locale.US, (if (key.step < 1) "%s: %.1f %s" else "%s: %.0f %s"), key.title, value, key.unit)
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { draft = draft.set(key, (value - key.step).coerceAtLeast(key.lo)); notice = null },
                        enabled = value > key.lo, modifier = Modifier.semantics { contentDescription = "Decrease ${key.title}" }) { Text("−") }
                    Slider(value = value.toFloat().coerceIn(key.lo.toFloat(), key.hi.toFloat()),
                        onValueChange = { draft = draft.set(key, it.toDouble()).normalized(); notice = null },
                        valueRange = key.lo.toFloat()..key.hi.toFloat(),
                        steps = ((key.hi - key.lo) / key.step).toInt().minus(1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).semantics { contentDescription = label })
                    TextButton(onClick = { draft = draft.set(key, (value + key.step).coerceAtMost(key.hi)); notice = null },
                        enabled = value < key.hi, modifier = Modifier.semantics { contentDescription = "Increase ${key.title}" }) { Text("+") }
                }
            }
        }
        OutlinedButton(onClick = { draft = ScalpControlSpec.cheapEarly(code); notice = "Preset loaded — save to start the paper trial" }) {
            Text("Load 40¢ / 9–11 minutes preset")
        }
        Text("Research preset: the combined filters are untested. Minimum profit if it wins is a separate control, not an expected-value guarantee.", style = MaterialTheme.typography.labelSmall)
        val bestWin = draft[ScalpControlSpec.Key.CLIP] * (1 - draft[ScalpControlSpec.Key.MIN_ASK] / 100) -
            com.dirk.kalshiodds.decision.ScalpRule.orderFee(draft[ScalpControlSpec.Key.CLIP].toInt(), draft[ScalpControlSpec.Key.MIN_ASK] / 100)
        if (draft[ScalpControlSpec.Key.WIN_PROFIT] > bestWin) Text(String.format(Locale.US,
            "No fills possible: this clip can win at most $%.2f at your minimum price. Lower the minimum profit or increase contracts.", bestWin),
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { onApply(draft); notice = "Saved for new paper trades" }, enabled = error == null,
            modifier = Modifier.fillMaxWidth()) { Text(if (changed) "Save changes for new trades" else "Use these settings for new trades") }
        TextButton(onClick = { onReset(); draft = ScalpControlSpec.defaults(code); notice = "Model defaults restored for new trades" }) { Text("Restore model defaults") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Results above include earlier settings. Each new trade records its setting revision for comparison.", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
    }
}
