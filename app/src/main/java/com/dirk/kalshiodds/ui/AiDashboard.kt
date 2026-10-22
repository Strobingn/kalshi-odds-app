package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.signal.scalper.AiEvent
import com.dirk.kalshiodds.signal.scalper.PaperBet
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ProviderRow(val name: String, val ok: Int, val failed: Int, val avgMs: Long, val model: String, val lastError: String)

/** One arena strategy's closed results, for the winners / losers charts. */
data class StrategyResult(val name: String, val closed: Int, val wins: Int, val pnlUsd: Double)

/** Everything the AI tab's dashboard shows. Built by [AiDashboard.build]; the screen only draws it. */
data class AiDashboardState(
    val version: String = "",
    val keys: List<Pair<String, Boolean>> = listOf("Groq" to false, "Mistral" to false, "Gemini" to false),
    val scalpingOn: Boolean = true,
    val paperOn: Boolean = true,
    val learningOn: Boolean = true,
    val scalpingStatus: String = "",
    val paperStatus: String = "",
    val lastError: String = "",
    val calls24h: Int = 0,
    val callsOk24h: Int = 0,
    val avgMs: Long = 0,
    val hourLabels: List<String> = emptyList(),
    /** Per hour, oldest first: [scalping ok, paper ok, chat ok, failed]. */
    val callsByHour: List<List<Float>> = emptyList(),
    val providers: List<ProviderRow> = emptyList(),
    /** Per hour, oldest first: [applied, rejected]. */
    val changesByHour: List<List<Float>> = emptyList(),
    val applied24h: Int = 0,
    val better: Int = 0,
    val worse: Int = 0,
    val arenaNet: Double = 0.0,
    val arenaTrades: Int = 0,
    val arenaWinRate: Double = 0.0,
    val winners: List<SignedBar> = emptyList(),
    val losers: List<SignedBar> = emptyList(),
    val winRates: List<Triple<String, Double, String>> = emptyList(),
    val mostChanged: List<Triple<String, Double, String>> = emptyList(),
    val paperCurve: List<Double> = emptyList(),
    val paperWins: Int = 0,
    val paperLosses: Int = 0,
    val paperOpen: Int = 0,
    val paperNet: Double = 0.0,
    val recentChanges: List<String> = emptyList(),
    val recentBets: List<String> = emptyList()
)

object AiDashboard {
    private const val HOUR = 3_600_000L

    fun build(
        base: AiDashboardState,
        nowMs: Long,
        events: List<AiEvent>,
        strategies: List<StrategyResult>,
        bets: List<PaperBet>,
        zone: TimeZone = TimeZone.getDefault()
    ): AiDashboardState {
        val since = nowMs - 24 * HOUR
        val day = events.filter { it.atMs in since..nowMs }
        val calls = day.filter { it.kind == AiEvent.CALL }
        val bucketOf = { t: Long -> ((t - since) / HOUR).toInt().coerceIn(0, 23) }

        val callsByHour = List(24) { MutableList(4) { 0f } }
        for (e in calls) {
            val b = callsByHour[bucketOf(e.atMs)]
            val seg = when {
                !e.ok -> 3
                e.ai == AiEvent.SCALPING -> 0
                e.ai == AiEvent.PAPER -> 1
                else -> 2
            }
            b[seg] += 1f
        }
        val changesByHour = List(24) { MutableList(2) { 0f } }
        for (e in day) when (e.kind) {
            AiEvent.APPLIED -> changesByHour[bucketOf(e.atMs)][0] += 1f
            AiEvent.REJECTED -> changesByHour[bucketOf(e.atMs)][1] += 1f
        }
        val fmt = SimpleDateFormat("ha", Locale.US).apply { timeZone = zone }
        val labels = List(24) { i -> if (i % 6 == 0) fmt.format(Date(since + i * HOUR)).lowercase().dropLast(1) else "" }

        val providers = calls.groupBy { it.provider }.filterKeys { it.isNotBlank() }.map { (name, list) ->
            val ok = list.filter { it.ok }
            ProviderRow(
                name = name,
                ok = ok.size,
                failed = list.size - ok.size,
                avgMs = if (ok.isEmpty()) 0 else ok.sumOf { it.ms } / ok.size,
                model = list.lastOrNull { it.ok }?.detail?.substringBefore(" | ").orEmpty(),
                lastError = list.lastOrNull { !it.ok }?.detail?.substringAfter(" | ", "").orEmpty()
            )
        }.sortedByDescending { it.ok + it.failed }

        val okCalls = calls.filter { it.ok }
        val all = events
        val better = all.count { it.kind == AiEvent.SCORED_BETTER }
        val worse = all.count { it.kind == AiEvent.SCORED_WORSE }
        val lastFailure = calls.lastOrNull()?.takeIf { !it.ok }?.let { "${it.provider}: ${it.detail.substringAfter(" | ", it.detail)}" }

        val ran = strategies.filter { it.closed > 0 }
        val byPnl = ran.sortedByDescending { it.pnlUsd }
        fun bar(s: StrategyResult) = SignedBar(
            pretty(s.name), s.pnlUsd, fmtSignedUsd(s.pnlUsd),
            "${s.closed} trades · ${pct(s.wins, s.closed)} won"
        )
        val winners = byPnl.filter { it.pnlUsd > 0 }.take(8).map(::bar)
        val losers = byPnl.filter { it.pnlUsd < 0 }.reversed().take(8).map(::bar)
        val winRates = ran.sortedByDescending { it.wins.toDouble() / it.closed }.take(12).map {
            Triple(pretty(it.name), it.wins.toDouble() / it.closed, "${it.wins}/${it.closed}")
        }

        val applied = all.filter { it.kind == AiEvent.APPLIED }
        val counts = applied.groupingBy { it.detail.substringBefore(' ') }.eachCount().entries.sortedByDescending { it.value }.take(8)
        val topCount = counts.maxOfOrNull { it.value }?.toDouble() ?: 1.0
        val mostChanged = counts.map { Triple(pretty(it.key), it.value / topCount, "${it.value} changes") }

        val clock = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }
        val recentChanges = all.filter { it.kind in setOf(AiEvent.APPLIED, AiEvent.SCORED_BETTER, AiEvent.SCORED_WORSE) }
            .takeLast(10).reversed().map {
                val tag = when (it.kind) { AiEvent.APPLIED -> "changed"; AiEvent.SCORED_BETTER -> "scored BETTER"; else -> "scored WORSE" }
                "${clock.format(Date(it.atMs))}  $tag  ${it.detail}"
            }

        val settled = bets.filter { it.result != null }.sortedBy { it.openedMs }
        var run = 0.0
        val curve = listOf(0.0) + settled.map { run += it.pnlUsd ?: 0.0; Math.round(run * 100.0) / 100.0 }
        val recentBets = bets.sortedByDescending { it.openedMs }.take(8).map {
            val res = when {
                it.result == null -> "open"
                (it.pnlUsd ?: 0.0) > 0 -> "WON " + fmtSignedUsd(it.pnlUsd ?: 0.0)
                (it.pnlUsd ?: 0.0) < 0 -> "LOST " + fmtSignedUsd(it.pnlUsd ?: 0.0)
                else -> it.result
            }
            "${clock.format(Date(it.openedMs))}  ${if (it.side == "YES") "UP" else "DOWN"} x${it.contracts} @ ${Math.round(it.price * 100)}¢ · ${it.confidence}% · $res"
        }

        val trades = ran.sumOf { it.closed }
        return base.copy(
            lastError = base.lastError.ifBlank { lastFailure.orEmpty() },
            calls24h = calls.size,
            callsOk24h = okCalls.size,
            avgMs = if (okCalls.isEmpty()) 0 else okCalls.sumOf { it.ms } / okCalls.size,
            hourLabels = labels,
            callsByHour = callsByHour,
            providers = providers,
            changesByHour = changesByHour,
            applied24h = day.count { it.kind == AiEvent.APPLIED },
            better = better,
            worse = worse,
            arenaNet = ran.sumOf { it.pnlUsd },
            arenaTrades = trades,
            arenaWinRate = if (trades == 0) 0.0 else ran.sumOf { it.wins }.toDouble() / trades,
            winners = winners,
            losers = losers,
            winRates = winRates,
            mostChanged = mostChanged,
            paperCurve = curve,
            paperWins = settled.count { (it.pnlUsd ?: 0.0) > 0 },
            paperLosses = settled.count { (it.pnlUsd ?: 0.0) < 0 },
            paperOpen = bets.count { it.result == null },
            paperNet = run,
            recentChanges = recentChanges,
            recentBets = recentBets
        )
    }

    /** "KASHI_FAV_BASE" → "Kashi fav base". */
    fun pretty(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun pct(n: Int, d: Int) = if (d == 0) "0%" else "${Math.round(100.0 * n / d)}%"
}

/** The Dashboard section of the AI tab. Stateless: draws [s]. */
@Composable
fun AiDashboardContent(s: AiDashboardState, onOpenKeys: () -> Unit = {}) {
    val c = DipTheme.colors
    val scalpColor = c.accentBlue
    val paperColor = Color(0xFFB083F0)
    val chatColor = c.accentOrange
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Keys and switches at a glance
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((name, ok) in s.keys) KeyChip(name, ok)
            Box(Modifier.weight(1f))
            Text(s.version, fontSize = 10.sp, color = c.textSecondary)
        }
        if (s.keys.none { it.second }) {
            Banner("No API key saved yet. Add your Groq, Mistral or Gemini key to start both AIs.", c.accentOrange, "Add keys", onOpenKeys)
        } else if (s.lastError.isNotBlank()) {
            Banner("Last AI call failed: ${s.lastError}", c.down, "Test keys", onOpenKeys)
        }
        AiStatusCard("SCALPING AI", s.scalpingOn, "Tunes the scalping algorithms every 60 s", s.scalpingStatus, scalpColor)
        AiStatusCard("PAPER AI", s.paperOn, "Places paper bets every minute", s.paperStatus, paperColor)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("AI calls 24h", s.calls24h.toString(), c.textPrimary, "${if (s.calls24h == 0) 0 else Math.round(100.0 * s.callsOk24h / s.calls24h)}% answered")
            StatTile("Changes 24h", s.applied24h.toString(), scalpColor, "${s.better} better · ${s.worse} worse")
            StatTile("Avg reply", if (s.avgMs == 0L) "—" else "${"%.1f".format(s.avgMs / 1000.0)}s", c.textPrimary, "per AI call")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Arena net", fmtSignedUsd(s.arenaNet), if (s.arenaNet >= 0) c.up else c.down, "${s.arenaTrades} trades")
            StatTile("Arena win rate", "${Math.round(s.arenaWinRate * 100)}%", if (s.arenaWinRate >= 0.5) c.up else c.down, "after fees")
            StatTile("Paper AI net", fmtSignedUsd(s.paperNet), if (s.paperNet >= 0) c.up else c.down, "${s.paperWins}W · ${s.paperLosses}L · ${s.paperOpen} open")
        }

        AiCard("AI calls per hour", "Last 24 hours, every request to Groq, Mistral and Gemini") {
            StackedBars(s.callsByHour, listOf(scalpColor, paperColor, chatColor, c.down), s.hourLabels)
            Legend(listOf("Scalping AI" to scalpColor, "Paper AI" to paperColor, "Chat" to chatColor, "Failed" to c.down))
        }

        AiCard("Providers", "Share of calls answered, and how fast") {
            if (s.providers.isEmpty()) Empty("No calls yet")
            PercentBars(s.providers.map { p ->
                Triple(p.name.replaceFirstChar { it.uppercase() } + if (p.model.isNotBlank()) " · ${p.model.substringAfterLast('/')}" else "",
                    if (p.ok + p.failed == 0) 0.0 else p.ok.toDouble() / (p.ok + p.failed),
                    "${p.ok}✓ ${p.failed}✗ ${if (p.avgMs > 0) "%.1fs".format(p.avgMs / 1000.0) else ""}")
            }, labelWidth = 150.dp)
            s.providers.filter { it.lastError.isNotBlank() }.forEach {
                Text("${it.name}: ${it.lastError}", fontSize = 10.sp, color = c.down, maxLines = 2)
            }
        }

        AiCard("Scalping AI changes per hour", "Settings it changed (and ones the hard bounds refused)") {
            StackedBars(s.changesByHour, listOf(scalpColor, c.textSecondary), s.hourLabels, height = 110.dp)
            Legend(listOf("Applied" to scalpColor, "Refused" to c.textSecondary))
        }

        AiCard("Did the changes work?", "Each change is scored after 4 more trades on that strategy") {
            Donut(
                listOf(Triple("Better", s.better.toFloat(), c.up), Triple("Worse", s.worse.toFloat(), c.down)),
                center = if (s.better + s.worse == 0) "—" else "${Math.round(100.0 * s.better / (s.better + s.worse))}%",
                centerSub = "better"
            )
        }

        AiCard("Scalping winners", "Net after fees, best strategies") {
            if (s.winners.isEmpty()) Empty("No winning strategy yet") else SignedBars(s.winners)
        }
        AiCard("Scalping losers", "Net after fees, worst strategies") {
            if (s.losers.isEmpty()) Empty("No losing strategy yet") else SignedBars(s.losers)
        }
        AiCard("Win rate by strategy", "Dashed line = 50%") {
            if (s.winRates.isEmpty()) Empty("No closed trades yet") else PercentBars(s.winRates)
        }
        AiCard("Strategies the AI tunes most", "Changes it applied, by strategy") {
            if (s.mostChanged.isEmpty()) Empty("No changes yet") else PercentBars(s.mostChanged)
        }

        AiCard("Paper AI profit", "Running total of its settled paper bets") {
            LineChart(s.paperCurve)
            Donut(
                listOf(
                    Triple("Won", s.paperWins.toFloat(), c.up),
                    Triple("Lost", s.paperLosses.toFloat(), c.down),
                    Triple("Open", s.paperOpen.toFloat(), c.textSecondary)
                ),
                center = "${s.paperWins + s.paperLosses}", centerSub = "settled", size = 96.dp
            )
        }
        AiCard("Paper AI bets", "Newest first") {
            if (s.recentBets.isEmpty()) Empty("No bets yet")
            s.recentBets.forEach { Text(it, fontSize = 11.sp, color = c.textPrimary) }
        }
        AiCard("Scalping AI journal", "Newest first") {
            if (s.recentChanges.isEmpty()) Empty("No changes yet")
            s.recentChanges.forEach { Text(it, fontSize = 11.sp, color = c.textPrimary, maxLines = 2) }
        }
    }
}

@Composable
private fun KeyChip(name: String, ok: Boolean) {
    val c = DipTheme.colors
    Row(
        Modifier
            .background(if (ok) c.upContainer else c.surfaceAlt, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(7.dp).background(if (ok) c.up else c.textSecondary, CircleShape))
        Text(name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (ok) c.up else c.textSecondary)
    }
}

@Composable
private fun Banner(text: String, color: Color, action: String, onClick: () -> Unit) {
    val c = DipTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .border(1.dp, color, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, Modifier.weight(1f), fontSize = 12.sp, color = c.textPrimary, maxLines = 3)
        TextButton(onClick = onClick) { Text(action, color = color, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun AiStatusCard(name: String, on: Boolean, what: String, status: String, color: Color) {
    val c = DipTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, if (on) color else c.border, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(12.dp).background(if (on) color else c.textSecondary, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.8.sp)
                Text(if (on) "ON" else "OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) c.up else c.textSecondary)
            }
            Text(what, fontSize = 11.sp, color = c.textSecondary)
            if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = c.textPrimary, maxLines = 2)
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, fontSize = 12.sp, color = DipTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
}
