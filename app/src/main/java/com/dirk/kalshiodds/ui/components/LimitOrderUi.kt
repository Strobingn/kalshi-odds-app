package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.limit.LimitHost
import com.dirk.kalshiodds.signal.limit.LimitOrder
import com.dirk.kalshiodds.signal.limit.PaperLimitBook
import com.dirk.kalshiodds.signal.limit.PaperLimitOrder
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.WindowLabel
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.floor
import kotlinx.coroutines.delay

/** Pure copy for the limit editor, unit tested. */
object LimitCopy {
    fun quoteLine(q: LimitOrder.Quote): String {
        fun part(name: String, p: Double?, qty: Double?): String =
            if (p == null) "$name –" else "$name ${LimitOrder.cents(p)}" + (qty?.let { String.format(Locale.US, " (%,.0f)", it) }.orEmpty())
        return part("Best bid", q.bid, q.bidQty) + " · " + part("ask", q.ask, q.askQty)
    }

    fun queueLine(isSell: Boolean, ahead: Double?): String = when {
        ahead == null -> "Queue at this price is not shown (turn Live signals on to see the book)"
        ahead < 0.5 -> if (isSell) "Nothing offered at this price yet: yours would be first" else "Nothing bid at this price yet: yours would be first"
        else -> String.format(Locale.US, "%,.0f contracts are ahead of yours at this price", ahead)
    }

    fun summary(isSell: Boolean, side: String, price: Double, contracts: Int): String {
        val usd = contracts * price
        return if (isSell) {
            String.format(Locale.US, "If filled: you receive $%.2f · no fee", usd)
        } else {
            String.format(
                Locale.US, "If filled: costs $%.2f · pays $%d.00 if %s wins (+$%.2f) · no fee",
                usd, contracts, PaperLimitBook.label(side), contracts - usd
            )
        }
    }

    fun confirmLabel(paper: Boolean, isSell: Boolean, price: Double, contracts: Int): String =
        "${if (paper) "PAPER" else "REAL MONEY"} · ${if (isSell) "offer" else "bid"} $contracts @ ${LimitOrder.cents(price)}"

    /** Contracts the editor starts with. */
    fun startContracts(ticket: TradeTicket, price: Double, paper: Boolean): Int {
        if (ticket.isSell) return (ticket.heldContracts ?: ticket.contracts).coerceAtLeast(1)
        val byStake = floor(ticket.stakeUsd.coerceAtLeast(price) / price + 1e-9).toInt().coerceAtLeast(1)
        return if (paper) byStake else minOf(byStake, LimitOrder.maxLiveContracts(price)).coerceAtLeast(1)
    }

    fun paperOrderLine(o: PaperLimitOrder, nowMs: Long): String {
        val left = ((o.expiresAtMs - nowMs) / 1000L).coerceAtLeast(0L)
        val ahead = if (o.queueKnown || o.stillAhead < o.queueAhead) String.format(Locale.US, "%,.0f ahead", o.stillAhead) else String.format(Locale.US, "about %,.0f ahead", o.stillAhead)
        return "$ahead · cancels in ${if (left >= 120) "${left / 60} min" else "$left s"}"
    }
}

/**
 * The limit-order editor: pick a price, a size and how long the order may
 * rest. Shown in place of the buy / sell confirm sheet. The two confirm
 * buttons are the only things here that place an order, and the real one is
 * labelled REAL MONEY.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LimitOrderDialog(
    ticket: TradeTicket,
    host: LimitHost,
    credentialsConfigured: Boolean,
    paperTradingEnabled: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = DipTheme.colors
    val isSell = ticket.isSell
    val paperOnly = ticket.paperOnly || paperTradingEnabled
    val quote by produceState(host.quote(ticket), ticket.id) {
        while (true) {
            delay(500L)
            value = host.quote(ticket)
        }
    }
    var price by remember(ticket.id) { mutableStateOf(LimitOrder.defaultPrice(isSell, quote)) }
    if (price == null) price = LimitOrder.defaultPrice(isSell, quote)
    var cancelAfter by remember(ticket.id) { mutableLongStateOf(LimitOrder.DEFAULT_CANCEL_MS) }
    val p = price
    var contracts by remember(ticket.id, p == null) {
        mutableIntStateOf(if (p == null) 1 else LimitCopy.startContracts(ticket, p, paperOnly))
    }
    val held = (ticket.heldContracts ?: ticket.contracts).coerceAtLeast(1)
    val maxContracts = when {
        isSell -> held
        p == null -> 1
        paperOnly -> 100_000
        else -> LimitOrder.maxLiveContracts(p).coerceAtLeast(1)
    }
    if (contracts > maxContracts) contracts = maxContracts
    val liveError = p?.let { host.check(ticket, it, contracts, cancelAfter, paper = false) }
    val paperError = p?.let { host.check(ticket, it, contracts, cancelAfter, paper = true) }
    val ahead = p?.let { host.queueAhead(ticket, it) }
    val sideColor = SideColor.ofTicketSide(ticket.side, colors)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                (if (isSell) "Limit sell · " else "Limit order · ") +
                    "${PaperLimitBook.label(ticket.side)} · ${WindowLabel.of(ticket.ticker)}"
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("LIMIT_EDITOR"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LimitCopy.quoteLine(quote), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                if (p == null) {
                    Text(LimitOrder.NO_QUOTE, style = MaterialTheme.typography.bodyMedium, color = colors.accentRed)
                } else {
                    Stepper(
                        label = if (isSell) "Your offer" else "Your bid",
                        value = LimitOrder.cents(p),
                        onDown = { price = LimitOrder.step(p, up = false) },
                        onUp = { price = LimitOrder.step(p, up = true) },
                        downEnabled = p > LimitOrder.MIN_PRICE + 1e-9,
                        upEnabled = p < LimitOrder.MAX_PRICE - 1e-9
                    )
                    Text(LimitCopy.queueLine(isSell, ahead), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Stepper(
                        label = if (isSell) "Contracts (you hold $held)" else if (paperOnly) "Contracts" else "Contracts (most under the \$5 cap: $maxContracts)",
                        value = contracts.toString(),
                        onDown = { contracts = (contracts - 1).coerceAtLeast(1) },
                        onUp = { contracts = (contracts + 1).coerceAtMost(maxContracts) },
                        downEnabled = contracts > 1,
                        upEnabled = contracts < maxContracts
                    )
                    Text("Cancel it if not filled after", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LimitOrder.CANCEL_CHOICES_MS.forEach { ms ->
                            FilterChip(
                                selected = cancelAfter == ms,
                                onClick = { cancelAfter = ms },
                                label = { Text(if (ms == LimitOrder.UNTIL_CLOSE) "end of window" else LimitOrder.cancelLabel(ms)) }
                            )
                        }
                    }
                    Text(
                        LimitCopy.summary(isSell, ticket.side, p, contracts),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    (if (paperOnly) paperError else liveError)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.accentRed, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (isSell) LimitOrder.SELL_NOTE else LimitOrder.BUY_NOTE,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    if (!host.tradeFeedOn) {
                        Text(
                            "Paper limit orders fill from the live trade feed. Live signals is off, so a paper order would not fill.",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accentOrange
                        )
                    }
                    if (!paperOnly && ticket.belowMinProfit) {
                        Text(
                            String.format(
                                Locale.US,
                                "This wins $%.2f, under your $%.0f min-profit setting. That setting switches off the buy-now ticket; a limit order at your own price is not held to it.",
                                contracts - contracts * p, ticket.minProfitIfWinUsd ?: 0.0
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accentOrange
                        )
                    }
                    if (!paperOnly) {
                        Text(
                            if (isSell) {
                                "REAL MONEY sends a real post-only sell to Kalshi. If you close this position another way first, cancel this order."
                            } else {
                                "REAL MONEY sends a real post-only order to Kalshi. It counts toward today's cap until it is cancelled."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accentOrange,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (p != null) {
                if (paperOnly) {
                    Button(
                        onClick = { host.place(ticket.id, p, contracts, cancelAfter, true) },
                        enabled = paperError == null,
                        colors = ButtonDefaults.buttonColors(containerColor = sideColor, contentColor = SideColor.onTicketSide(ticket.side, colors)),
                        modifier = Modifier.height(48.dp)
                    ) { Text(LimitCopy.confirmLabel(true, isSell, p, contracts)) }
                } else {
                    Button(
                        onClick = { host.place(ticket.id, p, contracts, cancelAfter, false) },
                        enabled = credentialsConfigured && liveError == null,
                        colors = ButtonDefaults.buttonColors(containerColor = sideColor, contentColor = SideColor.onTicketSide(ticket.side, colors)),
                        modifier = Modifier.height(48.dp)
                    ) { Text(LimitCopy.confirmLabel(false, isSell, p, contracts)) }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p != null && !paperOnly && !isSell) {
                    TextButton(
                        onClick = { host.place(ticket.id, p, contracts, cancelAfter, true) },
                        enabled = paperError == null
                    ) { Text("PAPER") }
                }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    )
}

@Composable
private fun Stepper(label: String, value: String, onDown: () -> Unit, onUp: () -> Unit, downEnabled: Boolean, upEnabled: Boolean) {
    val colors = DipTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDown, enabled = downEnabled, modifier = Modifier.width(64.dp).height(48.dp)) { Text("−") }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.textPrimary, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onUp, enabled = upEnabled, modifier = Modifier.width(64.dp).height(48.dp)) { Text("+") }
        }
    }
}

/**
 * Home card: every limit order still resting, real and paper, each with its
 * Cancel. Hidden when nothing is resting.
 */
@Composable
fun WorkingLimitsCard(
    live: List<PlacedOrder>,
    paper: List<PaperLimitOrder>,
    nowMs: Long,
    onCancelLive: (String) -> Unit,
    onCancelPaper: (String) -> Unit
) {
    val colors = DipTheme.colors
    // An order past its end time is gone on Kalshi's side even if the app never heard back.
    val resting = live.filter {
        it.isResting && it.error?.startsWith("cancelled") != true && it.orderId != null &&
            (it.ticket.expiresAtMs ?: Long.MAX_VALUE) + 5_000L > nowMs
    }
    if (resting.isEmpty() && paper.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.accentBlue, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("WORKING_LIMITS"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Limit orders resting", style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        resting.forEach { o ->
            val left = o.ticket.expiresAtMs?.let { ((it - nowMs) / 1000L).coerceAtLeast(0L) }
            RestingRow(
                title = "REAL · ${PaperLimitBook.describe(o.ticket)} · ${WindowLabel.of(o.ticket.ticker)}",
                detail = String.format(Locale.US, "filled %.0f of %d when sent", o.fillCount, o.ticket.contracts) +
                    (left?.let { " · Kalshi cancels it in ${if (it >= 120) "${it / 60} min" else "$it s"}" }.orEmpty()),
                onCancel = { onCancelLive(o.orderId!!) }
            )
        }
        paper.forEach { o ->
            RestingRow(
                title = "PAPER · ${PaperLimitBook.describe(o.ticket)} · ${WindowLabel.of(o.ticket.ticker)}",
                detail = LimitCopy.paperOrderLine(o, nowMs),
                onCancel = { onCancelPaper(o.id) }
            )
        }
        Text(
            "A real order that fills shows up under Positions. Paper orders fill only from real trades at their price.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
    }
}

@Composable
private fun RestingRow(title: String, detail: String, onCancel: () -> Unit) {
    val colors = DipTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.height(44.dp)) { Text("Cancel") }
        Spacer(Modifier.width(0.dp))
    }
}
