package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.activity.compose.BackHandler
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
    listVisible: Boolean = true,
    showDialog: Boolean = true,
    onReview: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onApprove: (String) -> Unit,
    onApproveSell: (String, Int, Double) -> Unit = { id, _, _ -> onApprove(id) },
    onApproveLimit: (String, Int, Double, Boolean, String, Long) -> Unit = { id, _, _, _, _, _ -> onApprove(id) },
    onPaper: (String) -> Unit,
    onPaperSell: (String, Int, Double) -> Unit = { id, _, _ -> onPaper(id) },
    onCancelApprove: () -> Unit,
    onCancelOrder: (String) -> Unit
) {
    val colors = DipTheme.colors
    val proposals = tickets.proposals.sortedByDescending {
        if (it.kind == TicketKind.HUNTER || it.kind == TicketKind.HUNTER_VALUE) 1_000.0 + it.maxPayoutUsd else it.maxPayoutUsd
    }
    val working = tickets.working.takeLast(20)

    if (listVisible) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!homeMode) {
            Text(
                text = "Live Approve",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Tickets wait for your Approve. Nothing is sent until you confirm.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            if (!credentialsConfigured) {
                Text(
                    text = "Add your Kalshi key in Settings to place a live order.",
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
                    "Submitting limit on ${com.dirk.kalshiodds.ui.WindowLabel.of(phase.ticket.ticker)}…",
                    color = colors.accentBlue,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            is TicketPhase.Submitted -> {
                Text(
                    if (phase.order.ticket.isSell) {
                        phase.order.fillSummary()
                    } else {
                        "Limit resting · ${com.dirk.kalshiodds.ui.SignalCopy.callLabel(phase.order.ticket.side)} ${com.dirk.kalshiodds.ui.WindowLabel.of(phase.order.ticket.ticker)} · order ${phase.order.orderId ?: "pending id"}"
                    },
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
    if (showDialog && awaiting != null) {
        BackHandler(enabled = true) { onCancelApprove() }
        ApproveTicketDialog(
            ticket = awaiting.ticket,
            credentialsConfigured = credentialsConfigured,
            paperTradingEnabled = paperTradingEnabled,
            onApprove = { onApprove(awaiting.ticket.id) },
            onApproveSell = { count, price -> onApproveSell(awaiting.ticket.id, count, price) },
            onApproveLimit = { count, price, post, tif, ttl -> onApproveLimit(awaiting.ticket.id, count, price, post, tif, ttl) },
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
                        TicketKind.SELL -> if (ticket.paperOnly) "PAPER SELL" else "SELL"
                        TicketKind.SCALP -> "EXPERIMENTAL SCALP · PAPER"
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
            } else if (ticket.isSell) {
            Spacer(Modifier.height(8.dp))
            TicketMetricRow("Contracts", ticket.contracts.toString())
            TicketMetricRow("Bid", KalshiQuoteDisplay.formatPriceCents(ticket.limitPrice))
            TicketMetricRow(
                "Expected proceeds",
                String.format(Locale.US, "$%.2f", ticket.stakeUsd)
            )
            ticket.feeUsd?.let {
                TicketMetricRow("Fee", String.format(Locale.US, "$%.2f", it))
            }
            Text(
                ticket.sizingNote,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
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
                String.format(Locale.US, "$%.0f if %s wins", ticket.maxPayoutUsd, com.dirk.kalshiodds.ui.SignalCopy.callLabel(ticket.side))
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
    val id = order.orderId
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.accentBlue.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            "${order.status.uppercase()} · ${com.dirk.kalshiodds.ui.SignalCopy.callLabel(order.ticket.side)} ${com.dirk.kalshiodds.ui.WindowLabel.of(order.ticket.ticker)}",
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
                id?.take(8) ?: order.clientOrderId.take(8)
            ),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        order.error?.let { Text(it, color = colors.accentRed, style = MaterialTheme.typography.labelMedium) }
        order.actualFeesUsd?.let { Text("Exchange fees $${String.format(Locale.US, "%.4f", it)}", color = colors.textSecondary) }
        if (order.isResting && id != null) OutlinedButton(
            onClick = { onCancel(id) },
            modifier = Modifier.padding(top = 6.dp).height(44.dp)
        ) { Text("Cancel order") }
    }
}

@Composable
fun LiveSellConfirmSheet(
    ticket: TradeTicket,
    credentialsConfigured: Boolean = true,
    paperTradingEnabled: Boolean = false
) {
    ApproveTicketDialog(
        ticket = ticket,
        credentialsConfigured = credentialsConfigured,
        paperTradingEnabled = paperTradingEnabled,
        onApprove = {},
        onPaper = {},
        onDismiss = {}
    )
}

@Composable
internal fun ApproveTicketDialog(
    ticket: TradeTicket,
    credentialsConfigured: Boolean,
    paperTradingEnabled: Boolean = false,
    onApprove: () -> Unit,
    onApproveSell: (Int, Double) -> Unit = { _, _ -> onApprove() },
    onApproveLimit: (Int, Double, Boolean, String, Long) -> Unit = { _, _, _, _, _ -> onApprove() },
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
    var makerOnly by remember(ticket.id) { mutableStateOf(ticket.limitOptions.postOnly) }
    var tif by remember(ticket.id) { mutableStateOf(ticket.limitOptions.timeInForce) }
    var expiryText by remember(ticket.id) { mutableStateOf("0") }
    val qty = countText.toIntOrNull() ?: 0
    val px = (centsText.toDoubleOrNull() ?: Double.NaN) / 100.0
    val validInput = qty > 0 && px.isFinite() && px in .001.. .999 &&
        (!ticket.isSell || qty <= held) && (ticket.isSell || expiryText.toLongOrNull()?.let { it >= 0 } == true)
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
            Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        paperSell ->
                            "Simulated sell on the paper book — never sent to Kalshi."
                        paperBuy ->
                            "Simulated fill on the paper book. This never places a live order."
                        ticket.isSell && ticket.blockedReason != null ->
                            com.dirk.kalshiodds.ui.PositionCopy.sellBlockedMessage(ticket.blockedReason)
                        ticket.isSell ->
                            "Reduce-only IOC sell at your minimum price. Unfilled contracts are canceled."
                        else ->
                            "Confirm sends your limit order. Canceling the sheet sends nothing."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (paperSell) FontWeight.Normal else FontWeight.Bold,
                    color = if (ticket.isSell && ticket.blockedReason != null) {
                        colors.accentRed
                    } else if (paperSell) {
                        colors.textPrimary
                    } else {
                        colors.accentOrange
                    }
                )
                Spacer(Modifier.height(8.dp))
                if (ticket.isSell && !paperSell && ticket.blockedReason == null) {
                    TicketMetricRow("Contracts", ticket.contracts.toString())
                    TicketMetricRow("Bid", KalshiQuoteDisplay.formatPriceCents(ticket.limitPrice))
                    TicketMetricRow(
                        "Fee",
                        String.format(Locale.US, "$%.2f", ticket.feeUsd ?: 0.0)
                    )
                    TicketMetricRow(
                        "Expected proceeds",
                        String.format(Locale.US, "$%.2f", ticket.stakeUsd)
                    )
                } else if (!paperSell && !paperBuy && ticket.blockedReason == null) {
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
                } else if (paperSell || paperBuy) {
                    Text(
                        String.format(
                            Locale.US,
                            "%s %s\n$%.2f stake · %d contracts @ %s\nEst. fill $%.2f · max payout $%.0f · gain $%.0f",
                            com.dirk.kalshiodds.ui.SignalCopy.callLabel(ticket.side),
                            com.dirk.kalshiodds.ui.WindowLabel.of(ticket.ticker),
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
                if (ticket.blockedReason == null) {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text(if (ticket.isSell) "Contracts (max $held)" else "Contracts") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    run {
                        OutlinedTextField(
                            value = centsText,
                            onValueChange = { centsText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                            label = { Text(if (ticket.isSell) "Minimum sell price ¢" else "Maximum buy price ¢") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
                if (!ticket.isSell && ticket.blockedReason == null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = makerOnly, onCheckedChange = {
                            makerOnly = it; if (it) tif = "good_till_canceled"
                        })
                        Text("Maker-only (may never fill)")
                    }
                    Row {
                        listOf("GTC" to "good_till_canceled", "IOC" to "immediate_or_cancel", "FOK" to "fill_or_kill").forEach { (label, value) ->
                            TextButton(onClick = { tif = value; if (value != "good_till_canceled") { makerOnly = false; expiryText = "0" } }) {
                                Text(if (tif == value) "✓ $label" else label)
                            }
                        }
                    }
                    if (tif == "good_till_canceled") OutlinedTextField(
                        value = expiryText, onValueChange = { expiryText = it.filter(Char::isDigit).take(7) },
                        label = { Text("Expire in seconds (0 = until canceled)") })
                    val debit = if (validInput) com.dirk.kalshiodds.signal.trade.LiveOrderSizer.allInUsd(qty, px) else 0.0
                    Text(String.format(Locale.US, "Edited maximum debit: $%.2f · $5 cap; fees reserved conservatively", debit))
                }
                if (ticket.kind == TicketKind.HUNTER || ticket.kind == TicketKind.HUNTER_VALUE || ticket.kind == TicketKind.SCALP) {
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
                        val px = centsText.toDoubleOrNull()?.div(100.0) ?: ticket.limitPrice
                        if (paperSell) onPaperSell(qty, px) else onApproveSell(qty, px)
                    } else if (paperBuy) {
                        onPaper()
                    } else {
                        onApproveLimit(qty, px, makerOnly, tif, if (tif == "good_till_canceled") expiryText.toLongOrNull() ?: 0L else 0L)
                    }
                },
                enabled = when {
                    paperSell || paperBuy -> ticket.canPaper || ticket.contracts > 0 || ticket.blockedReason != null
                    else -> credentialsConfigured && ticket.canApprove && validInput
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
                Text(if (!ticket.isSell) "Confirm limit" else HomeCopy.confirmApproveLabel(mode, ticket.stakeUsd, ticket.isSell))
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
