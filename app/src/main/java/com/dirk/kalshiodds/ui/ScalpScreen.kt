package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.ScalpStrategies
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated Scalp tab. Paper-only audit surface for the six parallel
 * strategies: leaderboard, open fills with their exit rules, and the
 * settled ledger. No buy control and no Kalshi dependency.
 */
@Composable
fun ScalpScreen(
    state: OddsUiState,
    onSetPaperTrading: (Boolean) -> Unit,
    onResetPaper: () -> Unit
) {
    val colors = DipTheme.colors
    val enabled = state.settings.paperTradingEnabled
    val scalpFills = state.paper.fills
        .filter { ScalpStrategies.strategyOf(it.source) != null }
        .sortedByDescending { it.createdAtMs }
    val openFills = scalpFills.filterNot { it.settled }
    val closedFills = scalpFills.filter { it.settled }
    val board = ScalpStrategies.leaderboard(state.paper.fills)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Scalp",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Six parallel paper-only strategies: mid window reversal, " +
                        "t minus exit, book imbalance, rollover gap, lead lag, " +
                        "and the AI edge baseline. Never hits Kalshi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, colors.border, RoundedCornerShape(16.dp))
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (enabled) "Paper scalping on" else "Paper scalping off",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Uncapped \u00b7 $%.2f equity \u00b7 %d open".format(
                            state.paper.equityUsd,
                            state.paper.openCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
                Switch(checked = enabled, onCheckedChange = onSetPaperTrading)
            }
        }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, colors.border, RoundedCornerShape(16.dp))
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "LEADERBOARD",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                if (board.isEmpty()) {
                    Text(
                        "No settled scalp fills yet \u2014 the leaderboard ranks " +
                            "strategies by realized paper P&L once windows settle.",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
                board.forEach { (kind, pnl) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            kind.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            String.format(Locale.US, "%+.2f", pnl),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        item {
            SizingDataCard(state = state, colors = colors)
        }

        item {
            Text(
                "OPEN (${openFills.size})",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        if (openFills.isEmpty()) {
            item {
                Text(
                    "No open scalp fills \u2014 strategies fire on live windows.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
        items(openFills.size) { i ->
            ScalpFillRow(openFills[i], colors = colors)
        }

        item {
            Text(
                "SETTLED (${closedFills.size})",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        if (closedFills.isEmpty()) {
            item {
                Text(
                    "Nothing settled yet. Fills settle with their 15-minute window.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
        items(closedFills.size) { i ->
            ScalpFillRow(closedFills[i], colors = colors)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onResetPaper, modifier = Modifier.height(44.dp)) {
                    Text("Reset paper to $20,000")
                }
            }
        }
    }
}

@Composable
private fun ScalpFillRow(fill: PaperFill, colors: com.dirk.kalshiodds.ui.theme.DipPalette) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${fill.displaySide} ${fill.contracts} ct @ ${"%.0f".format(fill.limitPrice * 100)}\u00a2",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                String.format(Locale.US, "%+.2f", fill.pnlUsd ?: 0.0),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
        }
        Text(
            "${fill.source} \u00b7 ${fill.ticker}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Text(
            fill.note,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Text(
            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(fill.createdAtMs)),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun SizingDataCard(state: OddsUiState, colors: com.dirk.kalshiodds.ui.theme.DipPalette) {
    val paper = state.paper
    val settings = state.settings

    // Paper learning sizer (AggressivePaperSizer)
    val settledWins = paper.fills.count { it.won == true }
    val settledTotal = paper.fills.count { it.won != null }
    val realizedPnl = paper.fills.mapNotNull { it.pnlUsd }.sum()
    val learned = com.dirk.kalshiodds.signal.paper.AggressivePaperSizer.multiplier(
        settledWins = settledWins,
        settledTotal = settledTotal,
        pnlUsd = realizedPnl
    )
    val nextStake = com.dirk.kalshiodds.signal.paper.AggressivePaperSizer.stakeUsd(
        paper.equityUsd, learned
    )

    // Live edge sizer (quarter-Kelly) evaluated at the home card's live ask,
    // when a live BTC market is present.
    val liveBtc = state.snapshot?.allMarkets?.firstOrNull {
        com.dirk.kalshiodds.domain.CryptoMarkets.isLiveTicker(it.ticker)
    }
    val ask = liveBtc?.yesAsk
    val modelPct = liveBtc?.aiYesPercent
    val edgeCap = if (ask != null && modelPct != null) {
        com.dirk.kalshiodds.signal.trade.LiveOrderSizer.edgeCapUsd(
            modelProb = modelPct / 100.0,
            ask = ask,
            bankrollUsd = settings.bankrollUsd,
            kellyFraction = settings.kellyFraction
        )
    } else {
        null
    }
    val clip = edgeCap?.let {
        com.dirk.kalshiodds.signal.trade.LiveOrderSizer.size(ask!!, it, settings.feeRate)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "SIZING ALGORITHMS",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        // Paper: uncapped learning equity-fraction
        SizingRow("paper engine", "learning equity-fraction (uncapped)", colors)
        SizingRow(
            "settled record",
            "$settledWins W / ${(settledTotal - settledWins).coerceAtLeast(0)} L of $settledTotal",
            colors
        )
        SizingRow(
            "realized P&L",
            String.format(Locale.US, "%+.2f", realizedPnl),
            colors
        )
        SizingRow(
            "learning multiplier",
            String.format(Locale.US, "%.2f\u00d7", learned),
            colors
        )
        SizingRow(
            "equity fraction",
            String.format(Locale.US, "%.0f%% of equity", com.dirk.kalshiodds.signal.config.SignalConstants.PAPER_AGGRESSIVE_FRACTION * 100.0),
            colors
        )
        SizingRow(
            "next AI stake",
            String.format(Locale.US, "$%.2f", nextStake),
            colors
        )

        // Live: quarter-Kelly edge sizing with $5 floor
        SizingRow("live engine", "quarter-Kelly edge cap (min $5)", colors)
        if (edgeCap != null && clip != null && clip.ok) {
            SizingRow(
                "BTC window",
                "${"%.0f".format(modelPct!!)}% AI @ ${"%.0f".format(ask!! * 100)}\u00a2",
                colors
            )
            SizingRow(
                "edge cap",
                String.format(Locale.US, "$%.2f (kelly %.0f%%)", edgeCap, settings.kellyFraction * 100.0),
                colors
            )
            SizingRow(
                "sized clip",
                "${clip.count} ct \u00b7 all-in ${String.format(Locale.US, "$%.2f", clip.allInUsd)} \u00b7 fee ${String.format(Locale.US, "$%.2f", clip.feeUsd)}",
                colors
            )
            SizingRow(
                "profit if win",
                String.format(Locale.US, "$%.2f", clip.profitIfWinUsd),
                colors
            )
        } else {
            SizingRow(
                "sized clip",
                if (edgeCap == null) "waiting for a live BTC window" else "no usable edge / ask",
                colors
            )
        }
    }
}

@Composable
private fun SizingRow(label: String, value: String, colors: com.dirk.kalshiodds.ui.theme.DipPalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
