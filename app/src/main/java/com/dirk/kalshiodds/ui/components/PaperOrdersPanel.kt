package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.paper.PaperOrder
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** 0.3.43 copy for paper limit orders (pure, tested). */
object PaperOrderCopy {
    fun line(o: PaperOrder): String = String.format(
        Locale.US, "%s %s %s · %d/%d @ %.0f¢ · %s%s",
        o.action, o.displaySide, o.ticker, o.filledQty, o.quantity, o.limitPrice * 100, o.status,
        if (o.feesUsd > 0) String.format(Locale.US, " · fees $%.2f", o.feesUsd) else ""
    ) + if (o.source != PaperOrder.SOURCE_MANUAL) " · ${o.source}" else ""

    const val FOOTNOTE = PaperOrder.TOUCH_FILL_NOTE + " Immediate fills pay the taker fee; resting fills pay the series maker fee ($0 for these crypto series). Unfilled orders auto-cancel at window close. Paper only — never sent to Kalshi."
}

/**
 * Manual PAPER ticket (Market vs Limit, Buy/Sell, UP/DOWN, any listed 15m or daily market) + open paper orders with
 * edit price/qty and cancel. Never places a real order.
 */
@Composable
fun PaperOrdersPanel(
    tickers: List<String>,
    orders: List<PaperOrder>,
    onSubmit: (ticker: String, side: String, action: String, limitCents: Double?, qty: Int, market: Boolean) -> Unit,
    onEdit: (id: String, limitCents: Double?, qty: Int?) -> Unit,
    onCancel: (id: String) -> Unit,
    showTicket: Boolean = true
) {
    val colors = DipTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Paper orders (PAPER)", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            if (showTicket && tickers.isNotEmpty()) {
                var ticker by rememberSaveable { mutableStateOf(tickers.first()) }
                if (ticker !in tickers) ticker = tickers.first()
                var side by rememberSaveable { mutableStateOf("YES") }
                var action by rememberSaveable { mutableStateOf("BUY") }
                var market by rememberSaveable { mutableStateOf(false) }
                var price by rememberSaveable { mutableStateOf("") }
                var qty by rememberSaveable { mutableStateOf("10") }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tickers.take(6).forEach { t ->
                        FilterChip(selected = t == ticker, onClick = { ticker = t }, label = { Text(t.substringBefore('-').removePrefix("KX"), style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Text(ticker, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = side == "YES", onClick = { side = "YES" }, label = { Text("UP") })
                    FilterChip(selected = side == "NO", onClick = { side = "NO" }, label = { Text("DOWN") })
                    FilterChip(selected = action == "BUY", onClick = { action = "BUY" }, label = { Text("Buy") })
                    FilterChip(selected = action == "SELL", onClick = { action = "SELL" }, label = { Text("Sell") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = market, onClick = { market = true }, label = { Text("Market") })
                    FilterChip(selected = !market, onClick = { market = false }, label = { Text("Limit") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!market) {
                        OutlinedTextField(
                            value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Limit ¢") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                    OutlinedTextField(
                        value = qty, onValueChange = { qty = it.filter(Char::isDigit) },
                        label = { Text("Contracts") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                OutlinedButton(
                    onClick = { onSubmit(ticker, side, action, price.toDoubleOrNull(), qty.toIntOrNull() ?: 0, market) },
                    enabled = (qty.toIntOrNull() ?: 0) > 0 && (market || (price.toDoubleOrNull() ?: 0.0) in 1.0..99.0),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) { Text("Place PAPER ${if (market) "market" else "limit"} ${action.lowercase()}") }
            }
            val open = orders.filter { it.isOpen }
            Text(if (open.isEmpty()) "No open paper orders." else "Open paper orders (${open.size})", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
            open.forEach { o -> OpenPaperOrderRow(o, onEdit, onCancel) }
            orders.filter { !it.isOpen }.take(5).forEach {
                Text(PaperOrderCopy.line(it), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
            Text(PaperOrderCopy.FOOTNOTE, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun OpenPaperOrderRow(o: PaperOrder, onEdit: (String, Double?, Int?) -> Unit, onCancel: (String) -> Unit) {
    val colors = DipTheme.colors
    var editing by rememberSaveable(o.id) { mutableStateOf(false) }
    var price by rememberSaveable(o.id) { mutableStateOf(String.format(Locale.US, "%.0f", o.limitPrice * 100)) }
    var qty by rememberSaveable(o.id) { mutableStateOf(o.quantity.toString()) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(PaperOrderCopy.line(o), style = MaterialTheme.typography.bodySmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Close" else "Edit") }
            TextButton(onClick = { onCancel(o.id) }) { Text("Cancel") }
        }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Limit ¢") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = qty, onValueChange = { qty = it.filter(Char::isDigit) }, label = { Text("Qty") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { onEdit(o.id, price.toDoubleOrNull(), qty.toIntOrNull()); editing = false }) { Text("Save") }
            }
        }
    }
}
