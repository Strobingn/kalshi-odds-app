package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalp.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperScalpScreen(state: PaperScalpState, onBack: () -> Unit, onStart: () -> Unit, onPause: (Boolean) -> Unit) {
    val now = System.currentTimeMillis()
    Scaffold(topBar = { TopAppBar(title = { Text("Paper Scalp Lab") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("PAPER ONLY · ${state.source.uppercase(Locale.US)} BOOKS", fontWeight = FontWeight.Bold)
                Text("20 independent $20,000 experiments. Buys strictly below 50¢. Every position trails 2¢ from entry. AI chooses entries, profit exits and affordable size throughout the open contract.")
                Text(state.status, style = MaterialTheme.typography.labelLarge)
                if (state.lastQuoteMs > 0) Text("Last processed book: ${((now - state.lastQuoteMs).coerceAtLeast(0) / 1000)}s ago")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart) { Text("Start monitor") }
                    OutlinedButton(onClick = { onPause(!state.paused) }) { Text(if (state.paused) "Resume entries" else "Pause entries") }
                }
                Text(if (state.paused) "New entries paused. Existing stops and exits continue." else "Entries autonomous — no approval prompts or live $5 cap.")
            }
            item {
                Text("Execution and learning", fontWeight = FontWeight.Bold)
                Text("Orders wait for a newer book and at least 250ms. Fills walk visible depth and charge fees on both legs. Reused depth is not replenished without observed additions. Partial exits and missing bids remain open; only official settlement resolves a closed market.", style = MaterialTheme.typography.bodySmall)
                Text("The 250ms delay is a simulation assumption. Results are hypothetical. Four sizing copies in each family share signals; they are not four independent proofs of an edge. Paper results never train from unrealized marks.", style = MaterialTheme.typography.bodySmall)
            }
            items(state.accounts.sortedByDescending { it.realized }, key = { it.id }) { a ->
                val spec = PaperScalpCatalog.ALL.first { it.id == a.id }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(spec.name, fontWeight = FontWeight.Bold)
                        Text("Realized ${money(a.realized)} · equity ${money(a.equity())} · cash ${money(a.cash)}")
                        Text("${a.closed} completed · ${a.wins} wins / ${a.closed - a.wins} losses · fees ${money(a.fees)} · ${a.noFills} no-fills")
                        val windows = a.results.map { it.ticker }.distinct().size
                        Text("Recent sample: ${a.results.size} trades / $windows contract windows${if (windows < 10) " · limited evidence" else ""}", style = MaterialTheme.typography.labelMedium)
                        a.positions.forEach { p ->
                            Text("${p.side} ${p.remaining}/${p.count} ct @ ${cents(p.entry)} · peak ${cents(p.highBid)} · stop ${cents(p.stop)}")
                            Text(p.exitReason ?: "Stop armed immediately", style = MaterialTheme.typography.labelMedium)
                            if (now - p.markAtMs > 3000) Text("STALE MARK — waiting for a fresh book or official settlement", style = MaterialTheme.typography.labelMedium)
                        }
                        if (a.pending.isNotEmpty()) Text("${a.pending.size} entry decisions waiting for a later book")
                        Text(a.note, style = MaterialTheme.typography.bodySmall)
                        a.results.lastOrNull()?.let { last ->
                            val learned = PaperScalpLearning.lesson(a.results, last.setup, last.entry)
                            Text("Learned ${last.setup}, ${PaperScalpPolicy.bucket(last.entry)}¢: ${learned.label}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
private fun money(value: Double) = String.format(Locale.US, "$%+.2f", value)
private fun cents(value: Double) = String.format(Locale.US, "%.1f¢", value * 100)
