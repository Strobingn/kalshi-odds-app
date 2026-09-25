package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.service.BatteryExemption
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.Bg
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenData: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshBatteryStatus()
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Signal settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Bg,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = AccentBlue
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Crypto-only analysis plus optional approve-gated tickets. Nothing is sent to Kalshi without an explicit Approve tap on that ticket. WTI and other non-crypto markets are ignored. Not financial advice.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Section("Live signals")
            Text(
                "Leave Live signals on to keep the Kalshi WebSocket and scoring loop running after you switch apps or turn the screen off. Android shows an ongoing “DipHunter live signals” notification — allow it. Nothing is ordered without an in-app Approve tap.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Live signals (keep odds alive)", s.liveSignalsEnabled, viewModel::setLiveSignals)
            Text(
                if (s.liveSignalsEnabled) {
                    "Running. Leave the ongoing notification in place. Swiping the app away will not stop it."
                } else {
                    "Stopped. The OS will close the live pipeline shortly after the app is backgrounded."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.liveSignalsEnabled) AccentGreen else AccentOrange,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Some phones (Samsung, Xiaomi, Oppo, …) still kill background apps. Optional: set Dip Hunter battery usage to Unrestricted. This never auto-trades.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                if (state.batteryUnrestricted) {
                    "Battery: unrestricted — good for background live odds."
                } else {
                    "Battery: restricted — the OS may still stop live odds."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.batteryUnrestricted) AccentGreen else AccentOrange
            )
            OutlinedButton(onClick = { BatteryExemption.openPrompt(context) }) {
                Text(if (state.batteryUnrestricted) "Open battery settings" else "Allow background")
            }

            Section("Watch series")
            ToggleRow("Bitcoin · KXBTC15M", s.watchBtc, viewModel::setWatchBtc)
            ToggleRow("Ethereum · KXETH15M", s.watchEth, viewModel::setWatchEth)
            ToggleRow("Solana · KXSOL15M", s.watchSol, viewModel::setWatchSol)

            OutlinedTextField(
                value = s.extraTickersText,
                onValueChange = viewModel::setExtraText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Extra crypto tickers") },
                supportingText = {
                    Text(
                        state.extraRejected
                            ?: "Comma-separated Kalshi tickers (ETH/SOL/XRP/… 15m ok). Non-crypto dropped.",
                        color = if (state.extraRejected != null) AccentOrange else TextSecondary
                    )
                },
                minLines = 2
            )

            Section("Edge threshold")
            if (s.isSittingOut()) {
                Text(
                    "SIT OUT — auto-tune says the model is not beating the market. ${s.autoTuneNote}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold
                )
            } else if (s.autoTuneEnabled && !s.autoTuneManualOverride && s.tunedEdgeThresholdPp != null) {
                Text(
                    String.format(
                        Locale.US,
                        "Auto-tune  %.1f pp  — %s",
                        s.tunedEdgeThresholdPp,
                        s.autoTuneNote.ifBlank { "from settled history" }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                String.format(Locale.US, "Manual slider  %.1f pp  — used when override is on", s.edgeThresholdPp),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.edgeThresholdPp.toFloat(),
                onValueChange = { viewModel.setThreshold(it.toDouble()) },
                valueRange = 1f..20f,
                steps = 18
            )
            ToggleRow("Auto-tune edge from settled history", s.autoTuneEnabled, viewModel::setAutoTuneEnabled)
            ToggleRow("Manual override (use slider, ignore sit-out)", s.autoTuneManualOverride, viewModel::setAutoTuneOverride)

            Section("Alerts")
            ToggleRow("Notifications", s.notificationsEnabled, viewModel::setNotifications)
            ToggleRow("Long-shot / $50 win-target cards", s.opportunityAlertsEnabled, viewModel::setOpportunityAlerts)
            ToggleRow("Quiet opportunity alerts (no sound)", s.opportunityQuiet, viewModel::setOpportunityQuiet)
            Text(
                "Opportunity notifications open the ticket. Approve is still required — they never place an order.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Subscribe public trades", s.subscribeTrades, viewModel::setSubscribeTrades)

            Section("Skip filter")
            Text(
                "Alerts and ranked opportunities require confidence, liquidity, and a tight enough spread. Weak edges that fail are hidden (not alerted).",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                String.format(Locale.US, "Min confidence  %.0f%%", s.minConfidence * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.minConfidence.toFloat(),
                onValueChange = { viewModel.setMinConfidence(it.toDouble()) },
                valueRange = 0.20f..0.80f,
                steps = 11
            )
            Text(
                String.format(Locale.US, "Min liquidity  %.0f (volume / OI / near-mid depth)", s.minLiquidity),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.minLiquidity.toFloat().coerceIn(0f, 10_000f),
                onValueChange = { viewModel.setMinLiquidity(it.toDouble()) },
                valueRange = 0f..10_000f,
                steps = 19
            )
            Text(
                String.format(Locale.US, "Max spread  %.0f¢", s.maxSpreadCents),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.maxSpreadCents.toFloat(),
                onValueChange = { viewModel.setMaxSpreadCents(it.toDouble()) },
                valueRange = 2f..20f,
                steps = 17
            )
            ToggleRow("Hide weak / filtered from opportunities", s.hideWeakOpportunities, viewModel::setHideWeak)

            Section("Bankroll & size (advisory)")
            Text(
                "Suggested contracts only. The app never places orders. Default is quarter-Kelly, capped at 5% of bankroll and by liquidity / spread.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            OutlinedTextField(
                value = state.bankrollDraft,
                onValueChange = viewModel::setBankrollDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bankroll (USD)") },
                supportingText = { Text("Used only to compute “N contracts max”.") },
                singleLine = true
            )
            ToggleRow("Kelly sizing (off = fixed fraction)", s.useKelly, viewModel::setUseKelly)
            if (s.useKelly) {
                Text(
                    String.format(Locale.US, "Kelly fraction  %.2f  (0.25 = quarter-Kelly)", s.kellyFraction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = s.kellyFraction.toFloat(),
                    onValueChange = { viewModel.setKellyFraction(it.toDouble()) },
                    valueRange = 0.10f..1.0f,
                    steps = 8
                )
            } else {
                Text(
                    String.format(Locale.US, "Fixed fraction  %.1f%% of bankroll", s.fixedFraction * 100.0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = s.fixedFraction.toFloat(),
                    onValueChange = { viewModel.setFixedFractionValue(it.toDouble()) },
                    valueRange = 0.005f..0.10f,
                    steps = 18
                )
            }
            Text(
                String.format(Locale.US, "Max clip  %.1f%% of bankroll", s.maxBankrollFraction * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.maxBankrollFraction.toFloat(),
                onValueChange = { viewModel.setMaxBankrollFraction(it.toDouble()) },
                valueRange = 0.01f..0.15f,
                steps = 13
            )

            Section("Fees & net EV")
            Text(
                "Official Kalshi taker fee is ceil-to-the-next-cent of feeRate × C × P × (1−P) (default 7%). Crypto 15-minute series (KXBTC15M / KXETH15M / KXSOL15M) have no series fee override. Ranking and alerts add half-spread when net EV is on. Raw edge still shows on cards.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                String.format(Locale.US, "Fee rate  %.0f%%  (Kalshi default 7%%)", s.feeRate * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.feeRate.toFloat(),
                onValueChange = { viewModel.setFeeRate(it.toDouble()) },
                valueRange = 0f..0.15f,
                steps = 14
            )
            ToggleRow("Rank & alert on net EV (after fees/spread)", s.rankByNetEv, viewModel::setRankByNetEv)

            Section("Series / regime auto-mute")
            Text(
                "When a series or regime’s rolling 7-day hit rate falls below the floor (and has enough samples), it is muted: no alerts, downranked, “Muted” chip. Cold start never mutes.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Auto-mute weak series / regimes", s.autoMute, viewModel::setAutoMute)
            Text(
                String.format(Locale.US, "Hit-rate floor  %.0f%%", s.muteHitRateFloor * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.muteHitRateFloor.toFloat(),
                onValueChange = { viewModel.setMuteFloor(it.toDouble()) },
                valueRange = 0.20f..0.60f,
                steps = 7
            )

            Section("Streak / drawdown guard")
            Text(
                "Pauses alerts after N consecutive wrong settlements or after a one-contract P&L-proxy drawdown. Scoring continues. Resume here or automatically on the next session.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            if (state.alertsPaused) {
                Text(
                    state.pauseReason ?: "alerts paused — streak guard",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = viewModel::resumeAlerts) { Text("Resume alerts") }
            }
            Text(
                "Pause after ${s.streakPauseN} wrong in a row",
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.streakPauseN.toFloat(),
                onValueChange = { viewModel.setStreakPauseN(it.toInt()) },
                valueRange = 2f..8f,
                steps = 5
            )
            Text(
                String.format(Locale.US, "Drawdown pause  $%.0f (1-contract proxy)", s.drawdownUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.drawdownUsd.toFloat().coerceIn(10f, 500f),
                onValueChange = { viewModel.setDrawdownUsd(it.toDouble()) },
                valueRange = 10f..500f,
                steps = 48
            )
            ToggleRow("Resume automatically on next session", s.resumeOnNewSession, viewModel::setResumeOnNewSession)

            Section("Results (survive crashes)")
            Text(
                "Scored snapshots, alerts, scorecard rows, and Approve-ticket attempts are written to on-device SQLite " +
                    "and a rolling results.log. Export writes a CSV you can open after a kill. " +
                    "Import, Kalshi backfill, spot candles, Supabase restore, and model weights live on the Data screen. " +
                    "Tickets are still Approve-only — never unsupervised bets.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Button(onClick = onOpenData, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Open Data (import / backfill)")
            }
            Button(onClick = viewModel::exportResults, modifier = Modifier.fillMaxWidth()) {
                Text("Export results")
            }
            state.exportMessage?.let {
                Text(it, color = AccentBlue, style = MaterialTheme.typography.bodyMedium)
            }

            Section("Heavy ML (0.3.0 / safe light default)")
            Text(
                "0.3.3 keeps light-mode scoring off the live order-book TreeMap (CME fix) and locks UP/YES vs DOWN/NO to spot-vs-target. " +
                "0.3.1 defaults to light mode (0.2.x MLP blend) after Heavy ML exhausted the 256MB heap on a Galaxy S24 Ultra. " +
                    "One OutOfMemoryError immediately latches light mode and persists so the next launch stays on 0.2.x. " +
                    "Non-OOM failures still trip after 3. Re-enable Heavy ML only if you accept the heap risk. " +
                    "Still analysis + approve-gated tickets only.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow(
                "Light mode (recommended — 0.2.x scoring, safer on device)",
                !s.heavyMlEnabled && !s.extendedAiEnabled,
                viewModel::setLightMode
            )
            state.mlGuardNote?.let {
                Text(it, color = AccentOrange, style = MaterialTheme.typography.bodyMedium)
            }
            ToggleRow("Heavy ML (sequence + GBM + ensemble)", s.heavyMlEnabled, viewModel::setHeavyMl)
            ToggleRow("Sequence model (Temporal CNN / TinyLSTM)", s.sequenceModelEnabled, viewModel::setSequenceModel)
            ToggleRow("GBM second opinion", s.gbmEnabled, viewModel::setGbm)
            ToggleRow("Continual fine-tune + regime calibration", s.continualFineTune, viewModel::setContinualFineTune)
            ToggleRow("Uncertainty gate (alerts / tickets)", s.uncertaintyGateEnabled, viewModel::setUncertaintyGate)
            Text(
                String.format(Locale.US, "Max uncertainty  %.2f  (ensemble / MC-dropout std)", s.maxUncertainty),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.maxUncertainty.toFloat().coerceIn(0.02f, 0.40f),
                onValueChange = { viewModel.setMaxUncertainty(it.toDouble()) },
                valueRange = 0.02f..0.40f,
                steps = 18
            )
            Text(
                String.format(Locale.US, "Policy-eval stake  $%.0f  (scorecard counterfactual)", s.policyEvalStakeUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.policyEvalStakeUsd.toFloat().coerceIn(1f, 25f),
                onValueChange = { viewModel.setPolicyEvalStakeUsd(it.toDouble()) },
                valueRange = 1f..25f,
                steps = 23
            )

            Section("Extended AI (0.3.0 · 10–19)")
            Text(
                "On-device regime / anomaly / survival / conformal / meta / path-sim plus advisory RL sizing. " +
                    "These can raise CPU and battery — turn the master switch off to drop back to Heavy ML + 0.2.x. " +
                    "RL suggested stake is display-only. Tickets always use the configured \$5 default / caps and still need Approve. " +
                    "News embeddings fail-soft and cache if the network is down.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Extended AI (master)", s.extendedAiEnabled, viewModel::setExtendedAi)
            ToggleRow("Regime classifier (session / weekend / news-shock)", s.regimeClassifierEnabled, viewModel::setRegimeClassifier)
            ToggleRow("Anomaly / spoof gate", s.anomalyGateEnabled, viewModel::setAnomalyGate)
            ToggleRow("Survival / hazard P(YES | TTE, path)", s.survivalModelEnabled, viewModel::setSurvivalModel)
            ToggleRow("RL sizer (advisory only — never auto-bets)", s.rlSizerEnabled, viewModel::setRlSizer)
            ToggleRow("News / social embedding pulse (fail-soft)", s.newsPulseEnabled, viewModel::setNewsPulse)
            ToggleRow("Rival-flow clustering", s.rivalFlowEnabled, viewModel::setRivalFlow)
            ToggleRow("Bayesian MM shadow voter", s.bayesianMmEnabled, viewModel::setBayesianMm)
            ToggleRow("Conformal prediction sets", s.conformalEnabled, viewModel::setConformal)
            ToggleRow("Meta-label take/skip", s.metaLabelEnabled, viewModel::setMetaLabel)
            ToggleRow("Synthetic path simulator", s.pathSimEnabled, viewModel::setPathSim)

            Section("Paper book (visible on home)")
            Text(
                "Isolated from live money. Starts at \$100, auto-logs a win-target-sized simulated fill when an AI hunter / LiveCall " +
                    "signal would trade. Never calls Kalshi. Reset returns cash to \$100. The home-screen PAPER BOOK " +
                    "card is the ledger — you do not need to dig here to see it.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Paper trading (AI auto-log win-target fills)", s.paperTradingEnabled, viewModel::setPaperTrading)
            OutlinedButton(onClick = viewModel::resetPaperBook, modifier = Modifier.height(44.dp)) {
                Text("Reset paper book to $100")
            }

            Section("Live Approve tickets (Kalshi V2)")
            Text(
                "Three approve-gated live paths, each sized so a win pays the target (default \$50): " +
                    "(1) Long-shot hunter — sides priced ≤20¢ (editable) when AI/fair beats implied after fees; " +
                    "(2) hunter — still surfaces when a \$1 stake can settle ≥\$25 (ask ≤~4¢); " +
                    "(3) configured stake (default \$5) when max payout is ≥\$100 (ask ≤5¢). " +
                    "Buy YES / Buy NO on any market opens a manual ticket. Limit orders only — never market. " +
                    "Expired 15m windows drop or move to the live contract; a missing ask shows on that ticket " +
                    "(Approve stays off). Your positions load from GET /portfolio/positions; Sell is a reduce-only V2 " +
                    "limit (ask to sell YES, bid to sell NO) capped at held size. " +
                    "Live Approve uses POST /trade-api/v2/portfolio/events/orders only (no v1 fallback). " +
                    "Raising stake above \$5 requires typing " +
                    "${SignalConstants.TICKET_RAISE_CONFIRM_PHRASE}. Hard cap \$${SignalConstants.TICKET_STAKE_HARD_CAP_USD.toInt()}. " +
                    "High variance: you can lose the full stake.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Show live trade tickets", s.ticketsEnabled, viewModel::setTicketsEnabled)
            Text(
                String.format(Locale.US, "Ticket stake  $%.0f  (soft cap $5 · hard cap $25)", s.ticketStakeUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.ticketStakeUsd.toFloat().coerceIn(1f, 25f),
                onValueChange = { viewModel.requestTicketStake(it.toDouble()) },
                valueRange = 1f..25f,
                steps = 23
            )
            ToggleRow(
                "Require skip filter / mute / streak-pause for tickets",
                s.ticketRespectGates,
                viewModel::setTicketRespectGates
            )
            Text(
                String.format(
                    Locale.US,
                    "Long-shot hunter  max ask ≤ %.0f¢  · sized to win $%.0f when AI beats implied after fees",
                    s.longShotMaxAsk * 100.0,
                    s.winTargetUsd
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentOrange,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Max ask ¢ — sides cheaper than this can appear as Long-shot cards (default 20¢).",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Slider(
                value = (s.longShotMaxAsk * 100.0).toFloat().coerceIn(5f, 40f),
                onValueChange = { viewModel.setLongShotMaxAsk(it.toDouble() / 100.0) },
                valueRange = 5f..40f,
                steps = 34
            )

            Section("Win target sizing")
            Text(
                "On by default. Size every Buy UP/DOWN and hunter card (including Long-shot) so profit-if-win ≥ the target, walking the ask book (VWAP, not top-of-book). " +
                    "Live Approve uses GET /portfolio/balance cash; Paper uses paper-book equity. " +
                    "Stake is capped at a % of that bankroll (default 10%) and an optional $ cap. " +
                    "When the cap or book depth limits size, the card shows Capped: wins \$X. " +
                    "Still Approve-only — never auto-placed.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Win-target sizing (default on · \$50)", s.winTargetEnabled, viewModel::setWinTargetEnabled)
            Text(
                String.format(Locale.US, "Target profit  $%.0f", s.winTargetUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.winTargetUsd.toFloat().coerceIn(5f, 200f),
                onValueChange = { viewModel.setWinTargetUsd(it.toDouble()) },
                valueRange = 5f..200f,
                steps = 38
            )
            Text(
                String.format(Locale.US, "Max stake  %.0f%% of bankroll", s.winTargetBankrollPct),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.winTargetBankrollPct.toFloat().coerceIn(1f, 50f),
                onValueChange = { viewModel.setWinTargetBankrollPct(it.toDouble()) },
                valueRange = 1f..50f,
                steps = 48
            )
            Text(
                if (s.winTargetAbsCapUsd == null) {
                    "Optional $ cap  off"
                } else {
                    String.format(Locale.US, "Optional $ cap  $%.0f", s.winTargetAbsCapUsd)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = (s.winTargetAbsCapUsd ?: 0.0).toFloat().coerceIn(0f, 200f),
                onValueChange = {
                    viewModel.setWinTargetAbsCapUsd(if (it < 1f) null else it.toDouble())
                },
                valueRange = 0f..200f,
                steps = 39
            )

            Section("Kalshi API key (WS + approve-gated orders)")
            if (s.hasPrivateKey) {
                Text(
                    "Kalshi key saved (${com.dirk.kalshiodds.signal.config.CredentialBackup.maskedKeyId(s.apiKeyId)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Create a key at kalshi.com → Account → API Keys. Paste Key ID + private key PEM. " +
                    "RSA-PSS/SHA-256 or Ed25519. Stored in EncryptedSharedPreferences. Never logged. " +
                    "Used for the public ticker / trade / orderbook_delta WebSocket and, after Live Approve, " +
                    "POST /trade-api/v2/portfolio/events/orders (V2 limit only — never /portfolio/orders).",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            OutlinedTextField(
                value = state.keyIdDraft,
                onValueChange = viewModel::setKeyIdDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key ID") },
                singleLine = true
            )
            OutlinedTextField(
                value = state.pemDraft,
                onValueChange = viewModel::setPemDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (s.hasPrivateKey) "Replace private key PEM" else "Private key PEM") },
                minLines = 5,
                supportingText = {
                    Text(
                        if (s.hasPrivateKey) "A private key is stored on this device."
                        else "-----BEGIN PRIVATE KEY----- / BEGIN RSA PRIVATE KEY-----"
                    )
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::saveCredentials) {
                    Text(if (s.hasPrivateKey) "Update key" else "Save key")
                }
                OutlinedButton(onClick = viewModel::clearCredentials) { Text("Clear") }
            }
            state.credentialMessage?.let {
                Text(it, color = AccentBlue, style = MaterialTheme.typography.bodyMedium)
            }

            Section("Kalshi demo (play money)")
            Text(
                "Separate from the local \$100 paper book. Demo uses https://external-api.demo.kalshi.co/trade-api/v2 " +
                    "and a demo-only key stored next to the GitHub token — not the live Kalshi EncryptedSharedPreferences. " +
                    "Paper Buy still works with no key and never hits this host.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            ToggleRow("Kalshi demo environment", s.kalshiDemoEnabled, viewModel::setKalshiDemo)
            if (s.demoCredentialsConfigured) {
                Text(
                    "Demo key saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedTextField(
                value = state.demoKeyIdDraft,
                onValueChange = viewModel::setDemoKeyIdDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Demo API Key ID") },
                singleLine = true
            )
            OutlinedTextField(
                value = state.demoPemDraft,
                onValueChange = viewModel::setDemoPemDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (s.demoCredentialsConfigured) "Replace demo PEM" else "Demo private key PEM") },
                minLines = 4
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::saveDemoCredentials) {
                    Text(if (s.demoCredentialsConfigured) "Update demo key" else "Save demo key")
                }
                OutlinedButton(onClick = viewModel::clearDemoCredentials) { Text("Clear demo") }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Notification channel: diphunter_signal_alerts (HIGH). Foreground: diphunter_live_signals_ongoing. Live WS: wss://external-api-ws.kalshi.com/trade-api/ws/v2. Demo WS: wss://external-api-ws.demo.kalshi.co/trade-api/ws/v2.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
    }

    val pending = state.pendingRaiseStake
    if (pending != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRaiseStake,
            title = { Text("Raise ticket stake?") },
            text = {
                Column {
                    Text(
                        String.format(
                            Locale.US,
                            "Default is $5. Type %s to set stake to $%.0f (hard cap $25). This is not auto-trading.",
                            SignalConstants.TICKET_RAISE_CONFIRM_PHRASE,
                            pending
                        )
                    )
                    OutlinedTextField(
                        value = state.raiseDraft,
                        onValueChange = viewModel::setRaiseDraft,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Type ${SignalConstants.TICKET_RAISE_CONFIRM_PHRASE}") },
                        singleLine = true
                    )
                    state.raiseError?.let {
                        Text(it, color = AccentOrange, style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmRaiseStake) { Text("Confirm raise") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRaiseStake) { Text("Keep $5 max") }
            }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
