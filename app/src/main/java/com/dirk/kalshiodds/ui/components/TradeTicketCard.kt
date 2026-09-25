package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketUiState
import com.dirk.kalshiodds.signal.trade.TradeModeLabel
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import java.util.Locale
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun TradeTicketsSection(
    tickets: TicketUiState,
    credentialsConfigured: Boolean,
    paperTradingEnabled: Boolean = false,
    homeMode: Boolean = false,
    onReview: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onApprove: (String) -> Unit,
    onApproveSell: (String, Int, Double) -> Unit = { id, _, _ -> onApprove(id) },
    onPaper: (String) -> Unit,
    onPaperSell: (String, Int, Double) -> Unit = { id, _, _ -> onPaper(id) },
    onCancelApprove: () -> Unit,
    onCancelOrder: (String) -> Unit
) {
    val colors = DipTheme.colors
    val proposals = tickets.proposals.sortedByDescending {
        if (it.kind == TicketKind.HUNTER || it.kind == TicketKind.HUNTER_VALUE) 1_000.0 + it.maxPayoutUsd else it.maxPayoutUsd
    }
    val working = tickets.working.filter { it.orderId != null && it.error?.startsWith("cancelled") != true }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!homeMode) {
            Text(
                text = "Live Approve",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "LIVE \$ = real V2 GTC (\$5 all-in including fees). PAPER = simulated \$100 book. Paper fills never block Live. Paper trading ON does not swallow a keyed Live Approve. Hunter cards still appear when a \$1 stake can settle ≥\$25. Cancel leaves no live order.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            if (paperTradingEnabled && credentialsConfigured) {
                Text(
                    "Paper trading is ON for AI auto-log / the Paper button. Live Approve still sends a real Kalshi order after you confirm.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!credentialsConfigured) {
                Text(
                    text = "Add Kalshi API Key ID + PEM in Settings for Live Approve. Paper fills do not need keys. Keys stay on device and are never logged.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accentOrange
                )
            }
        }
        val visibleError = tickets.lastError
            ?.takeUnless { com.dirk.kalshiodds.signal.trade.TicketSession.stalePageError(it) }
        visibleError?.let {
            val paperOk = it.startsWith("PAPER ", ignoreCase = true)
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (paperOk) colors.textPrimary else colors.accentRed,
                fontWeight = FontWeight.SemiBold
            )
        }
        when (val phase = tickets.phase) {
            is TicketPhase.Submitting -> {
                Text(
                    "Submitting limit on ${phase.ticket.ticker}…",
                    color = colors.accentBlue,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            is TicketPhase.Submitted -> {
                Text(
                    "Limit resting · ${phase.order.ticket.ticker} · order ${phase.order.orderId ?: "pending id"}",
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            is TicketPhase.Failed -> {
                Text(
                    phase.error,
                    color = colors.accentRed,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                        .border(1.dp, colors.accentRed, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                )
            }
            else -> Unit
        }
        if (proposals.isEmpty() && working.isEmpty() && visibleError == null) {
            Text(
                "No pending tickets. Use Buy UP / Buy DOWN on a market, or Sell on Positions.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentBlue.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
        working.forEach { order ->
            WorkingOrderCard(order, onCancelOrder)
        }
        proposals.forEach { ticket ->
            ProposedTicketCard(
                ticket,
                credentialsConfigured,
                paperTradingEnabled,
                onReview,
                onDismiss,
                onPaper
            )
        }
    }

    val awaiting = tickets.phase as? TicketPhase.AwaitingApprove
    if (awaiting != null) {
        ApproveTicketDialog(
            ticket = awaiting.ticket,
            credentialsConfigured = credentialsConfigured,
            paperTradingEnabled = paperTradingEnabled,
            onApprove = { onApprove(awaiting.ticket.id) },
            onApproveSell = { count, price -> onApproveSell(awaiting.ticket.id, count, price) },
            onPaper = { onPaper(awaiting.ticket.id) },
            onPaperSell = { count, price -> onPaperSell(awaiting.ticket.id, count, price) },
            onDismiss = onCancelApprove
        )
    }
}

@Composable
private fun ProposedTicketCard(
    ticket: TradeTicket,
    credentialsConfigured: Boolean,
    paperTradingEnabled: Boolean = false,
    onReview: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onPaper: (String) -> Unit
) {
    val colors = DipTheme.colors
    val hunter = ticket.kind == TicketKind.HUNTER || ticket.kind == TicketKind.HUNTER_VALUE
    val highlightEdge = hunter && ticket.modelEdge
    val border = when {
        highlightEdge -> colors.accentOrange
        ticket.kind == TicketKind.HUNTER_VALUE -> colors.textSecondary
        hunter -> colors.accentOrange
        else -> colors.accentBlue
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (highlightEdge || ticket.kind == TicketKind.HUNTER) 2.dp else 1.dp, border, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when (ticket.kind) {
                        TicketKind.HUNTER -> "PENDING APPROVAL · Hunter"
                        TicketKind.HUNTER_VALUE -> "PENDING APPROVAL · Long-shot"
                        TicketKind.MANUAL -> "MANUAL BUY"
                        TicketKind.CONFIGURED -> "TICKET"
                        TicketKind.SELL -> if (ticket.paperOnly) "PAPER SELL" else "SELL · REDUCE-ONLY"
                    } + " · " + TradeModeLabel.forApprove(
                        paperTradingEnabled = paperTradingEnabled,
                        liveCredentialsConfigured = credentialsConfigured,
                        paperOnly = ticket.paperOnly,
                        isSell = ticket.isSell,
                        canApprove = ticket.canApprove,
                        blockedReason = ticket.blockedReason
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (highlightEdge || ticket.kind == TicketKind.HUNTER) colors.accentOrange else colors.accentBlue,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "%d ct", ticket.contracts),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
                Text(
                    "${com.dirk.kalshiodds.ui.SignalCopy.callLabel(ticket.side)} · ${com.dirk.kalshiodds.ui.WindowLabel.of(ticket.ticker)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            ticket.title?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
            if (ticket.blockedReason != null) {
                Text(
                    ticket.blockedReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentRed,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                        .border(1.dp, colors.accentRed, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                )
                ticket.gateNote?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            } else {
            Spacer(Modifier.height(8.dp))
            TicketMetricRow("Stake needed", String.format(Locale.US, "$%.2f", ticket.stakeUsd))
            TicketMetricRow("Contracts", String.format(Locale.US, "%d", ticket.contracts))
            TicketMetricRow(
                "Avg price",
                "${KalshiQuoteDisplay.formatPriceCents(ticket.estimatedAvgFill)}  (never market)"
            )
            ticket.visibleContracts?.let { vis ->
                TicketMetricRow(
                    "Size at that price",
                    String.format(Locale.US, "%d visible (need %d)", vis, ticket.contracts)
                )
            }
            TicketMetricRow(
                "Max payout",
                String.format(Locale.US, "$%.0f if %s wins", ticket.maxPayoutUsd, ticket.displaySide)
            )
            run {
                val profit = ticket.profitIfWinUsd ?: ticket.potentialGainUsd
                val target = ticket.winTargetUsd ?: profit
                val winsLabel = if (ticket.winTargetCapped) {
                    String.format(Locale.US, "Capped: wins $%.0f", profit)
                } else {
                    String.format(Locale.US, "Wins $%.0f", target)
                }
                TicketMetricRow(winsLabel, String.format(Locale.US, "$%.2f", profit))
            }
            ticket.bankrollUsd?.let { roll ->
                val src = when (ticket.bankrollSource) {
                    "live" -> "Kalshi cash"
                    "paper" -> "paper book"
                    else -> "settings bankroll"
                }
                TicketMetricRow("Bankroll", String.format(Locale.US, "$%.0f · %s", roll, src))
            }
            ticket.impliedChance?.let {
                TicketMetricRow("Implied chance", String.format(Locale.US, "%.0f%%", it * 100.0))
            }
            ticket.modelChance?.let {
                TicketMetricRow(
                    if (ticket.modelEdge) "AI chance (edge)" else "AI chance",
                    String.format(Locale.US, "%.0f%%", it * 100.0)
                )
            }
            ticket.fairChance?.let { fair ->
                if (ticket.modelChance == null || kotlin.math.abs(fair - ticket.modelChance) > 0.005) {
                    TicketMetricRow("Fair value", String.format(Locale.US, "%.0f%%", fair * 100.0))
                }
            }
            if (ticket.kind == TicketKind.HUNTER_VALUE) {
                TicketMetricRow(
                    if (ticket.modelEdge) "Edge vs market" else "Edge vs market",
                    if (ticket.modelEdge) "YES — model beats implied after fees + margin"
                    else "No — model does not clear fees + margin"
                )
            }
            ticket.winTargetNote?.let {
                val label = when {
                    ticket.paperOnly -> if (ticket.winTargetCapped) "Paper size (capped)" else "Paper size"
                    else -> "Live size"
                }
                TicketMetricRow(label, it)
            }
            ticket.netEvUsd?.let {
                TicketMetricRow("Net EV", String.format(Locale.US, "%+.2f  (%+.1f pp)", it, ticket.netEdgePp ?: 0.0))
            }
            Text(
                ticket.sizingNote,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
            ticket.gateNote?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.accentBlue)
            }
            ticket.closeNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onPaper(ticket.id) },
                    enabled = ticket.canPaper,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SideColor.ofTicketSide(ticket.side, colors)
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        if (ticket.isSell) "PAPER sell"
                        else String.format(Locale.US, "PAPER $%.2f", ticket.stakeUsd)
                    )
                }
                Button(
                    onClick = { onReview(ticket.id) },
                    enabled = credentialsConfigured && ticket.canApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SideColor.ofTicketSide(ticket.side, colors),
                        contentColor = SideColor.onTicketSide(ticket.side, colors)
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        when {
                            !ticket.canApprove -> ticket.blockedReason ?: "NO BET"
                            else -> {
                                val mode = TradeModeLabel.forApprove(
                                    paperTradingEnabled = paperTradingEnabled,
                                    liveCredentialsConfigured = credentialsConfigured,
                                    paperOnly = ticket.paperOnly,
                                    isSell = ticket.isSell,
                                    canApprove = true,
                                    blockedReason = ticket.blockedReason
                                )
                                HomeCopy.confirmApproveLabel(mode, ticket.stakeUsd, ticket.isSell)
                            }
                        }
                    )
                }
            }
            OutlinedButton(
                onClick = { onDismiss(ticket.id) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(44.dp)
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun WorkingOrderCard(order: PlacedOrder, onCancel: (String) -> Unit) {
    val colors = DipTheme.colors
    val id = order.orderId ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.accentBlue.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            "Working limit · ${com.dirk.kalshiodds.ui.SignalCopy.callLabel(order.ticket.side)} ${com.dirk.kalshiodds.ui.WindowLabel.of(order.ticket.ticker)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = colors.accentBlue
        )
        Text(
            String.format(
                Locale.US,
                "%d ct @ %s · filled %.0f · rest %.0f · id %s",
                order.ticket.contracts,
                KalshiQuoteDisplay.formatPriceCents(order.ticket.limitPrice),
                order.fillCount,
                order.remainingCount,
                id.take(8)
            ),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        OutlinedButton(
            onClick = { onCancel(id) },
            modifier = Modifier.padding(top = 6.dp).height(44.dp)
        ) { Text("Cancel order") }
    }
}

@Composable
private fun ApproveTicketDialog(
    ticket: TradeTicket,
    credentialsConfigured: Boolean,
    paperTradingEnabled: Boolean = false,
    onApprove: () -> Unit,
    onApproveSell: (Int, Double) -> Unit = { _, _ -> onApprove() },
    onPaper: () -> Unit,
    onPaperSell: (Int, Double) -> Unit = { _, _ -> onPaper() },
    onDismiss: () -> Unit
) {
    val colors = DipTheme.colors
    val held = (ticket.heldContracts ?: ticket.contracts).coerceAtLeast(1)
    val paperSell = ticket.paperOnly && ticket.isSell
    val paperBuy = false
    var countText by remember(ticket.id) { mutableStateOf(ticket.contracts.toString()) }
    var centsText by remember(ticket.id) {
        mutableStateOf(String.format(Locale.US, "%.1f", ticket.limitPrice * 100.0))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
                title = {
            Text(
                when {
                    paperSell -> "PAPER sell this position?"
                    ticket.isSell -> "REAL MONEY — live sell"
                    else -> "REAL MONEY"
                }
            )
        },
        text = {
            Column {
                Text(
                    when {
                        paperSell ->
                            "Simulated sell on the $100 paper book — never sent to Kalshi. " +
                                "Count is capped at the paper fill so this cannot flip."
                        paperBuy ->
                            "Simulated fill on the paper book at the current walked ask, including fees. " +
                                "No Kalshi key needed. This never places a live order."
                        ticket.isSell ->
                            "REAL MONEY. Places a reduce-only V2 GTC limit (POST /portfolio/events/orders) to sell the held side. " +
                                "Count is capped at your position so this cannot flip. Not a paper fill."
                        else ->
                            "REAL MONEY. Places a GTC limit via Kalshi V2 (POST /portfolio/events/orders) — not a market order, " +
                                "and not a paper fill. This tap is the only way a live order is sent. " +
                                "Dismiss / Back leaves no hanging order. Not financial advice. High variance."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (paperSell) FontWeight.Normal else FontWeight.Bold,
                    color = if (paperSell) colors.textPrimary else colors.accentOrange
                )
                Spacer(Modifier.height(8.dp))
                if (!paperSell && !paperBuy) {
                    TicketMetricRow("Contracts", ticket.contracts.toString())
                    TicketMetricRow("Price", KalshiQuoteDisplay.formatPriceCents(ticket.limitPrice))
                    TicketMetricRow(
                        "Fee",
                        String.format(Locale.US, "$%.2f", ticket.feeUsd ?: 0.0)
                    )
                    TicketMetricRow(
                        "Total cost",
                        String.format(Locale.US, "$%.2f", ticket.allInUsd ?: ticket.stakeUsd)
                    )
                    TicketMetricRow(
                        "Profit if win",
                        String.format(Locale.US, "$%.2f", ticket.profitIfWinUsd ?: ticket.potentialGainUsd)
                    )
                } else {
                    Text(
                        String.format(
                            Locale.US,
                            "%s %s\n$%.2f stake · %d contracts @ %s\nEst. fill $%.2f · max payout $%.0f · gain $%.0f",
                            ticket.displaySide,
                            ticket.ticker,
                            ticket.stakeUsd,
                            ticket.contracts,
                            KalshiQuoteDisplay.formatPriceCents(ticket.limitPrice),
                            ticket.estimatedFillUsd,
                            ticket.maxPayoutUsd,
                            ticket.potentialGainUsd
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ticket.closeNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentOrange,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (ticket.isSell) {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("Contracts (max $held)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = centsText,
                        onValueChange = { centsText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                        label = { Text("Limit ¢ (best bid)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                if (ticket.kind == TicketKind.HUNTER || ticket.kind == TicketKind.HUNTER_VALUE) {
                    Text(
                        ticket.gateNote ?: "Hunter path · Approve still required — never auto-placed.",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentOrange,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (ticket.isSell) {
                        val qty = countText.toIntOrNull()?.coerceIn(1, held) ?: ticket.contracts
                        val px = (centsText.toDoubleOrNull()?.div(100.0)) ?: ticket.limitPrice
                        if (paperSell) onPaperSell(qty, px) else onApproveSell(qty, px)
                    } else if (paperBuy) {
                        onPaper()
                    } else {
                        onApprove()
                    }
                },
                enabled = when {
                    paperSell || paperBuy -> ticket.canPaper || ticket.contracts > 0 || ticket.blockedReason != null
                    else -> credentialsConfigured && ticket.canApprove
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SideColor.ofTicketSide(ticket.side, colors),
                    contentColor = SideColor.onTicketSide(ticket.side, colors)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                val mode = TradeModeLabel.forApprove(
                    paperTradingEnabled = paperTradingEnabled,
                    liveCredentialsConfigured = credentialsConfigured,
                    paperOnly = ticket.paperOnly,
                    isSell = ticket.isSell,
                    canApprove = ticket.canApprove,
                    blockedReason = ticket.blockedReason
                )
                Text(HomeCopy.confirmApproveLabel(mode, ticket.stakeUsd, ticket.isSell))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ticket.isSell) {
                    TextButton(
                        onClick = onPaper,
                        enabled = ticket.canPaper
                    ) { Text(String.format(Locale.US, "PAPER $%.2f", ticket.stakeUsd)) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun TicketMetricRow(label: String, value: String) {
    val colors = DipTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}
