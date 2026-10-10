package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperArchive
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.ScalpSignal
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated audit surface for the paper-only scalp experiment. It has no buy
 * control and no Kalshi client dependency: toggling it only enables/disables
 * synthetic paper fills in [OddsViewModel].
 */
@Composable
fun ScalpScreen(
    state: OddsUiState,
    onBack: () -> Unit,
    onSetPaperTrading: (Boolean) -> Unit,
    onResetPaper: () -> Unit
) {
    val colors = DipTheme.colors
    val scalpFills = state.paper.fills.filter(PaperFill::isScalp).sortedByDescending { it.createdAtMs }
    val openFills = scalpFills.filterNot { it.settled }
    val closedFills = scalpFills.filter { it.settled }
    val archivedScalps = state.paper.archived
        .sortedByDescending(PaperArchive::archivedAtMs)
        .flatMap { archive ->
            archive.fills.filter(PaperFill::isScalp).map { fill ->
                ArchivedScalpFill(archive.archivedAtMs, fill)
            }
        }
    val candidates = state.tickets.proposals
        .filter { it.kind == TicketKind.SCALP }
        .sortedBy { it.strategyVersion.orEmpty() }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Home",
                        tint = colors.accentBlue
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "AI Scalp",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Eight concurrent paper-only strategies on each 15-minute contract.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        }

        item {
            ScalpControlCard(
                state = state,
                scalpFills = scalpFills,
                onSetPaperTrading = onSetPaperTrading,
                onResetPaper = onResetPaper
            )
        }

        item {
            SectionTitle("Live candidates", "Current executable-paper proposals")
        }
        if (candidates.isEmpty()) {
            item {
                EmptyScalpCard(
                    if (state.settings.paperTradingEnabled) {
                        "Waiting for an open BTC window with an executable displayed ask. This is a quote requirement, not a stake or time cap."
                    } else {
                        "Paper autopilot is paused. Turn it on above to allow all eight tracks to open paper positions."
                    }
                )
            }
        } else {
            items(candidates, key = { it.strategyVersion ?: it.id }) { ticket ->
                ScalpCandidateCard(ticket)
            }
        }

        item {
            SectionTitle("Open scalp positions", "${openFills.size} currently held")
        }
        if (openFills.isEmpty()) {
            item { EmptyScalpCard("No open scalp positions yet.") }
        } else {
            items(openFills, key = PaperFill::id) { fill ->
                ScalpFillCard(fill)
            }
        }

        item {
            SectionTitle("Completed scalp ledger", "${closedFills.size} recorded exits and settlements")
        }
        if (closedFills.isEmpty()) {
            item { EmptyScalpCard("Completed exits will stay here with their entry, high-water mark, fees, result, and exit reason.") }
        } else {
            items(closedFills, key = PaperFill::id) { fill ->
                ScalpFillCard(fill)
            }
        }

        item {
            SectionTitle("Archived scalp runs", "${archivedScalps.size} entries retained after paper resets")
        }
        if (archivedScalps.isEmpty()) {
            item { EmptyScalpCard("No archived scalp runs. Resetting paper archives the current ledger here instead of deleting it.") }
        } else {
            items(archivedScalps, key = { "${it.archivedAtMs}-${it.fill.id}" }) { archived ->
                ScalpFillCard(archived.fill, archivedAtMs = archived.archivedAtMs)
            }
        }
    }
}

@Composable
private fun ScalpControlCard(
    state: OddsUiState,
    scalpFills: List<PaperFill>,
    onSetPaperTrading: (Boolean) -> Unit,
    onResetPaper: () -> Unit
) {
    val colors = DipTheme.colors
    val realized = scalpFills.mapNotNull { it.pnlUsd }.sum()
    val openStake = scalpFills.filterNot { it.settled }.sumOf { it.stakeUsd }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, if (state.settings.paperTradingEnabled) colors.accentBlue else colors.border, RoundedCornerShape(18.dp))
            .background(colors.surface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (state.settings.paperTradingEnabled) "SCALPER ARMED" else "SCALPER PAUSED",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.settings.paperTradingEnabled) colors.accentBlue else colors.textSecondary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (state.settings.paperTradingEnabled) {
                        "All eight tracks may fill independently on the same 15-minute contract."
                    } else {
                        "No new paper positions will open until the autopilot is switched back on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Switch(
                checked = state.settings.paperTradingEnabled,
                onCheckedChange = onSetPaperTrading
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ScalpStat("Start", money(SignalConstants.PAPER_START_USD), colors.textPrimary)
            ScalpStat("Open", money(openStake), colors.accentOrange)
            ScalpStat("P&L", signedMoney(realized), pnlColor(realized, colors))
        }
        Text(
            "Paper only: \$100,000 starting balance, unrestricted synthetic credit, and each fill uses the displayed touch quantity. No action on this tab can place a Kalshi order.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        state.paper.lastMessage?.let { message ->
            Text(
                message,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceAlt, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary
            )
        }
        OutlinedButton(onClick = onResetPaper) {
            Text("Reset paper to \$100,000")
        }
    }
}

@Composable
private fun ScalpCandidateCard(ticket: TradeTicket) {
    val colors = DipTheme.colors
    val isUp = ticket.displaySide != "NO"
    val sideColor = if (isUp) colors.up else colors.down
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                trackLabel(ticket.strategyVersion),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "BUY ${SignalCopy.callLabel(ticket.side)}",
                style = MaterialTheme.typography.labelLarge,
                color = sideColor,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "${WindowLabel.of(ticket.ticker)} · ${ticket.visibleContracts ?: ticket.contracts} visible ct @ ${KalshiQuoteDisplay.formatPriceCents(ticket.estimatedAvgFill.takeIf { it > 0.0 } ?: ticket.limitPrice)}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
        ticket.strategyDecisionSource?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Text(
            listOfNotNull(
                ticket.strategySpotReturn1m?.let { "1m spot ${signed(it)}" },
                ticket.strategySpotReturn5m?.let { "5m spot ${signed(it)}" },
                ticket.strategyTimeToCloseSec?.let { "${timeLeft(it)} left" }
            ).ifEmpty { listOf("Waiting for live spot features") }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
    }
}

@Composable
private fun ScalpFillCard(fill: PaperFill, archivedAtMs: Long? = null) {
    val colors = DipTheme.colors
    val isOpen = !fill.settled
    val result = when {
        isOpen -> "OPEN"
        fill.outcome == "void" -> "VOID"
        fill.won == true -> "WIN"
        else -> "LOSS"
    }
    val resultColor = when (result) {
        "OPEN" -> colors.accentOrange
        "WIN" -> colors.up
        "LOSS" -> colors.down
        else -> colors.textSecondary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, resultColor.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                trackLabel(fill.strategyVersion, fill.note),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(result, style = MaterialTheme.typography.labelLarge, color = resultColor, fontWeight = FontWeight.Bold)
        }
        Text(
            "${SignalCopy.callLabel(fill.side)} ${WindowLabel.of(fill.ticker)} · ${fill.contracts} ct @ ${KalshiQuoteDisplay.formatPriceCents(fill.limitPrice)}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
        Text(
            "Entry ${money(fill.stakeUsd)} · fees ${money(fill.feeUsd + fill.exitFeeUsd)} · high-water ${KalshiQuoteDisplay.formatPriceCents(fill.highWaterMarkPrice.takeIf { it > 0.0 } ?: fill.limitPrice)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                archivedAtMs?.let { "Archived ${timeLabel(it)} · opened ${timeLabel(fill.createdAtMs)}" }
                    ?: timeLabel(fill.createdAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            fill.pnlUsd?.let { pnl ->
                Text(
                    signedMoney(pnl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = pnlColor(pnl, colors),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(fill.note, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val colors = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

@Composable
private fun EmptyScalpCard(text: String) {
    val colors = DipTheme.colors
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSecondary
    )
}

@Composable
private fun ScalpStat(label: String, value: String, color: Color) {
    val colors = DipTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

private fun PaperFill.isScalp(): Boolean =
    strategyVersion?.startsWith("scalp-") == true || note.contains("paper scalping", ignoreCase = true)

private fun trackLabel(strategyVersion: String?, note: String = ""): String =
    ScalpSignal.Track.values().firstOrNull { it.formulaVersion == strategyVersion }?.label
        ?: if (note.contains("paper scalping", ignoreCase = true)) "Value Scanner"
        else "Paper Scalp"

private fun money(value: Double): String = String.format(Locale.US, "$%.2f", value)

private fun signedMoney(value: Double): String = String.format(Locale.US, "%+.2f", value)

private fun signed(value: Double): String = String.format(Locale.US, "%+.4f", value)

private fun timeLeft(seconds: Long): String = "%d:%02d".format(Locale.US, seconds / 60L, seconds % 60L)

private fun timeLabel(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

private fun pnlColor(value: Double, colors: com.dirk.kalshiodds.ui.theme.DipPalette): Color = when {
    value > 0.0 -> colors.up
    value < 0.0 -> colors.down
    else -> colors.textPrimary
}

private data class ArchivedScalpFill(
    val archivedAtMs: Long,
    val fill: PaperFill
)
