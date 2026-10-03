package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.TimeLeft
import kotlinx.coroutines.delay
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun TimeLeftLabel(
    closeEpochMs: Long?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    pill: Boolean = false,
    nowMs: Long? = null
) {
    val colors = DipTheme.colors
    var clockMs by remember { mutableLongStateOf(nowMs ?: System.currentTimeMillis()) }
    LaunchedEffect(closeEpochMs, nowMs) {
        if (nowMs != null) {
            clockMs = nowMs
            return@LaunchedEffect
        }
        while (true) {
            clockMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val expired = TimeLeft.isExpired(closeEpochMs, clockMs)
    val text = TimeLeft.format(closeEpochMs, clockMs)
    val color = when {
        closeEpochMs == null -> colors.textSecondary
        expired -> colors.accentOrange
        else -> colors.accentBlue
    }
    Text(
        text = if (compact && text != "—" && text != "Expired") text else text,
        modifier = if (pill) {
            modifier
                .background(color.copy(alpha = 0.16f), com.dirk.kalshiodds.ui.theme.FieldShapes.chip)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        } else {
            modifier
        },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color
    )
}
