package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.domain.MarketUiModel
import java.util.Locale
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun TargetNowLine(market: MarketUiModel, modifier: Modifier = Modifier) {
    val colors = DipTheme.colors
    val line = KalshiQuoteDisplay.targetNowLine(market.floorStrike, market.spotUsd) ?: return
    val up = (market.spotVsTargetUsd ?: 0.0) >= 0.0
    Text(
        line,
        style = MaterialTheme.typography.bodyMedium,
        color = if (up) colors.accentGreen else colors.accentRed,
        fontWeight = FontWeight.Bold,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun TapeConflictBanner(market: MarketUiModel, modifier: Modifier = Modifier) {
    val colors = DipTheme.colors
    if (!market.tapeConflict || market.tapeConflictNote.isNullOrBlank()) return
    Text(
        text = market.tapeConflictNote.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.accentOrange,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.accentOrange.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    )
}

@Composable
fun MarketAskHero(market: MarketUiModel, modifier: Modifier = Modifier) {
    val colors = DipTheme.colors
    val quotes = com.dirk.kalshiodds.domain.MarketQuoteView.of(market)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AskHeroSide(
            title = "UP",
            price = quotes.upHero,
            multiple = quotes.upMultiple,
            color = colors.accentGreen,
            emphasized = market.primaryHeroSide != "NO"
        )
        AskHeroSide(
            title = "DOWN",
            price = quotes.downHero,
            multiple = quotes.downMultiple,
            color = colors.accentRed,
            emphasized = market.primaryHeroSide == "NO"
        )
    }
}

@Composable
private fun AskHeroSide(
    title: String,
    price: String,
    multiple: Double?,
    color: androidx.compose.ui.graphics.Color,
    emphasized: Boolean
) {
    val colors = DipTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) color else colors.textSecondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            price,
            fontSize = if (emphasized) 40.sp else 32.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) color else color.copy(alpha = 0.7f),
            lineHeight = if (emphasized) 44.sp else 36.sp
        )
        Text(
            multiple?.let { String.format(Locale.US, "%.2fx", it) } ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) color else colors.textSecondary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun AiFairLabel(market: MarketUiModel, modifier: Modifier = Modifier) {
    val colors = DipTheme.colors
    val ai = KalshiQuoteDisplay.aiLabel(
        (market.importedModelPp ?: market.aiYesPercent)?.div(100.0)
    ) ?: return
    Text(
        ai + (market.digitalFairPp?.let { String.format(Locale.US, "  ·  Fair %.0f¢", it) } ?: ""),
        color = colors.accentBlue,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

@Composable
fun PastSettlementsRow(results: List<Boolean>, modifier: Modifier = Modifier) {
    val colors = DipTheme.colors
    if (results.isEmpty()) return
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Past", color = colors.textSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        results.take(8).forEach { yes ->
            Text(
                if (yes) "▲" else "▼",
                color = if (yes) colors.accentGreen else colors.accentRed,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun UpDownBuyButtons(
    market: MarketUiModel,
    onBuyYes: () -> Unit,
    onBuyNo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    val quotes = com.dirk.kalshiodds.domain.MarketQuoteView.of(market)
    val tapeUp = market.primaryHeroSide == "YES" ||
        (market.primaryHeroSide == null && (quotes.yesAsk ?: 0.5) >= 0.5)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onBuyYes,
            modifier = Modifier.weight(1f).height(52.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = if (tapeUp) colors.accentGreen else colors.accentGreen.copy(alpha = 0.75f),
                contentColor = colors.onAccentGreen
            )
        ) { Text(quotes.upButton) }
        Button(
            onClick = onBuyNo,
            modifier = Modifier.weight(1f).height(52.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = colors.accentRed,
                contentColor = colors.onAccentRed
            )
        ) { Text(quotes.downButton) }
    }
}
