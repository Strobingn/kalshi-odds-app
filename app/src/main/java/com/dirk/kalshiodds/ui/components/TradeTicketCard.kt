package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.trade.PlacedOrder
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
    onCancelApprove: () -> Unit,
    onCancelOrder: (String) -> Unit
) {
    val proposals = tickets.proposals
    val working = tickets.working.filter { it.orderId != null && it.error?.startsWith("cancelled") != true }
    if (proposals.isEmpty() && working.isEmpty() && tickets.lastError == null &&
        tickets.phase !is TicketPhase.Failed && tickets.phase !is TicketPhase.Submitted
    ) {
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Trade tickets",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Limit orders only. A ticket appears when a \$${fmt(proposals.firstOrNull()?.stakeUsd ?: 5.0)} " +
                "stake can settle ≥\$100 (price ≤5¢ on \$1 binaries, enough size). " +
                "Nothing is sent until you tap Approve on that ticket. Not financial advice — high variance.",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        if (!credentialsConfigured) {
            Text(
                text = "Add Kalshi API Key ID + PEM in Settings to Approve. Keys stay in EncryptedSharedPreferences.",
                style = MaterialTheme.typography.labelMedium,
                color = AccentOrange
            )
        }
        tickets.lastError?.let {
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
        working.forEach { order ->
            WorkingOrderCard(order, onCancelOrder)
        }
        proposals.forEach { ticket ->
            ProposedTicketCard(ticket, credentialsConfigured, onReview, onDismiss)
        }
    }

    val awaiting = tickets.phase as? TicketPhase.AwaitingApprove
    if (awaiting != null) {
        ApproveTicketDialog(
            ticket = awaiting.ticket,
            credentialsConfigured = credentialsConfigured,
            onApprove = { onApprove(awaiting.ticket.id) },
            onDismiss = onCancelApprove
        )
    }
}

@Composable
private fun ProposedTicketCard(
    ticket: TradeTicket,
    credentialsConfigured: Boolean,
    onReview: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${ticket.displaySide} · ${ticket.ticker}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "%d ct", ticket.contracts),
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
            }
            ticket.title?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            TicketMetricRow("Limit", String.format(Locale.US, "%.0f¢  (never market)", ticket.limitPrice * 100))
            TicketMetricRow("Est. fill", String.format(Locale.US, "$%.2f", ticket.estimatedFillUsd))
            TicketMetricRow("Max payout", String.format(Locale.US, "$%.0f if %s wins", ticket.maxPayoutUsd, ticket.displaySide))
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
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onReview(ticket.id) },
                    enabled = credentialsConfigured,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (credentialsConfigured) "Approve…" else "Needs API key")
                }
                OutlinedButton(onClick = { onDismiss(ticket.id) }, modifier = Modifier.weight(1f)) {
                    Text("Dismiss")
                }
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
            modifier = Modifier.padding(top = 6.dp)
        ) { Text("Cancel order") }
    }
}

@Composable
private fun ApproveTicketDialog(
    ticket: TradeTicket,
    credentialsConfigured: Boolean,
    onApprove: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Approve this ticket?") },
        text = {
            Column {
                Text(
                    "Places a GTC limit — not a market order. This tap is the only way an order is sent. " +
                        "Not financial advice. High variance: you can lose the full stake.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(
                        Locale.US,
                        "%s %s\n%d contracts @ %.0f¢\nEst. fill $%.2f · max payout $%.0f",
                        ticket.displaySide,
                        ticket.ticker,
                        ticket.contracts,
                        ticket.limitPrice * 100,
                        ticket.estimatedFillUsd,
                        ticket.maxPayoutUsd
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                enabled = credentialsConfigured,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
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

private fun fmt(v: Double): String = String.format(Locale.US, "%.0f", v)
