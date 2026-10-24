package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    /** "Now using Groq · 312 of 500 calls, …" */
    val rotation: String = "",
    val keys: List<Pair<String, Boolean>> = listOf("Groq" to false, "Cerebras" to false, "Gemini" to false, "Mistral" to false),
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
    val recentBets: List<String> = emptyList(),
    /** The Paper AI's lessons, written after each settled window it bet on; newest first. */
    val paperLessons: List<String> = emptyList(),
    /** The on-phone live tuner and the self-learning gate (both need no key). */
    val liveTunerOn: Boolean = true,
    val gateOn: Boolean = true,
    val liveChanges24h: Int = 0,
    /** Times the Scalping AI was called early in the last 24 h. */
    val kicks24h: Int = 0,
    /** Newest first: live-tuner changes and early calls. */
    val liveFeed: List<String> = emptyList(),
    /** Per learned trade, oldest first: (rolling win rate, threshold, predicted chance of a win), each 0..1. */
    val gateCurve: List<Triple<Double, Double, Double>> = emptyList(),
    /** Per strategy: (name, threshold 0..1, "n · p · skipped"). */
    val gateRows: List<Triple<String, Double, String>> = emptyList(),
    val gateLearned: Int = 0,
    val gateSkipped: Int = 0,
    val gateActive: Int = 0
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
        val recentChanges = all.filter { it.ai != AiEvent.LIVE && it.kind in setOf(AiEvent.APPLIED, AiEvent.SCORED_BETTER, AiEvent.SCORED_WORSE) }
            .takeLast(10).reversed().map {
                val tag = when (it.kind) { AiEvent.APPLIED -> "changed"; AiEvent.SCORED_BETTER -> "scored BETTER"; else -> "scored WORSE" }
                "${clock.format(Date(it.atMs))}  $tag  ${it.detail}"
            }
        val secClock = SimpleDateFormat("h:mm:ss a", Locale.US).apply { timeZone = zone }
        val liveFeed = all.filter { (it.ai == AiEvent.LIVE && it.kind == AiEvent.APPLIED) || it.kind == AiEvent.KICK }
            .takeLast(14).reversed().map {
                val tag = if (it.kind == AiEvent.KICK) "asked Scalping AI early:" else "live tuner:"
                "${secClock.format(Date(it.atMs))}  $tag ${it.detail}"
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
            val fair = it.fairCents?.let { f -> " · fair ${Math.round(f)}%" }.orEmpty()
            "${clock.format(Date(it.openedMs))}  ${if (it.side == "YES") "UP" else "DOWN"} x${it.contracts} @ ${Math.round(it.price * 100)}¢ · ${it.confidence}%$fair · $res"
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
            recentBets = recentBets,
            liveChanges24h = day.count { it.ai == AiEvent.LIVE && it.kind == AiEvent.APPLIED },
            kicks24h = day.count { it.kind == AiEvent.KICK },
            liveFeed = liveFeed
        )
    }

    /**
     * The self-learning gate's chart and table from its learned points and rows: a 50-trade rolling win rate, and the
     * threshold and predicted chance smoothed over 20 trades.
     */
    fun withGate(s: AiDashboardState, points: List<com.dirk.kalshiodds.signal.scalper.OnlineGate.Point>, rows: List<com.dirk.kalshiodds.signal.scalper.OnlineGate.Row>): AiDashboardState {
        val wins = ArrayDeque<Int>()
        var conf: Double? = null
        var p: Double? = null
        val a = 1.0 / 20.0
        val curve = points.map { pt ->
            wins.addLast(if (pt.win) 1 else 0)
            while (wins.size > 50) wins.removeFirst()
            conf = conf?.let { it * (1 - a) + pt.minConf * a } ?: pt.minConf
            p = p?.let { it * (1 - a) + pt.p * a } ?: pt.p
            Triple(wins.average(), conf!!, p!!)
        }
        val table = rows.filter { it.n > 0 }.take(12).map { r ->
            Triple(
                pretty(r.strategy), r.minConf,
                "${r.n} learned · ${r.lastP?.let { "p ${Math.round(it * 100)}%" } ?: "p —"} · ${r.skipped} skipped" + if (r.gating) "" else " · warming up"
            )
        }
        return s.copy(
            gateCurve = curve, gateRows = table,
            gateLearned = rows.sumOf { it.n }, gateSkipped = rows.sumOf { it.skipped }, gateActive = rows.count { it.gating }
        )
    }

    /** "KASHI_FAV_BASE" → "Kashi fav base". */
    fun pretty(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun pct(n: Int, d: Int) = if (d == 0) "0%" else "${Math.round(100.0 * n / d)}%"
}

/** The Dashboard section of the AI tab. Stateless: draws [s]. */
@Composable
fun AiDashboardContent(
    s: AiDashboardState,
    onOpenKeys: () -> Unit = {},
    /** Switches for the on-phone live tuner and the self-learning gate; null hides them (screenshots). */
    onLiveTuner: ((Boolean) -> Unit)? = null,
    onGate: ((Boolean) -> Unit)? = null
) {
    val c = DipTheme.colors
    val scalpColor = c.accentBlue
    val paperColor = c.accentPurple
    val chatColor = c.accentOrange
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Keys and switches at a glance
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((name, ok) in s.keys) KeyChip(name, ok)
            Box(Modifier.weight(1f))
            Text(s.version, fontSize = 10.sp, color = c.textSecondary)
        }
        if (s.keys.none { it.second }) {
            Banner("No API key saved yet. Add a Groq, Cerebras, Gemini or Mistral key to start both AIs.", c.accentOrange, "Add keys", onOpenKeys)
        } else if (s.lastError.isNotBlank()) {
            Banner("Last AI call failed: ${s.lastError}", c.down, "Test keys", onOpenKeys)
        }
        if (s.rotation.isNotBlank()) Text(s.rotation, fontSize = 11.sp, color = c.textSecondary)
        AiStatusCard("SCALPING AI", s.scalpingOn, "Tunes the scalping algorithms every 60 s", s.scalpingStatus, scalpColor)
        AiStatusCard("PAPER AI", s.paperOn, "Places paper bets every minute", s.paperStatus, paperColor)
        AiSwitchCard(
            "LIVE TUNER", s.liveTunerOn, "On the phone, no key: after every 4 trades a winner gets bigger and looser, a loser smaller and stricter",
            "${s.liveChanges24h} changes · ${s.kicks24h} early Scalping AI calls (24 h)", chatColor, onLiveTuner, "SWITCH_LIVE_TUNER"
        )
        AiSwitchCard(
            "SELF-LEARNING GATE", s.gateOn, "Learns from every closed trade and skips entries that look like a strategy's losers",
            "${s.gateLearned} trades learned · ${s.gateActive} strategies gated · ${s.gateSkipped} entries skipped", c.up, onGate, "SWITCH_GATE"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("AI calls 24h", s.calls24h.toString(), c.textPrimary, "${if (s.calls24h == 0) 0 else Math.round(100.0 * s.callsOk24h / s.calls24h)}% answered")
            StatTile("Changes 24h", s.applied24h.toString(), scalpColor, "${s.better} better · ${s.worse} worse")
            StatTile("Avg reply", if (s.avgMs == 0L) "—" else "${"%.1f".format(s.avgMs / 1000.0)}s", c.textPrimary, "per AI call")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Arena net", fmtSignedUsd(s.arenaNet), if (s.arenaNet >= 0) c.up else c.down, "${s.arenaTrades} trades")
            StatTile("Arena win rate", if (s.arenaTrades == 0) "—" else "${Math.round(s.arenaWinRate * 100)}%", if (s.arenaTrades == 0) c.textPrimary else if (s.arenaWinRate >= 0.5) c.up else c.down, "after fees")
            StatTile("Paper AI net", fmtSignedUsd(s.paperNet), if (s.paperNet >= 0) c.up else c.down, "${s.paperWins}W · ${s.paperLosses}L · ${s.paperOpen} open")
        }

        AiCard("Live tuner and early calls", "Newest first · the arena adapts between the Scalping AI's calls") {
            if (s.liveFeed.isEmpty()) Empty("Nothing yet: it changes a strategy after its 4th closed trade")
            s.liveFeed.forEach { Text(it, fontSize = 11.sp, color = c.textPrimary, maxLines = 2) }
        }
        AiCard("Self-learning gate", "Rolling win rate (50 trades), the learned threshold and the predicted chance of a win, per learned trade") {
            if (s.gateCurve.size < 2) {
                Empty("Draws after the first trades close")
            } else {
                GateChart(s.gateCurve, c.up, chatColor, scalpColor)
                Legend(listOf("Win rate" to c.up, "Threshold" to chatColor, "Predicted" to scalpColor))
            }
            if (s.gateRows.isNotEmpty()) {
                Text("Threshold by strategy", fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
                PercentBars(s.gateRows, labelWidth = 130.dp)
            }
        }

        AiCard("AI calls per hour", "Last 24 hours, every request to Groq, Cerebras, Gemini and Mistral") {
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
        AiCard("Paper AI lessons", "Written after each window it bet on settles; newest first") {
            if (s.paperLessons.isEmpty()) Empty("No lessons yet")
            s.paperLessons.forEach { Text(it, fontSize = 11.sp, color = c.textPrimary) }
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
private fun AiSwitchCard(name: String, on: Boolean, what: String, status: String, color: Color, onToggle: ((Boolean) -> Unit)?, tag: String) {
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
            Text(status, fontSize = 12.sp, color = c.textPrimary, maxLines = 2)
        }
        if (onToggle != null) {
            androidx.compose.material3.Switch(checked = on, onCheckedChange = onToggle, modifier = Modifier.testTag(tag))
        }
    }
}

/** Three 0..1 lines over the learned trades, with 25 / 50 / 75% guides. */
@Composable
private fun GateChart(points: List<Triple<Double, Double, Double>>, win: Color, conf: Color, pred: Color) {
    val c = DipTheme.colors
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp).testTag("CHART_GATE")) {
        val w = size.width
        val h = size.height
        for (g in listOf(0.25, 0.5, 0.75)) {
            val y = h * (1f - g.toFloat())
            drawLine(if (g == 0.5) c.textSecondary.copy(alpha = 0.6f) else c.border, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1.dp.toPx())
        }
        fun line(sel: (Triple<Double, Double, Double>) -> Double, color: Color, width: Float) {
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, pt ->
                val x = w * i / (points.size - 1).coerceAtLeast(1)
                val y = h * (1f - sel(pt).coerceIn(0.0, 1.0).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = width))
        }
        line({ it.second }, conf, 1.25.dp.toPx())
        line({ it.third }, pred, 1.dp.toPx())
        line({ it.first }, win, 1.5.dp.toPx())
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, fontSize = 12.sp, color = DipTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
}
