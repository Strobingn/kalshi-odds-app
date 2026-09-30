package com.dirk.kalshiarb.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dirk.kalshiarb.ArbNotifier
import com.dirk.kalshiarb.data.KalshiPublicClient
import com.dirk.kalshiarb.scan.Opportunity
import com.dirk.kalshiarb.scan.PaperEntry
import com.dirk.kalshiarb.scan.Side
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val AUTO_SCAN_INTERVAL_MS = 60_000L
private const val READ_ONLY_BANNER =
    "Read-only · shows opportunities, never trades. Arbs are rare and brief; prices can move before you act."

@Composable
fun ArbApp(state: ArbUiState, vm: ArbViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !ArbNotifier.canPost(context)) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { if (state.notify) askNotifyPermission() }

    // Auto-scan every 60 s, only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.autoScan) {
        if (state.autoScan) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.scanNow()
                    delay(AUTO_SCAN_INTERVAL_MS)
                }
            }
        }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(
                "Arb Hunter",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Opportunities") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Paper log (${state.log.size})") })
            }
            when (tab) {
                0 -> ScanTab(state, vm, now, onNotifyOn = { askNotifyPermission() })
                else -> LogTab(state, vm, now)
            }
        }
    }
}

@Composable
private fun Banner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            READ_ONLY_BANNER,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun ScanTab(state: ArbUiState, vm: ArbViewModel, now: Long, onNotifyOn: () -> Unit) {
    var showNear by rememberSaveable { mutableStateOf(false) }
    var showUnverified by rememberSaveable { mutableStateOf(false) }
    val firstSeen = remember(state.log) { state.log.filter { it.open }.associate { it.key to it.firstSeenMs } }
    val report = state.report
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Banner() }
        item { Controls(state, vm, onNotifyOn) }
        item { Summary(state, now) }
        if (report != null) {
            val r = report.result
            if (r.opportunities.isEmpty()) {
                item {
                    Text(
                        "No risk-free set clears fees right now. That's the normal state.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(r.opportunities, key = { "o:" + it.key }) { o ->
                OpportunityCard(o, firstSeen[o.key], now)
            }
            collapsible(
                title = "Near misses (${r.nearMisses.size}) · within 2¢/set after fees",
                open = showNear,
                onToggle = { showNear = !showNear },
                list = r.nearMisses.take(40),
                prefix = "n:",
                now = now
            )
            collapsible(
                title = "Unverified (${r.unverified.size}) · payoff not proven, not counted",
                open = showUnverified,
                onToggle = { showUnverified = !showUnverified },
                list = r.unverified.take(40),
                prefix = "u:",
                now = now
            )
            if (report.warnings.isNotEmpty()) {
                item {
                    Text(
                        report.warnings.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun LazyListScope.collapsible(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    list: List<Opportunity>,
    prefix: String,
    now: Long
) {
    item(key = prefix + "header") {
        Text(
            (if (open) "▾ " else "▸ ") + title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp)
        )
    }
    if (open) {
        items(list, key = { prefix + it.key }) { o -> OpportunityCard(o, null, now) }
    }
}

@Composable
private fun Controls(state: ArbUiState, vm: ArbViewModel, onNotifyOn: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.scanNow() }, enabled = !state.scanning) {
                    Text(if (state.scanning) "Scanning…" else "Scan now")
                }
                Spacer(Modifier.width(12.dp))
                if (state.scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(state.progress, style = MaterialTheme.typography.bodySmall)
                }
            }
            SwitchRow("Auto-scan every 60 s while open", state.autoScan) { vm.setAutoScan(it) }
            SwitchRow("Notify on new arb ≥ ${Format.cents(ArbNotifier.MIN_PROFIT_CENTS)}", state.notify) {
                vm.setNotify(it)
                if (it) onNotifyOn()
            }
            SwitchRow("Light theme", state.lightTheme) { vm.setLightTheme(it) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Summary(state: ArbUiState, now: Long) {
    val err = state.error
    if (err != null) {
        Text("Scan failed: $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    val r = state.report ?: run {
        if (err == null) {
            Text(
                "Tap Scan now to check every open Kalshi event.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val res = r.result
    Column {
        Text(
            "Scanned ${r.events} events · ${r.markets} markets · ${res.opportunities.size} opportunities · " +
                "${Format.cents(res.lockedProfitCents)} locked",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "${r.candidates} candidates · ${r.booksFetched} books · took ${Format.ago(r.finishedMs - r.startedMs)} · " +
                "${Format.ago(now - r.finishedMs)} ago",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OpportunityCard(o: Opportunity, firstSeenMs: Long?, now: Long) {
    val colors = LocalArbColors.current
    val uri = LocalUriHandler.current
    val s = o.structure
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.eventTitle.ifBlank { s.eventTicker }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(
                "${s.type.label} · ${s.eventTicker}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (s.note.isNotBlank()) {
                Text(
                    s.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.verified) MaterialTheme.colorScheme.onSurfaceVariant else colors.warn
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            for (leg in o.legs) {
                val sideColor = if (leg.spec.side == Side.YES) colors.profit else colors.loss
                Row {
                    Text(
                        "BUY ${leg.spec.side} ",
                        color = sideColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        "${leg.spec.label} · ${leg.qty} @ ${Format.priceE4(leg.avgPriceE4)}" +
                            if (leg.worstPriceE4 > leg.avgPriceE4 + 0.5) " (to ${Format.priceE4(leg.worstPriceE4.toDouble())})" else "",
                        fontSize = 12.sp
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Cost ${Format.cents(o.costCents)} · Payout ≥ ${Format.cents(o.payoutCents)} · Fees ${Format.feeE4(o.feeE4)}",
                style = MaterialTheme.typography.bodySmall
            )
            if (o.isProfitable) {
                Text(
                    "Profit ${Format.cents(o.profitCents)} (${Format.pct(o.profitPct)}) on ${o.sets} sets",
                    color = colors.profit,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    String.format(Locale.US, "Short by %.2f¢ per set after fees", o.shortfallPerSetCents),
                    color = colors.warn,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val age = firstSeenMs?.let { "first seen ${Format.ago(now - it)} ago" } ?: ""
                Text(
                    age,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    runCatching { uri.openUri(KalshiPublicClient.marketPageUrl(s.seriesTicker, s.eventTicker)) }
                }) { Text("Open in Kalshi") }
            }
        }
    }
}

@Composable
private fun LogTab(state: ArbUiState, vm: ArbViewModel, now: Long) {
    val log = state.log
    val closed = log.filter { !it.open }
    val avgMs = if (closed.isEmpty()) 0L else closed.sumOf { it.durationMs } / closed.size
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Banner() }
        item {
            Column {
                Text(
                    "${log.size} opportunities logged · ${log.count { it.open }} open now",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (closed.isEmpty()) "No closed opportunities yet." else
                        "Closed ones lasted ${Format.ago(avgMs)} on average (between first and last scan that saw them).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { vm.clearLog() }, enabled = log.isNotEmpty()) { Text("Clear log") }
            }
        }
        items(log, key = { it.key + ":" + it.firstSeenMs }) { e -> LogCard(e, now) }
    }
}

private val timeFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

@Composable
private fun LogCard(e: PaperEntry, now: Long) {
    val colors = LocalArbColors.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.eventTitle.ifBlank { e.eventTicker },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (e.open) "OPEN" else "GONE",
                    color = if (e.open) colors.profit else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Text(e.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(e.legs, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            Text(
                "First ${timeFmt.format(Date(e.firstSeenMs))} · last ${timeFmt.format(Date(e.lastSeenMs))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Lasted ${Format.ago(e.durationMs)} · seen in ${e.sightings} scans · peak ${e.peakSets} sets / " +
                    "${Format.cents(e.peakProfitCents)}" + if (e.open) " · open ${Format.ago(now - e.firstSeenMs)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (e.open) colors.profit else Color.Unspecified
            )
        }
    }
}
