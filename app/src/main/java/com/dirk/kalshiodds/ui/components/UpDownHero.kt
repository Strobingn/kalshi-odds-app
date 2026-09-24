package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.signal.model.LiveCall
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.AccentRed
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun UpDownHero(call: LiveCall?, live: Boolean, modifier: Modifier = Modifier) {
    val up = call?.upPct
    val down = call?.downPct
    val dir = call?.direction
    val upColor = if (dir == LiveCall.UP) AccentGreen else TextSecondary
    val downColor = if (dir == LiveCall.DOWN) AccentRed else TextSecondary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "LIVE CALL",
                style = MaterialTheme.typography.labelMedium,
                color = if (live) AccentGreen else TextSecondary,
                fontWeight = FontWeight.Bold
            )
            Text(
                call?.ticker ?: "waiting for feed",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
        Text(
            dir ?: "—",
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
            color = if (dir == LiveCall.DOWN) AccentRed else AccentGreen,
            lineHeight = 60.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.Start) {
                Text("UP", style = MaterialTheme.typography.labelMedium, color = upColor, fontWeight = FontWeight.Bold)
                Text(
                    up?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = upColor,
                    lineHeight = 44.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("DOWN", style = MaterialTheme.typography.labelMedium, color = downColor, fontWeight = FontWeight.Bold)
                Text(
                    down?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = downColor,
                    lineHeight = 44.sp
                )
            }
        }
        call?.let {
            Text(
                String.format(Locale.US, "mkt %.1f%%  ·  edge %+.1fpp", it.marketYesPct, it.edgePp),
                style = MaterialTheme.typography.labelMedium,
                color = AccentOrange,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
