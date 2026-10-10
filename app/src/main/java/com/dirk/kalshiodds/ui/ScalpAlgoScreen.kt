package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.components.FieldCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 0.3.52 copy for one algorithm's page (pure, tested). */
object ScalpAlgoCopy {
    private val TIME = DateTimeFormatter.ofPattern("MMM d h:mm:ss a", Locale.US).withZone(ZoneId.of("America/New_York"))
    fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms))
    fun state(t: ScalpTrade): String = when (t.state) {
        ScalpState.PENDING_ENTRY -> "Entry pending"
        ScalpState.OPEN -> "Holding"
        ScalpState.PENDING_EXIT -> "Exiting"
        ScalpState.CLOSED -> if ((t.netUsd ?: 0.0) > 0) "Win" else if ((t.netUsd ?: 0.0) < 0) "Loss" else "Flat"
        ScalpState.NO_FILL -> "No fill"
    }
    fun exitPrice(t: ScalpTrade): Double? = if (t.soldContracts > 0) t.proceedsUsd / t.soldContracts else null
    fun priceLine(t: ScalpTrade): String {
        val side = if (t.side.equals("NO", true)) "DOWN" else "UP"
        val entry = t.entryPrice ?: t.signalAsk
        return "$side · ${t.contracts.takeIf { it > 0 } ?: com.dirk.kalshiodds.decision.ScalpRule.CONTRACTS} ct · entry ${ScalpTabCopy.cents(entry)} → exit ${ScalpTabCopy.cents(exitPrice(t))}"
    }
    fun moneyLine(t: ScalpTrade): String =
        "fees " + String.format(Locale.US, "$%.2f", t.entryFeeUsd + t.exitFeeUsd) + " · P&L " + (t.netUsd?.let { ScalpTabCopy.money(it) } ?: "open")
}

@Composable
fun ScalpAlgoScreen(viewModel: OddsViewModel, onBack: () -> Unit) {
    val d by viewModel.scalpAlgo.collectAsStateWithLifecycle()
    ScalpAlgoContent(d, onBack)
}

@Composable
fun ScalpAlgoContent(d: ScalpAlgoDetail, onBack: () -> Unit = {}) {
    val c = DipTheme.colors
    val m = d.model
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "hdr") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.textPrimary) }
                Text(m?.label ?: "Algorithm", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.textPrimary, maxLines = 1)
            }
            if (m != null) Text(m.blurb + " · paper only", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        if (m != null) {
            item(key = "card") { AlgoCard(m, d.allTime, d.today, d.byCoin) {} }
            item(key = "today") {
                FieldCard {
                    Text("Today (ET)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    val t = d.today
                    StatPair("P&L", t?.takeIf { it.roundTrips > 0 }?.let { ScalpTabCopy.money(it.netUsd) } ?: "—", "Round trips", "${t?.roundTrips ?: 0}")
                    StatPair("Won", t?.let { ScalpTabCopy.money(it.grossWinUsd) } ?: "—", "Lost", t?.let { ScalpTabCopy.money(it.grossLossUsd) } ?: "—")
                    StatPair("Wins / losses", "${t?.wins ?: 0} / ${t?.losses ?: 0}", "Win rate", ScalpTabCopy.pct(t?.winRate))
                }
            }
            item(key = "coins") {
                FieldCard {
                    Text("Per coin (all-time)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    com.dirk.kalshiodds.decision.ScalpParams.COINS.forEach { coin ->
                        val r = d.byCoin[coin]
                        StatPair(coin, r?.takeIf { it.roundTrips > 0 }?.let { ScalpTabCopy.money(it.netUsd) } ?: "—",
                            "Win ${ScalpTabCopy.pct(r?.winRate)}", "${r?.wins ?: 0}W / ${r?.losses ?: 0}L")
                    }
                }
            }
            item(key = "equity") {
                FieldCard {
                    Text("Equity (net after fees)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    Spacer(Modifier.height(8.dp))
                    val pts = d.equity
                    if (pts.size < 2) {
                        Text("Not enough closed round trips yet", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    } else {
                        val line = if (pts.last() >= 0) c.up else c.down
                        val zero = c.border
                        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                            val lo = minOf(0.0, pts.min()); val hi = maxOf(0.0, pts.max())
                            val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                            fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()
                            drawLine(zero, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 2f)
                            val path = Path()
                            pts.forEachIndexed { i, v ->
                                val x = size.width * i / (pts.size - 1)
                                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                            }
                            drawPath(path, line, style = Stroke(width = 4f))
                        }
                        Text(String.format(Locale.US, "Low %s · high %s · now %s", ScalpTabCopy.money(pts.min()), ScalpTabCopy.money(pts.max()), ScalpTabCopy.money(pts.last())),
                            style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1)
                    }
                }
            }
            item(key = "trades-h") {
                Text("Trades (${d.trades.size}, newest first)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            }
            items(d.trades, key = { "t-" + it.id }) { t ->
                FieldCard(accentColor = when { (t.netUsd ?: 0.0) > 0 -> c.up; (t.netUsd ?: 0.0) < 0 -> c.down; else -> null }) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("${t.coin} · ${ScalpAlgoCopy.state(t)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1)
                        Text(ScalpAlgoCopy.time(t.entryAtMs ?: t.signalAtMs), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1)
                    }
                    Text(ScalpAlgoCopy.priceLine(t), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ScalpAlgoCopy.moneyLine(t), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = when { (t.netUsd ?: 0.0) > 0 -> c.up; (t.netUsd ?: 0.0) < 0 -> c.down; else -> c.textSecondary }, maxLines = 1)
                    t.exitReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
}
