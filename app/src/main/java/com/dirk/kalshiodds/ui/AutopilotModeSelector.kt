package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dirk.kalshiodds.signal.paper.AutopilotMode
import com.dirk.kalshiodds.ui.theme.FieldMetrics

@Composable
fun AutopilotModeSelector(
    mode: AutopilotMode,
    onSelect: (AutopilotMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FieldMetrics.space8)
    ) {
        AutopilotMode.entries.forEach { item ->
            val click = { onSelect(item) }
            if (item == mode) {
                Button(onClick = click, modifier = Modifier.weight(1f)) { Text(item.label) }
            } else {
                OutlinedButton(onClick = click, modifier = Modifier.weight(1f)) { Text(item.label) }
            }
        }
    }
}
