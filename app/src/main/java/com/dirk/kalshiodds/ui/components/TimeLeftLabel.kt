package com.dirk.kalshiodds.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.dirk.kalshiodds.domain.TimeLeft
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TimeLeftLabel(
    closeEpochMs: Long?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(closeEpochMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val expired = TimeLeft.isExpired(closeEpochMs, nowMs)
    val text = TimeLeft.format(closeEpochMs, nowMs)
    Text(
        text = if (compact && text != "—" && text != "Expired") text else text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = when {
            closeEpochMs == null -> TextSecondary
            expired -> AccentOrange
            else -> AccentBlue
        }
    )
}
