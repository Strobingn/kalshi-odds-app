package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.ui.components.FieldCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.FieldMetrics

/**
 * Dedicated real-money destination. Approve still opens the existing
 * confirm sheet (Approve tap, then REAL MONEY). Controls that already
 * live on Home, Live, and Settings stay there.
 */
@Composable
fun RealMoneyScreen(
    odds: OddsViewModel,
    settings: SettingsViewModel,
    onOpenApiKey: () -> Unit
) {
    val state by odds.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val liveKey = state.settings.credentialsConfigured
    val demo = settingsState.settings.kalshiDemoEnabled || state.settings.kalshiDemoEnabled
    val proposals = state.tickets.proposals.ifEmpty {
        (state.tickets.phase as? TicketPhase.AwaitingApprove)?.let { listOf(it.ticket) + it.others }
            ?: emptyList()
    }
    val mode = com.dirk.kalshiodds.signal.paper.AutopilotMode.parse(state.settings.autopilotMode)
    val day = com.dirk.kalshiodds.signal.paper.LiveAutopilotGate.dayKey(System.currentTimeMillis())
    val page = RealMoneyTab.of(
        liveKeySaved = liveKey,
        demoEnvironment = demo,
        cashUsd = state.liveCashUsd,
        paperOn = state.settings.paperTradingEnabled,
        proposals = proposals,
        d3 = state.d3,
        working = state.tickets.working,
        resting = state.restingOrders,
        positions = state.positions,
        autopilotMode = mode,
        liveArmed = state.liveAutopilotArmed,
        liveApproveTapped = state.liveAutopilotApproveTapped,
        dailySpentUsd = state.shadow.spentOn(day),
        shadowTickets = state.shadow.tickets,
        shadowBankrollUsd = state.shadow.bankrollUsd,
        shadowPnlUsd = state.shadow.lifetimeRealizedPnlUsd,
        liveError = state.shadow.liveLastError
    )
    val colors = DipTheme.colors
    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = dipContentInsets()
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(FieldMetrics.screenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldMetrics.space12)
        ) {
            Text(
                RealMoneyTab.TITLE,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            FieldCard {
                Text(page.account.environmentLabel, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(page.account.balanceLabel, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                Text(
                    if (page.account.keyValid) "API key saved and the balance endpoint answered."
                    else if (page.account.keySaved) "API key is saved. A live balance confirms it."
                    else "Save a live Kalshi API key to trade real money.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                if (page.account.showAddKey) {
                    Button(onClick = onOpenApiKey, modifier = Modifier.fillMaxWidth()) {
                        Text(RealMoneyTab.ADD_KEY)
                    }
                }
            }
            FieldCard {
                Text("Autopilot", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Mode: ${page.autopilotModeLabel}. ${page.autopilotDetail}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                AutopilotModeSelector(mode, onSelect = { settings.setAutopilotMode(it) })
                Text(page.liveStatus, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                if (page.showLiveApprove) {
                    Button(onClick = { odds.tapLiveAutopilotApprove() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Approve live Autopilot")
                    }
                }
                if (page.showLiveConfirm) {
                    Text(RealMoneyTab.LIVE_BLURB, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Button(onClick = { odds.confirmLiveAutopilotRealMoney() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Confirm REAL MONEY")
                    }
                }
                page.largeClipWarning?.let { warn ->
                    Text(warn, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                }
                page.liveError?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Button(onClick = { odds.clearLiveAutopilotError() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear live error")
                    }
                }
            }
            FieldCard {
                Text("SHADOW — not submitted", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(page.shadowBankrollLabel, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                if (page.shadowLines.isEmpty()) {
                    Text("No shadow orders yet.", color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                } else {
                    page.shadowLines.forEach { line ->
                        Text(line, color = colors.textPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            page.switches.forEach { sw ->
                FieldCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sw.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            Text(sw.explanation, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                        }
                        Switch(
                            checked = sw.on,
                            onCheckedChange = { on ->
                                when (sw.id) {
                                    "demo" -> settings.setKalshiDemo(on)
                                    "paper" -> odds.setPaperTrading(on)
                                }
                            }
                        )
                    }
                }
            }
            LinesSection("Waiting for Approve", page.pending.map { it.line }, "No real ticket is waiting.") {
                page.pending.forEach { row ->
                    Button(onClick = { odds.openTicketApprove(row.id) }) { Text("Approve") }
                }
            }
            FieldCard {
                Text("D3 Bitcoin daily 5 PM", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(page.d3Status, color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(com.dirk.kalshiodds.signal.d3.D3Copy.CONFIRM_POST_ONLY, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                page.d3Tickets.forEach { row ->
                    Text(row.line, color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { odds.openTicketApprove(row.id) }) { Text("Approve") }
                }
            }
            LinesSection("Open real orders", page.orders, "No open Kalshi orders.")
            LinesSection("Real positions", page.positions, "No open Kalshi positions.")
            LinesSection("Real fills and P&L", listOf(page.realPnlLabel) + page.fills, "No settled real P&L on open markets.")
            FieldCard {
                Text(
                    page.explainer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary
                )
            }
        }
    }
}

@Composable
private fun LinesSection(
    title: String,
    lines: List<String>,
    empty: String,
    extra: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {}
) {
    val colors = DipTheme.colors
    FieldCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        if (lines.isEmpty()) {
            Text(empty, color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            lines.forEach { line ->
                Text(line, color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        extra()
    }
}
