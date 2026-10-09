package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TradeModeLabel
import com.dirk.kalshiodds.ui.theme.DipTheme


/**
 * Reconstruction of the 0.3.11 home (f0e26ca) for before/after screenshots.
 * Same fake markets as the redesigned [HomeScreen] shots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyHome0311(
    dark: Boolean,
    hasKey: Boolean,
    btc: MarketUiModel,
    eth: MarketUiModel,
    sol: MarketUiModel,
    nowMs: Long
) {
    val colors = DipTheme.colors
    val settings = HomeFixtures.settings(hasKey)
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("Dip Hunter") },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Default.History, contentDescription = "History") }
                    IconButton(onClick = {}) { Icon(Icons.Default.FolderOpen, contentDescription = "Data") }
                    IconButton(onClick = {}) { Icon(Icons.Default.Assessment, contentDescription = "Scorecard") }
                    IconButton(onClick = {}) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    IconButton(onClick = {}) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    actionIconContentColor = colors.accentBlue
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasKey) {
                Text(
                    ApiKeyUi.BANNER,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accentOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
            }
            Text("Updated 2:00:00 PM", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Text(
                "Scorecard: 12/18 · Brier 0.211",
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentBlue,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "CRYPTO ONLY · BTC/ETH/SOL 15m · Paper vs Live Approve · no auto-fire.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text("Live markets", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
            LegacyCard(btc, settings, nowMs)
            LegacyCard(eth, settings, nowMs)
            LegacyCard(sol, settings, nowMs)
            Text("Paper book", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
            Text(
                "Start / reset $100 · win-target sizing · never hits Kalshi",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text("Positions", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
            Text(
                "Live Kalshi holdings (GET /portfolio/positions). Sell opens an approve-gated V2 reduce-only limit — never the retired v1 path.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text("DipHunter v0.3.11 (26)", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            Text(if (dark) "dark" else "light", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun LegacyCard(market: MarketUiModel, settings: SignalSettings, nowMs: Long) {
    val colors = DipTheme.colors
    val call = BetCall.decide(market, settings, nowMs)
    val headlineColor = SideColor.of(call.headline, colors)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, headlineColor, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(market.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(market.ticker, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Text(
            call.label,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            color = headlineColor,
            lineHeight = 38.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
        val ask = KalshiQuoteDisplay.formatAsk(call.ask ?: market.yesAsk)
        val profit = call.profitIfWinUsd
        Text(
            buildString {
                append("Ask $ask")
                if (profit != null) append(String.format(java.util.Locale.US, "  ·  win $%.2f at $5 cap", profit))
                call.noBetReason?.let { append("  ·  $it") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp)
        )
        val mode = TradeModeLabel.forApprove(settings, call.ticket)
        Button(
            onClick = {},
            enabled = call.isActionable,
            colors = ButtonDefaults.buttonColors(
                containerColor = headlineColor,
                contentColor = SideColor.on(call.headline, colors)
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(56.dp)
        ) {
            Text(if (call.isActionable) "$mode  Buy UP" else "NO BET", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(44.dp)) {
            Text("Buy DOWN")
        }
        Text("Details", style = MaterialTheme.typography.labelLarge, color = colors.accentBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("closes ${HomeCopy.closesIn(market.closeTimeEpochMs, nowMs)}", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }
}
