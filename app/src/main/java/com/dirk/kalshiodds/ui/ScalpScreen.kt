package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.signal.paper.PaperArchive
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.ScalpReview
import com.dirk.kalshiodds.signal.trade.ScalpSignal
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.theme.DipTheme
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
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    if (activeTab == 1) {
        V3LabScreen(
            state = state.v3,
            onBack = onBack,
            onOpenArena = { activeTab = 0 },
            onOpenChat = { activeTab = 2 }
        )
        return
    }
    if (activeTab == 2) {
        PaperChatScreen(state, onBack, onSelectTab = { activeTab = it })
        return
    }
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
    val scorecards = ScalpSignal.Track.values().map { track ->
        ScalpTrackScore.from(track, scalpFills.filter { it.strategyVersion == track.formulaVersion })
    }
    val reviewReady = scorecards.count { it.review.decision != ScalpReview.Decision.COLLECT }

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
                        "Twenty concurrent paper-only hypotheses on each 15-minute contract.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        }

        item {
            ScalpModeTabs(selected = 0, onSelect = { selected -> activeTab = selected })
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
            ReviewProtocolCard(scorecards = scorecards, reviewReady = reviewReady)
        }

        item {
            ScalpPerformanceChart(scorecards)
        }

        item {
            SectionTitle("Model scoreboard", "Current paper run · realized outcomes only, not a profit forecast")
        }
        items(scorecards, key = { it.track.formulaVersion }) { scorecard ->
            ScalpScorecard(scorecard)
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
                        "Paper autopilot is paused. Turn it on above to allow all twenty tracks to open paper positions."
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

/** After-fee realized paper results, with the sample size visible for every track. */
@Composable
private fun ScalpPerformanceChart(scorecards: List<ScalpTrackScore>) {
    val colors = DipTheme.colors
    val maximum = scorecards.maxOfOrNull { kotlin.math.abs(it.realizedPnlUsd) }?.coerceAtLeast(0.01) ?: 0.01
    Column(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .background(colors.surface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Strategy paper P&L", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text("Realized after fees · current run · open and void trades excluded. Small samples do not establish an edge.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        scorecards.sortedByDescending { it.realizedPnlUsd }.forEach { score ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(score.track.label, color = colors.textPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (score.settled == 0) "No settled trades" else String.format(Locale.US, "%+.2f USD · %d settled", score.realizedPnlUsd, score.settled),
                        color = colors.textSecondary, style = MaterialTheme.typography.labelSmall
                    )
                }
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val center = size.width / 2f
                    drawLine(colors.border, Offset(center, 0f), Offset(center, size.height), 1f)
                    if (score.settled > 0) {
                        val length = (kotlin.math.abs(score.realizedPnlUsd) / maximum).toFloat().coerceIn(0f, 1f) * center
                        val start = if (score.realizedPnlUsd >= 0.0) center else center - length
                        drawLine(if (score.realizedPnlUsd >= 0.0) colors.up else colors.down,
                            Offset(start, size.height / 2f), Offset(start + length, size.height / 2f), size.height * 0.65f)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewProtocolCard(scorecards: List<ScalpTrackScore>, reviewReady: Int) {
    val colors = DipTheme.colors
    val keeps = scorecards.count { it.review.decision == ScalpReview.Decision.KEEP }
    val tweaks = scorecards.count { it.review.decision == ScalpReview.Decision.TWEAK }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .background(colors.surface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "30-minute experiment review",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            "$reviewReady/${scorecards.size} tracks have enough settled evidence · keep $keeps · tweak candidates $tweaks",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
        Text(
            "A track needs ${ScalpReview.MIN_SETTLED_FILLS} non-void settled fills across ${ScalpReview.MIN_DISTINCT_DAYS} UTC days. The review uses after-fee P&L per contract and resamples whole days; it labels the next named variant but does not erase, disable, or silently retune a live track. The paper ledger is also synced after changes and on Android's inexact 30-minute network schedule for the independent review.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
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
                        "All twenty tracks may fill independently on the same 15-minute contract."
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
            ScalpStat("Start", money(state.paper.startingUsd), colors.textPrimary)
            ScalpStat("Open", money(openStake), colors.accentOrange)
            ScalpStat("P&L", signedMoney(realized), pnlColor(realized, colors))
        }
        Text(
            "Paper and scalp share one ledger; reset starts it at \$100. Synthetic credit is unrestricted, and each fill uses the displayed touch quantity. No action on this tab can place a Kalshi order.",
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
            Text("Reset paper to \$100")
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
private fun ScalpScorecard(scorecard: ScalpTrackScore) {
    val colors = DipTheme.colors
    val pnl = scorecard.realizedPnlUsd
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(scorecard.track.label, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(scorecard.track.family, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
            Text(
                if (scorecard.settled == 0) "NO RESULT YET" else signedMoney(pnl),
                style = MaterialTheme.typography.bodyMedium,
                color = if (scorecard.settled == 0) colors.textSecondary else pnlColor(pnl, colors),
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            if (scorecard.settled == 0) {
                "${scorecard.fills} fills · ${scorecard.open} open · needs settled outcomes before ranking"
            } else {
                "${scorecard.fills} fills · ${scorecard.open} open · ${scorecard.wins}/${scorecard.settled} wins (${String.format(Locale.US, "%.0f", scorecard.winRate * 100)}%) · ${afterFeePerContract(scorecard.review.meanPnlPerContractUsd)} / ct · realized ${signedMoney(pnl)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        val reviewColor = when (scorecard.review.decision) {
            ScalpReview.Decision.KEEP -> colors.up
            ScalpReview.Decision.TWEAK -> colors.accentOrange
            ScalpReview.Decision.COLLECT -> colors.textSecondary
        }
        Text(
            scorecard.review.decision.label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelLarge,
            color = reviewColor,
            fontWeight = FontWeight.Bold
        )
        Text(
            scorecard.review.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        if (scorecard.review.lower95PerContractUsd != null) {
            Text(
                "After-fee ${signedMoney(scorecard.review.meanPnlPerContractUsd ?: 0.0)}/ct · 95% day-resample floor ${signedMoney(scorecard.review.lower95PerContractUsd)}/ct",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
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
        fill.strategyDecisionFingerprint?.let { fingerprint ->
            Text(
                "Decision $fingerprint",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }
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

private fun afterFeePerContract(value: Double?): String = value?.let { signedMoney(it) } ?: "—"

private fun signed(value: Double): String = String.format(Locale.US, "%+.4f", value)

private fun timeLeft(seconds: Long): String = "%d:%02d".format(Locale.US, seconds / 60L, seconds % 60L)

private fun timeLabel(epochMs: Long): String = com.dirk.kalshiodds.domain.EasternTime.short(epochMs)

private fun pnlColor(value: Double, colors: com.dirk.kalshiodds.ui.theme.DipPalette): Color = when {
    value > 0.0 -> colors.up
    value < 0.0 -> colors.down
    else -> colors.textPrimary
}

private data class ArchivedScalpFill(
    val archivedAtMs: Long,
    val fill: PaperFill
)

private data class ScalpTrackScore(
    val track: ScalpSignal.Track,
    val fills: Int,
    val open: Int,
    val settled: Int,
    val wins: Int,
    val realizedPnlUsd: Double,
    val review: ScalpReview.Assessment
) {
    val winRate: Double get() = if (settled == 0) 0.0 else wins.toDouble() / settled.toDouble()

    companion object {
        fun from(track: ScalpSignal.Track, fills: List<PaperFill>): ScalpTrackScore {
            val settled = fills.filter { it.settled && it.won != null }
            return ScalpTrackScore(
                track = track,
                fills = fills.size,
                open = fills.count { !it.settled },
                settled = settled.size,
                wins = settled.count { it.won == true },
                realizedPnlUsd = settled.mapNotNull { it.pnlUsd }.sum(),
                review = ScalpReview.assess(fills)
            )
        }
    }
}
