package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.d3.D3Quote
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.ui.components.FieldCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** 0.3.39 copy for the restored BTC/ETH/SOL views. Pure. */
object CoinViewCopy {
    fun dailyTitle(series: String): String = when {
        series.contains("ETH", true) -> "Ethereum daily 5 PM ET"
        series.contains("SOL", true) -> "Solana daily 5 PM ET"
        else -> "Bitcoin daily 5 PM ET"
    }

    fun cents(p: Double?): String = p?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—"

    fun dailyLine(q: D3Quote): String {
        val strike = q.strikeUsd?.let { String.format(Locale.US, "$%,.2f", it) } ?: (q.subtitle ?: q.ticker)
        return "$strike · UP ${cents(q.yesBid)}/${cents(q.yesAsk)} · DOWN ${cents(q.noBid)}/${cents(q.noAsk)}"
    }

    /**
     * Top [depth] levels per side, best first. YES bids and NO bids; a NO bid at p is a YES ask at 1−p.
     */
    fun bookLines(book: BookLevelSnapshot?, depth: Int = 5): Pair<List<String>, List<String>> {
        if (book == null || book.isEmpty()) return emptyList<String>() to emptyList()
        fun side(levels: List<Pair<Double, Double>>) = levels
            .filter { it.second > 0.0 }
            .sortedByDescending { it.first }
            .take(depth)
            .map { (p, size) -> String.format(Locale.US, "%s × %.0f", cents(p), size) }
        return side(book.yes) to side(book.no)
    }
}

@Composable
fun HomeCoinSelector(selected: HomeMarkets.Coin, onSelect: (HomeMarkets.Coin) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeMarkets.Coin.entries.forEach { coin ->
            FilterChip(
                selected = coin == selected,
                onClick = { onSelect(coin) },
                label = { Text(coin.label) }
            )
        }
    }
}

@Composable
fun HomeDailyCard(
    series: String,
    rows: List<D3Quote>,
    onBuy: (D3Quote, String) -> Unit
) {
    val colors = DipTheme.colors
    FieldCard {
        Text(CoinViewCopy.dailyTitle(series), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        if (rows.isEmpty()) {
            Text("Daily strikes loading…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        rows.forEach { q ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(CoinViewCopy.dailyLine(q), style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onBuy(q, "YES") }, enabled = q.yesAsk != null) {
                        Text("Buy UP ${CoinViewCopy.cents(q.yesAsk)}", color = colors.up)
                    }
                    OutlinedButton(onClick = { onBuy(q, "NO") }, enabled = q.noAsk != null) {
                        Text("Buy DOWN ${CoinViewCopy.cents(q.noAsk)}", color = colors.down)
                    }
                }
            }
        }
        Text(
            "Buy opens the same approve-gated ticket as the 15m cards. Real money needs Approve + typed REAL MONEY.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
    }
}

@Composable
fun OrderBookCard(book: BookLevelSnapshot?) {
    val colors = DipTheme.colors
    val (yes, no) = CoinViewCopy.bookLines(book)
    FieldCard {
        Text("Order book (live)", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        if (yes.isEmpty() && no.isEmpty()) {
            Text("No live book yet — needs Live signals / WS.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            return@FieldCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("UP bids", style = MaterialTheme.typography.labelMedium, color = colors.up)
                yes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary) }
            }
            Column(Modifier.weight(1f)) {
                Text("DOWN bids", style = MaterialTheme.typography.labelMedium, color = colors.down)
                no.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary) }
            }
        }
    }
}

