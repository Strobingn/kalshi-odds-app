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
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.AccentRed
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun TradeTicketsSection(
    tickets: TicketUiState,
    credentialsConfigured: Boolean,
    onReview: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onApprove: (String) -> Unit,
    onApproveSell: (String, Int, Double) -> Unit = { id, _, _ -> onApprove(id) },
    onPaper: (String) -> Unit,
    onCancelApprove: () -> Unit,
    onCancelOrder: (String) -> Unit
) {
    val proposals = tickets.proposals.sortedByDescending {
        if (it.kind == TicketKind.HUNTER) 1_000.0 + it.maxPayoutUsd else it.maxPayoutUsd
    }
    val working = tickets.working.filter { it.orderId != null && it.error?.startsWith("cancelled") != true }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Live Approve",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Live Approve sends a Kalshi V2 GTC limit after you confirm — never the retired v1 /portfolio/orders path. Paper \$5 (above, or on the card) is a simulated fill on the \$100 paper book and never hits Kalshi. Hunter cards still appear when a \$1 stake can settle ≥\$25. Cancel leaves no live order.",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        if (!credentialsConfigured) {
            Text(
                text = "Add Kalshi API Key ID + PEM in Settings for Live Approve. Paper fills do not need keys. Keys stay on device and are never logged.",
                style = MaterialTheme.typography.labelMedium,
                color = AccentOrange
            )
        }
        val visibleError = tickets.lastError
            ?.takeUnless { com.dirk.kalshiodds.signal.trade.TicketSession.stalePageError(it) }
        visibleError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AccentRed)
        }
        when (val phase = tickets.phase) {
            is TicketPhase.Submitting -> {
                Text(
                    "Submitting limit on ${phase.ticket.ticker}…",
                    color = AccentBlue,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            is TicketPhase.Submitted -> {
                Text(
                    "Limit resting · ${phase.order.ticket.ticker} · order ${phase.order.orderId ?: "pending id"}",
                    color = AccentGreen,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            is TicketPhase.Failed -> {
                Text(phase.error, color = AccentRed, style = MaterialTheme.typography.bodyMedium)
            }
            else -> Unit
        }
        if (proposals.isEmpty() && working.isEmpty() && visibleError == null) {
            Text(
                "No pending tickets. Use Buy YES / Buy NO on the hero or a market card.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AccentBlue.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
        working.forEach { order ->
            WorkingOrderCard(order, onCancelOrder)
        }
        proposals.forEach { ticket ->
            ProposedTicketCard(ticket, credentialsConfigured, onReview, onDismiss, onPaper)
        }
    }

    val awaiting = tickets.phase as? TicketPhase.AwaitingApprove
    if (awaiting != null) {
        ApproveTicketDialog(
            ticket = awaiting.ticket,
            credentialsConfigured = credentialsConfigured,
            onApprove = { onApprove(awaiting.ticket.id) },
            onApproveSell = { count, price -> onApproveSell(awaiting.ticket.id, count, price) },
            onDismiss = onCancelApprove
        )
    }
}

@Composable
private fun ProposedTicketCard(
    ticket: TradeTicket,
    credentialsConfigured: Boolean,
    onReview: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onPaper: (String) -> Unit
) {
    val hunter = ticket.kind == TicketKind.HUNTER
    val border = if (hunter) AccentOrange else AccentBlue
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (hunter) 2.dp else 1.dp, border, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when (ticket.kind) {
                        TicketKind.HUNTER -> "PENDING APPROVAL · $1 → ≥$25"
                        TicketKind.MANUAL -> "MANUAL BUY"
                        TicketKind.CONFIGURED -> "TICKET"
                        TicketKind.SELL -> if (ticket.paperOnly) "PAPER SELL" else "SELL · REDUCE-ONLY"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hunter) AccentOrange else AccentBlue,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "%d ct", ticket.contracts),
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "${ticket.displaySide} · ${ticket.ticker}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            ticket.title?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
            if (ticket.blockedReason != null) {
                Text(
                    ticket.blockedReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentRed,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                ticket.gateNote?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            } else {
            Spacer(Modifier.height(8.dp))
            TicketMetricRow("Stake", String.format(Locale.US, "$%.2f", ticket.stakeUsd))
            TicketMetricRow("Ask / limit", String.format(Locale.US, "%.0f¢  (never market)", ticket.limitPrice * 100))
            TicketMetricRow("Est. fill", String.format(Locale.US, "$%.2f", ticket.estimatedFillUsd))
            TicketMetricRow(
                "Max payout",
                String.format(Locale.US, "$%.0f if %s wins", ticket.maxPayoutUsd, ticket.displaySide)
            )
            TicketMetricRow(
                "Potential gain",
                String.format(Locale.US, "$%.0f", ticket.potentialGainUsd)
            )
            ticket.netEvUsd?.let {
                TicketMetricRow("Net EV", String.format(Locale.US, "%+.2f  (%+.1f pp)", it, ticket.netEdgePp ?: 0.0))
            }
            Text(
                ticket.sizingNote,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
            ticket.gateNote?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = AccentBlue)
            }
            ticket.closeNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentOrange,
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
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Paper $5")
                }
                Button(
                    onClick = { onReview(ticket.id) },
                    enabled = credentialsConfigured && ticket.canApprove,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        when {
                            !ticket.canApprove -> ticket.blockedReason ?: "Unavailable"
                            credentialsConfigured -> "Live Approve…"
                            else -> "Needs API key"
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
    val id = order.orderId ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(AccentBlue.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            "Working limit · ${order.ticket.displaySide} ${order.ticket.ticker}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = AccentBlue
        )
        Text(
            String.format(
                Locale.US,
                "%d ct @ %.0f¢ · filled %.0f · rest %.0f · id %s",
                order.ticket.contracts,
                order.ticket.limitPrice * 100,
                order.fillCount,
                order.remainingCount,
                id.take(8)
            ),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
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
    onApprove: () -> Unit,
    onApproveSell: (Int, Double) -> Unit = { _, _ -> onApprove() },
    onDismiss: () -> Unit
) {
    val held = (ticket.heldContracts ?: ticket.contracts).coerceAtLeast(1)
    var countText by remember(ticket.id) { mutableStateOf(ticket.contracts.toString()) }
    var centsText by remember(ticket.id) {
        mutableStateOf(String.format(Locale.US, "%.1f", ticket.limitPrice * 100.0))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ticket.isSell) "Live Approve this sell?" else "Live Approve this ticket?") },
        text = {
            Column {
                Text(
                    if (ticket.isSell) {
                        "Places a real V2 reduce-only GTC limit (POST /portfolio/events/orders) to sell the held side. " +
                            "Count is capped at your position so this cannot flip. Not a paper fill."
                    } else {
                        "Places a real GTC limit via Kalshi V2 (POST /portfolio/events/orders) — not a market order, " +
                            "and not a paper fill. This tap is the only way a live order is sent. " +
                            "Dismiss / Back leaves no hanging order. Not financial advice. High variance."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(
                        Locale.US,
                        "%s %s\n$%.2f stake · %d contracts @ %.0f¢\nEst. fill $%.2f · max payout $%.0f · gain $%.0f",
                        ticket.displaySide,
                        ticket.ticker,
                        ticket.stakeUsd,
                        ticket.contracts,
                        ticket.limitPrice * 100,
                        ticket.estimatedFillUsd,
                        ticket.maxPayoutUsd,
                        ticket.potentialGainUsd
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ticket.closeNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentOrange,
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
                if (ticket.kind == TicketKind.HUNTER) {
                    Text(
                        "Hunter path: $1 stake, ≥$25 max payout.",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentOrange,
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
                        onApproveSell(qty, px)
                    } else {
                        onApprove()
                    }
                },
                enabled = credentialsConfigured && ticket.canApprove,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                modifier = Modifier.height(48.dp)
            ) { Text(if (ticket.isSell) "Live Approve sell" else "Live Approve") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TicketMetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}
