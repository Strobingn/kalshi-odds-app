package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.Border
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun MarketCard(market: MarketUiModel, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Border, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = market.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    market.subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    StatusChip(market.status)
                    Text(
                        text = market.ticker,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "DIP HUNTER AI",
                style = MaterialTheme.typography.labelMedium,
                color = AccentBlue,
                fontWeight = FontWeight.Bold
            )
            market.aiNote?.let {
                Text(
                    text = it + market.aiConfidence?.let { c ->
                        String.format(Locale.US, " · conf %.0f%%", c * 100)
                    }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OddsColumn(
                    label = "AI YES",
                    percent = market.aiYesPercent,
                    bid = null,
                    ask = null,
                    accent = AccentGreen,
                    modifier = Modifier.weight(1f),
                    big = true
                )
                OddsColumn(
                    label = "AI NO",
                    percent = market.aiNoPercent,
                    bid = null,
                    ask = null,
                    accent = AccentOrange,
                    modifier = Modifier.weight(1f),
                    endAligned = true,
                    big = true
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Kalshi market (reference)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OddsColumn(
                    label = "Mkt YES",
                    percent = market.yesProbabilityPercent,
                    bid = market.yesBid,
                    ask = market.yesAsk,
                    accent = TextSecondary,
                    modifier = Modifier.weight(1f),
                    big = false
                )
                OddsColumn(
                    label = "Mkt NO",
                    percent = market.noProbabilityPercent,
                    bid = market.noBid,
                    ask = market.noAsk,
                    accent = TextSecondary,
                    modifier = Modifier.weight(1f),
                    endAligned = true,
                    big = false
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Volume", formatCompact(market.volume))
                Metric("24h vol", formatCompact(market.volume24h))
                Metric("Closes", market.closeTimeLocal ?: "—")
            }
        }
    }
}

@Composable
private fun OddsColumn(
    label: String,
    percent: Double?,
    bid: Double?,
    ask: Double?,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    endAligned: Boolean = false,
    big: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (endAligned) Alignment.End else Alignment.Start
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            text = percent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
            fontSize = if (big) 36.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            lineHeight = if (big) 40.sp else 26.sp
        )
        if (bid != null || ask != null) {
            Text(
                text = "Bid ${formatCents(bid)} · Ask ${formatCents(ask)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusChip(status: String?) {
    val label = status?.ifBlank { null } ?: "unknown"
    val color = when (label.lowercase(Locale.US)) {
        "active", "open" -> AccentGreen
        "closed", "determined" -> AccentOrange
        else -> AccentBlue
    }
    Text(
        text = label.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Bold
    )
}

private fun formatCents(dollars: Double?): String =
    dollars?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—"

private fun formatCompact(value: Double?): String {
    if (value == null) return "—"
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
