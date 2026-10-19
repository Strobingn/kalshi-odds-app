package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import java.util.Locale

/**
 * Enhanced Market Card - wrapper around MarketCard with additional info display
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnhancedMarketCard(
    market: MarketUiModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    decision: BetCall.Decision? = null,
    settings: SignalSettings = SignalSettings(),
    paperTradingEnabled: Boolean = false,
    nowMs: Long? = null,
    onBuyYes: (() -> Unit)? = null,
    onBuyNo: (() -> Unit)? = null,
    onPaperUp: (() -> Unit)? = null,
    onPaperDown: (() -> Unit)? = null,
    paperPosition: String? = null,
    onSell: (() -> Unit)? = null,
    onOpenChart: (() -> Unit)? = null,
    detailsInitiallyOpen: Boolean = true
) {
    val clock = nowMs ?: System.currentTimeMillis()
    val call = decision ?: BetCall.decide(market, settings, clock)
    
    // Add enhanced header with AI probabilities
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Enhanced info row showing AI predictions
        val aiYes = market.aiYesPercent ?: 50.0
        val aiNo = market.aiNoPercent ?: 50.0
        val marketMid = market.yesProbabilityPercent ?: 50.0
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AI: ${String.format(Locale.US, "%.1f", aiYes)}% YES / ${String.format(Locale.US, "%.1f", aiNo)}% NO",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Market: ${String.format(Locale.US, "%.1f", marketMid)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        
        // Edge display
        market.edgePp?.let { edge ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                val edgeColor = if (edge > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Text(
                    text = "Edge: ${String.format(Locale.US, "%.2f", edge)}pp",
                    style = MaterialTheme.typography.labelMedium,
                    color = edgeColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // Use the standard MarketCard for the rest
        MarketCard(
            market = market,
            modifier = Modifier.fillMaxWidth(),
            compact = compact,
            decision = decision,
            settings = settings,
            paperTradingEnabled = paperTradingEnabled,
            nowMs = nowMs,
            onBuyYes = onBuyYes,
            onBuyNo = onBuyNo,
            onPaperUp = onPaperUp,
            onPaperDown = onPaperDown,
            paperPosition = paperPosition,
            onSell = onSell,
            onOpenChart = onOpenChart,
            detailsInitiallyOpen = detailsInitiallyOpen
        )
    }
}
