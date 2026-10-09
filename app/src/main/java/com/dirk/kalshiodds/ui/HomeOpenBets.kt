package com.dirk.kalshiodds.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.RealMoneyPhrase
import com.dirk.kalshiodds.signal.trade.RestingOrder
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Home open-bets list (0.3.38): live positions, resting orders, open paper positions. */
object HomeOpenBets {
    enum class Kind { LIVE_POSITION, RESTING_ORDER, PAPER_POSITION }

    data class Row(
        val key: String,
        val kind: Kind,
        val ticker: String,
        val side: String,
        val title: String,
        val detail: String,
        val action: String,
        val orderId: String? = null,
        val realMoney: Boolean
    )

    const val STOP_LABEL = "STOP — Autopilot off + cancel resting orders"
    const val STOP_BLURB = "One tap, no confirmation. Every other real-money action still needs typed REAL MONEY."

    fun rows(positions: List<LivePosition>, resting: List<RestingOrder>, paperFills: List<PaperFill>): List<Row> {
        val out = ArrayList<Row>()
        resting.filter { it.remaining > 1e-9 }.forEach { o ->
            out += Row(
                key = "order:${o.orderId}",
                kind = Kind.RESTING_ORDER,
                ticker = o.ticker,
                side = o.side,
                title = "${o.ticker} · ${o.side} resting",
                detail = String.format(Locale.US, "%.0f left @ %s · %s", o.remaining, cents(o.price), o.status),
                action = "Cancel",
                orderId = o.orderId,
                realMoney = true
            )
        }
        positions.filter { it.contracts > 1e-9 }.forEach { p ->
            out += Row(
                key = "pos:${p.ticker}:${p.side}",
                kind = Kind.LIVE_POSITION,
                ticker = p.ticker,
                side = p.side,
                title = "${p.ticker} · ${p.displaySide}",
                detail = String.format(
                    Locale.US,
                    "%.0f contracts · avg %s · bid %s%s",
                    p.contracts,
                    cents(p.avgCost),
                    cents(p.bestBid),
                    p.unrealizedPnlUsd?.let { String.format(Locale.US, " · %+.2f USD", it) }.orEmpty()
                ),
                action = "Close",
                realMoney = true
            )
        }
        paperFills.filter { !it.settled && it.contracts > 0 }
            .groupBy { it.ticker.uppercase() to it.side.uppercase() }
            .forEach { (k, fills) ->
                val n = fills.sumOf { it.contracts }
                val avg = fills.sumOf { it.contracts * it.limitPrice } / n
                out += Row(
                    key = "paper:${k.first}:${k.second}",
                    kind = Kind.PAPER_POSITION,
                    ticker = k.first,
                    side = k.second,
                    title = "PAPER ${k.first} · ${k.second}",
                    detail = "$n contracts · avg ${cents(avg)}",
                    action = "Close",
                    realMoney = false
                )
            }
        return out
    }

    private fun cents(p: Double?): String = p?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—"
}

@Composable
fun HomeStopButton(onStop: () -> Unit) {
    val colors = DipTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accentRed, contentColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Text(HomeOpenBets.STOP_LABEL, fontWeight = FontWeight.Bold)
        }
        Text(HomeOpenBets.STOP_BLURB, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
    }
}

@Composable
fun HomeOpenBetsSection(
    rows: List<HomeOpenBets.Row>,
    onClose: (ticker: String, side: String) -> Unit,
    onCancel: (orderId: String, ticker: String, typed: String) -> Unit
) {
    val colors = DipTheme.colors
    var pending by remember { mutableStateOf<HomeOpenBets.Row?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Open bets", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        if (rows.isEmpty()) {
            Text("No open bets or resting orders.", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(row.detail, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
                OutlinedButton(onClick = {
                    when (row.kind) {
                        HomeOpenBets.Kind.RESTING_ORDER -> pending = row
                        // Close opens the existing sell ticket: Approve + REAL MONEY for live, paper sell otherwise.
                        else -> onClose(row.ticker, row.side)
                    }
                }) { Text(row.action) }
            }
        }
    }
    pending?.let { row ->
        var typed by remember(row.key) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("REAL MONEY — cancel resting order") },
            text = {
                Column {
                    Text("${row.title}\n${row.detail}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Type ${RealMoneyPhrase.PHRASE}") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = RealMoneyPhrase.matches(typed),
                    onClick = {
                        onCancel(row.orderId.orEmpty(), row.ticker, typed)
                        pending = null
                    }
                ) { Text("Cancel order") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep") } }
        )
    }
}
