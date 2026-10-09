package com.dirk.kalshiodds.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.service.BatteryExemption
import kotlinx.coroutines.launch
import java.util.Locale
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenData: () -> Unit,
    scrollToApiKey: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportKeys = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? -> uri?.let { viewModel.backupCredentials(it) } }
    val importKeys = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.restoreCredentials(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshBatteryStatus()
        viewModel.refreshLastOrderError()
    }
    SettingsContent(
        state = state,
        viewModel = viewModel,
        onBack = onBack,
        onOpenData = onOpenData,
        scrollToApiKey = scrollToApiKey,
        onExportKeys = { exportKeys.launch("diphunter-kalshi-key.dhcred") },
        onImportKeys = { importKeys.launch(arrayOf("*/*")) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenData: () -> Unit,
    scrollToApiKey: Boolean = false,
    viewModel: SettingsViewModel? = null,
    onExportKeys: () -> Unit = {},
    onImportKeys: () -> Unit = {}
) {
    val colors = DipTheme.colors
    val s = state.settings
    val context = LocalContext.current
    val updateScope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val apiKeyY = remember { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(scrollToApiKey, apiKeyY.intValue) {
        if (scrollToApiKey) {
            scroll.animateScrollTo(apiKeyY.intValue.coerceAtLeast(0))
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("Signal settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accentBlue
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                AppVersion.label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            OutlinedButton(
                enabled = !updateBusy,
                onClick = {
                    updateScope.launch {
                        updateBusy = true
                        updateMessage = "Checking branch release…"
                        updateMessage = when (val result = AppUpdater.checkAndDownload(context)) {
                            AppUpdater.Result.Current -> "This app is up to date."
                            is AppUpdater.Result.Failed -> result.reason
                            is AppUpdater.Result.Ready -> runCatching {
                                AppUpdater.showInstaller(context, result.apk)
                            }.getOrElse { it.message ?: "Could not open Android installer" }
                        }
                        updateBusy = false
                    }
                }
            ) { Text(if (updateBusy) "Checking for update…" else "Check for app update") }
            updateMessage?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
            Column(
                modifier = Modifier.onGloballyPositioned { apiKeyY.intValue = it.positionInParent().y.toInt() },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Section(ApiKeyUi.HEADER)
            val hasKey = s.tradingCredentialsConfigured()
            Text(
                ApiKeyUi.statusLine(hasKey = hasKey, connectionOk = state.connectionTestOk),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    hasKey && state.connectionTestOk -> colors.textPrimary
                    hasKey -> colors.accentOrange
                    else -> colors.accentOrange
                },
                fontWeight = FontWeight.Bold
            )
            if (hasKey && s.apiKeyId.isNotBlank()) {
                Text(
                    "Saved ${com.dirk.kalshiodds.signal.config.CredentialBackup.maskedKeyId(s.apiKeyId)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
            Text(
                "Create a key at kalshi.com → Account → API Keys. Paste Key ID + private key PEM. " +
                    "RSA-PSS/SHA-256 or Ed25519. Stored in EncryptedSharedPreferences. Never logged. " +
                    "Used for the public ticker / trade / orderbook_delta WebSocket and, after Live Approve, " +
                    "POST /trade-api/v2/portfolio/events/orders (V2 limit only — never /portfolio/orders).",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            OutlinedTextField(
                value = state.keyIdDraft,
                onValueChange = { viewModel?.setKeyIdDraft(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key ID") },
                singleLine = true
            )
            OutlinedTextField(
                value = state.pemDraft,
                onValueChange = { viewModel?.setPemDraft(it) },
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
                Button(onClick = { viewModel?.saveCredentials() }) {
                    Text(if (s.hasPrivateKey) "Update key" else "Save key")
                }
                OutlinedButton(onClick = { viewModel?.clearCredentials() }) { Text("Clear") }
            }
            if (state.keyIdDraft.isNotBlank() && !s.hasPrivateKey && state.pemDraft.isBlank()) {
                Text(
                    com.dirk.kalshiodds.signal.config.CredentialWriteGuard.REJECT_KEY_ONLY,
                    color = colors.accentOrange,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Test connection calls GET /trade-api/v2/portfolio/balance with the stored key " +
                    "(https://docs.kalshi.com/api-reference/portfolio/get-balance). Shows the cash " +
                    "or the exact Kalshi error (401 INCORRECT_API_KEY_SIGNATURE, missing PEM, clock skew).",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Button(
                onClick = { viewModel?.testConnection() },
                enabled = !state.connectionTestBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (state.connectionTestBusy) "Testing…" else "Test connection")
            }
            state.connectionTestMessage?.let {
                Text(
                    it,
                    color = if (it.contains(" ok ", ignoreCase = true) || it.startsWith("GET /portfolio/balance ok")) {
                        colors.textPrimary
                    } else {
                        colors.accentOrange
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Section("Last order error")
            Text(
                "Copyable last Live Approve / Test connection failure. Also written to SQLite + results.log on Approve.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            val lastErr = state.lastOrderError
            Text(
                lastErr ?: "No order error yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (lastErr == null) colors.textSecondary else colors.accentRed,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (lastErr == null) colors.surfaceAlt else MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp)
                    )
                    .then(
                        if (lastErr == null) Modifier
                        else Modifier.border(1.dp, colors.accentRed, RoundedCornerShape(12.dp))
                    )
                    .padding(12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { lastErr?.let { clipboard.setText(AnnotatedString(it)) } },
                    enabled = lastErr != null
                ) { Text("Copy last error") }
                OutlinedButton(onClick = { viewModel?.clearLastOrderError() }, enabled = lastErr != null) {
                    Text("Clear")
                }
            }
            Text(
                "Export writes a passphrase-encrypted file you pick (Downloads or Drive). " +
                    "Import restores the live key and, if present, the demo key. Never uploaded.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            OutlinedTextField(
                value = state.credPassphrase,
                onValueChange = { viewModel?.setCredPassphrase(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Keys backup passphrase") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onExportKeys) {
                    Text("Export keys backup")
                }
                OutlinedButton(onClick = onImportKeys) {
                    Text("Import keys backup")
                }
            }
            state.credentialMessage?.let {
                Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium)
            }
            }

            Text(
                "Crypto-only analysis plus optional approve-gated tickets. Nothing is sent to Kalshi without an explicit Approve tap on that ticket. WTI and other non-crypto markets are ignored. Not financial advice.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            Section(HomeHelp.HOME_TITLE)
            Text(
                HomeHelp.HOME_BODY,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                HomeHelp.TICKETS_BODY,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                HomeHelp.POSITIONS_BODY,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                HomeHelp.PAPER_BODY,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Section("Live signals")
            Text(
                "Leave Live signals on to keep the Kalshi WebSocket and scoring loop running after you switch apps or turn the screen off. Android shows an ongoing “DipHunter live signals” notification — allow it. Nothing is ordered without an in-app Approve tap.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            ToggleRow("Live signals (keep odds alive)", s.liveSignalsEnabled, { viewModel?.setLiveSignals(it) })
            Text(
                if (s.liveSignalsEnabled) {
                    "Running. Leave the ongoing notification in place. Swiping the app away will not stop it."
                } else {
                    "Stopped. The OS will close the live pipeline shortly after the app is backgrounded."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.liveSignalsEnabled) colors.textPrimary else colors.accentOrange,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Some phones (Samsung, Xiaomi, Oppo, …) still kill background apps. Optional: set Dip Hunter battery usage to Unrestricted. This never auto-trades.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                if (state.batteryUnrestricted) {
                    "Battery: unrestricted — good for background live odds."
                } else {
                    "Battery: restricted — the OS may still stop live odds."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.batteryUnrestricted) colors.textPrimary else colors.accentOrange
            )
            OutlinedButton(onClick = { BatteryExemption.openPrompt(context) }) {
                Text(if (state.batteryUnrestricted) "Open battery settings" else "Allow background")
            }

            Section("Watch series")
            Text(
                "Bitcoin-only. DipHunter watches KXBTC15M. Ethereum, Solana, and extra tickers are not subscribed, scored, or paper-traded.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            Section("Edge threshold")
            if (s.isSittingOut()) {
                Text(
                    "SIT OUT — auto-tune says the model is not beating the market. ${s.autoTuneNote}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentOrange,
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
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                String.format(Locale.US, "Manual slider  %.1f pp  — used when override is on", s.edgeThresholdPp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.edgeThresholdPp.toFloat(),
                onValueChange = { viewModel?.setThreshold(it.toDouble()) },
                valueRange = 1f..20f,
                steps = 18
            )
            ToggleRow("Auto-tune edge from settled history", s.autoTuneEnabled, { viewModel?.setAutoTuneEnabled(it) })
            ToggleRow("Manual override (use slider, ignore sit-out)", s.autoTuneManualOverride, { viewModel?.setAutoTuneOverride(it) })

            Section("Alerts")
            ToggleRow("Notifications", s.notificationsEnabled, { viewModel?.setNotifications(it) })
            ToggleRow("Long-shot / hunter cards", s.opportunityAlertsEnabled, { viewModel?.setOpportunityAlerts(it) })
            ToggleRow("Quiet opportunity alerts (no sound)", s.opportunityQuiet, { viewModel?.setOpportunityQuiet(it) })
            Text(
                "Opportunity notifications open the ticket. Approve is still required — they never place an order.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            ToggleRow("Subscribe public trades", s.subscribeTrades, { viewModel?.setSubscribeTrades(it) })

            Section("Skip filter")
            Text(
                "Alerts and ranked opportunities require confidence, liquidity, and a tight enough spread. Weak edges that fail are hidden (not alerted).",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                String.format(Locale.US, "Min confidence  %.0f%%", s.minConfidence * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.minConfidence.toFloat(),
                onValueChange = { viewModel?.setMinConfidence(it.toDouble()) },
                valueRange = 0.20f..0.80f,
                steps = 11
            )
            Text(
                String.format(Locale.US, "Min liquidity  %.0f (volume / OI / near-mid depth)", s.minLiquidity),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.minLiquidity.toFloat().coerceIn(0f, 10_000f),
                onValueChange = { viewModel?.setMinLiquidity(it.toDouble()) },
                valueRange = 0f..10_000f,
                steps = 19
            )
            Text(
                String.format(Locale.US, "Max spread  %.0f¢", s.maxSpreadCents),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.maxSpreadCents.toFloat(),
                onValueChange = { viewModel?.setMaxSpreadCents(it.toDouble()) },
                valueRange = 2f..20f,
                steps = 17
            )
            ToggleRow("Hide weak / filtered from opportunities", s.hideWeakOpportunities, { viewModel?.setHideWeak(it) })

            Section("Bankroll & size (advisory)")
            Text(
                "Suggested contracts only. The app never places orders. Default is quarter-Kelly, capped at 5% of bankroll and by liquidity / spread.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            OutlinedTextField(
                value = state.bankrollDraft,
                onValueChange = { viewModel?.setBankrollDraft(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bankroll (USD)") },
                supportingText = { Text("Used only to compute “N contracts max”.") },
                singleLine = true
            )
            ToggleRow("Kelly sizing (off = fixed fraction)", s.useKelly, { viewModel?.setUseKelly(it) })
            if (s.useKelly) {
                Text(
                    String.format(Locale.US, "Kelly fraction  %.2f  (0.25 = quarter-Kelly)", s.kellyFraction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = s.kellyFraction.toFloat(),
                    onValueChange = { viewModel?.setKellyFraction(it.toDouble()) },
                    valueRange = 0.10f..1.0f,
                    steps = 8
                )
            } else {
                Text(
                    String.format(Locale.US, "Fixed fraction  %.1f%% of bankroll", s.fixedFraction * 100.0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = s.fixedFraction.toFloat(),
                    onValueChange = { viewModel?.setFixedFractionValue(it.toDouble()) },
                    valueRange = 0.005f..0.10f,
                    steps = 18
                )
            }
            Text(
                String.format(Locale.US, "Max clip  %.1f%% of bankroll", s.maxBankrollFraction * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.maxBankrollFraction.toFloat(),
                onValueChange = { viewModel?.setMaxBankrollFraction(it.toDouble()) },
                valueRange = 0.01f..0.15f,
                steps = 13
            )

            Section("Fees & net EV")
            Text(
                "Official Kalshi taker fee is ceil_6dp(feeRate × C × P × (1−P)), then the whole order is aligned to $0.01 (non-direct). Default coefficient 7%. Crypto 15-minute series (KXBTC15M / KXETH15M / KXSOL15M) are quadratic with multiplier 1 — no special fee. Ranking and alerts add half-spread when net EV is on. Raw edge still shows on cards.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                String.format(Locale.US, "Fee rate  %.0f%%  (Kalshi default 7%%)", s.feeRate * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.feeRate.toFloat(),
                onValueChange = { viewModel?.setFeeRate(it.toDouble()) },
                valueRange = 0f..0.15f,
                steps = 14
            )
            ToggleRow("Rank & alert on net EV (after fees/spread)", s.rankByNetEv, { viewModel?.setRankByNetEv(it) })

            Section("Series / regime auto-mute")
            Text(
                "When a series or regime’s rolling 7-day hit rate falls below the floor (and has enough samples), it is muted: no alerts, downranked, “Muted” chip. Cold start never mutes.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            ToggleRow("Auto-mute weak series / regimes", s.autoMute, { viewModel?.setAutoMute(it) })
            Text(
                String.format(Locale.US, "Hit-rate floor  %.0f%%", s.muteHitRateFloor * 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.muteHitRateFloor.toFloat(),
                onValueChange = { viewModel?.setMuteFloor(it.toDouble()) },
                valueRange = 0.20f..0.60f,
                steps = 7
            )

            Section("Streak / drawdown guard")
            Text(
                "Pauses alerts after N consecutive wrong settlements or after a one-contract P&L-proxy drawdown. Scoring continues. Resume here or automatically on the next session.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            if (state.alertsPaused) {
                Text(
                    state.pauseReason ?: "alerts paused — streak guard",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = { viewModel?.resumeAlerts() }) { Text("Resume alerts") }
            }
            Text(
                "Pause after ${s.streakPauseN} wrong in a row",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.streakPauseN.toFloat(),
                onValueChange = { viewModel?.setStreakPauseN(it.toInt()) },
                valueRange = 2f..8f,
                steps = 5
            )
            Text(
                String.format(Locale.US, "Drawdown pause  $%.0f (1-contract proxy)", s.drawdownUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.drawdownUsd.toFloat().coerceIn(10f, 500f),
                onValueChange = { viewModel?.setDrawdownUsd(it.toDouble()) },
                valueRange = 10f..500f,
                steps = 48
            )
            ToggleRow("Resume automatically on next session", s.resumeOnNewSession, { viewModel?.setResumeOnNewSession(it) })

            Section("Results (survive crashes)")
            Text(
                "Scored snapshots, alerts, scorecard rows, and Approve-ticket attempts are written to on-device SQLite " +
                    "and a rolling results.log. Export writes a CSV you can open after a kill. " +
                    "Import, Kalshi backfill, spot candles, Supabase restore, and model weights live on the Data screen. " +
                    "Tickets are still Approve-only — never unsupervised bets.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Button(onClick = onOpenData, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Open Data (import / backfill)")
            }
            Button(onClick = { viewModel?.exportResults() }, modifier = Modifier.fillMaxWidth()) {
                Text("Export results")
            }
            state.exportMessage?.let {
                Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium)
            }

            Section("Heavy ML (0.3.0 / safe light default)")
            Text(
                "0.3.3 keeps light-mode scoring off the live order-book TreeMap (CME fix) and locks UP/YES vs DOWN/NO to spot-vs-target. " +
                "0.3.1 defaults to light mode (0.2.x MLP blend) after Heavy ML exhausted the 256MB heap on a Galaxy S24 Ultra. " +
                    "One OutOfMemoryError immediately latches light mode and persists so the next launch stays on 0.2.x. " +
                    "Non-OOM failures still trip after 3. Re-enable Heavy ML only if you accept the heap risk. " +
                    "Still analysis + approve-gated tickets only.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            ToggleRow(
                "Light mode (recommended — 0.2.x scoring, safer on device)",
                !s.heavyMlEnabled && !s.extendedAiEnabled,
                { viewModel?.setLightMode(it) }
            )
            state.mlGuardNote?.let {
                Text(it, color = colors.accentOrange, style = MaterialTheme.typography.bodyMedium)
            }
            ToggleRow("Heavy ML (sequence + GBM + ensemble)", s.heavyMlEnabled, { viewModel?.setHeavyMl(it) })
            ToggleRow("Sequence model (Temporal CNN / TinyLSTM)", s.sequenceModelEnabled, { viewModel?.setSequenceModel(it) })
            ToggleRow("GBM second opinion", s.gbmEnabled, { viewModel?.setGbm(it) })
            ToggleRow("Continual fine-tune + regime calibration", s.continualFineTune, { viewModel?.setContinualFineTune(it) })
            ToggleRow("Uncertainty gate (alerts / tickets)", s.uncertaintyGateEnabled, { viewModel?.setUncertaintyGate(it) })
            Text(
                String.format(Locale.US, "Max uncertainty  %.2f  (ensemble / MC-dropout std)", s.maxUncertainty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.maxUncertainty.toFloat().coerceIn(0.02f, 0.40f),
                onValueChange = { viewModel?.setMaxUncertainty(it.toDouble()) },
                valueRange = 0.02f..0.40f,
                steps = 18
            )
            Text(
                String.format(Locale.US, "Policy-eval stake  $%.0f  (scorecard counterfactual)", s.policyEvalStakeUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.policyEvalStakeUsd.toFloat().coerceIn(1f, 25f),
                onValueChange = { viewModel?.setPolicyEvalStakeUsd(it.toDouble()) },
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
                color = colors.textSecondary
            )
            ToggleRow("Extended AI (master)", s.extendedAiEnabled, { viewModel?.setExtendedAi(it) })
            ToggleRow("Regime classifier (session / weekend / news-shock)", s.regimeClassifierEnabled, { viewModel?.setRegimeClassifier(it) })
            ToggleRow("Anomaly / spoof gate", s.anomalyGateEnabled, { viewModel?.setAnomalyGate(it) })
            ToggleRow("Survival / hazard P(YES | TTE, path)", s.survivalModelEnabled, { viewModel?.setSurvivalModel(it) })
            ToggleRow("RL sizer (advisory only — never auto-bets)", s.rlSizerEnabled, { viewModel?.setRlSizer(it) })
            ToggleRow("News / social embedding pulse (fail-soft)", s.newsPulseEnabled, { viewModel?.setNewsPulse(it) })
            ToggleRow("Rival-flow clustering", s.rivalFlowEnabled, { viewModel?.setRivalFlow(it) })
            ToggleRow("Bayesian MM shadow voter", s.bayesianMmEnabled, { viewModel?.setBayesianMm(it) })
            ToggleRow("Conformal prediction sets", s.conformalEnabled, { viewModel?.setConformal(it) })
            ToggleRow("Meta-label take/skip", s.metaLabelEnabled, { viewModel?.setMetaLabel(it) })
            ToggleRow("Synthetic path simulator", s.pathSimEnabled, { viewModel?.setPathSim(it) })

            Section("Paper book (visible on home)")
            Text(
                "Isolated from live money. Starts at \$100, auto-logs a win-target-sized simulated fill when an AI hunter / LiveCall " +
                    "signal would trade. Never calls Kalshi. Reset returns cash to \$100. The home-screen PAPER BOOK " +
                    "card is the ledger — you do not need to dig here to see it. Paper trading ON does not swallow " +
                    "Live Approve after a Kalshi key is saved — use the Paper button for simulated fills.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            ToggleRow("Paper trading (AI auto-log win-target fills)", s.paperTradingEnabled, { viewModel?.setPaperTrading(it) })
            ToggleRow("AI auto-scalp (AI buys and sells on its own)", s.aiAutoScalp, { viewModel?.setAiAutoScalp(it) })
            ToggleRow(
                "AI auto-scalp with REAL money (live orders, no Approve tap)",
                s.aiAutoScalpLive,
                { viewModel?.setAiAutoScalpLive(it) }
            )
            OutlinedButton(onClick = { viewModel?.resetPaperBook() }, modifier = Modifier.height(44.dp)) {
                Text("Reset paper book to $100")
            }

            Section("Live Approve tickets (Kalshi V2)")
            Text(
                "Live Approve is \$5 all-in including Kalshi fees. Count is the largest integer with " +
                    "count×price + fee ≤ \$5 (fee = ceil_cent(0.07×count×P×(1−P))). " +
                    "Tickets below the min-profit-if-win setting (default \$10) stay disabled. " +
                    "The \$100-payout long-shot (ask ≤5¢ / configured stake) is still its own option. " +
                    "Hunter still surfaces when \$1 can settle ≥\$25. Long-shot hunter still needs ask ≤20¢ " +
                    "and AI beating implied after fees. Paper fills never block Live. " +
                    "Limit orders only — POST /trade-api/v2/portfolio/events/orders. No auto-fire.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                String.format(Locale.US, "Min profit if win  $%.0f", s.minProfitIfWinUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.minProfitIfWinUsd.toFloat().coerceIn(0f, 50f),
                onValueChange = { viewModel?.setMinProfitIfWinUsd(it.toDouble()) },
                valueRange = 0f..50f,
                steps = 49
            )
            ToggleRow("Show live trade tickets", s.ticketsEnabled, { viewModel?.setTicketsEnabled(it) })
            Text(
                String.format(Locale.US, "Ticket stake  $%.0f  (soft cap $5 · hard cap $25)", s.ticketStakeUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.ticketStakeUsd.toFloat().coerceIn(1f, 25f),
                onValueChange = { viewModel?.requestTicketStake(it.toDouble()) },
                valueRange = 1f..25f,
                steps = 23
            )
            ToggleRow(
                "Require skip filter / mute / streak-pause for tickets",
                s.ticketRespectGates,
                { viewModel?.setTicketRespectGates(it) }
            )
            Text(
                String.format(
                    Locale.US,
                    "Long-shot hunter  max ask ≤ %.0f¢  · $5 all-in · disabled if profit < $%.0f",
                    s.longShotMaxAsk * 100.0,
                    s.minProfitIfWinUsd
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accentOrange,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Max ask ¢ — sides cheaper than this can appear as Long-shot cards (default 20¢).",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Slider(
                value = (s.longShotMaxAsk * 100.0).toFloat().coerceIn(5f, 40f),
                onValueChange = { viewModel?.setLongShotMaxAsk(it.toDouble() / 100.0) },
                valueRange = 5f..40f,
                steps = 34
            )

            Section("Legacy win-target (History / paper only)")
            Text(
                "Off for live. Live Approve always uses the \$5 all-in cap and the min-profit setting above. " +
                    "This leftover \$50 sizer is kept so History restore still reads old snapshots — " +
                    "it can never resize a LIVE order above \$5 (the final order-build step clips again). " +
                    "Paper / History may still walk the ask book for a \$50 win target.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            ToggleRow("Legacy win-target sizing (default off · \$50)", s.winTargetEnabled, { viewModel?.setWinTargetEnabled(it) })
            Text(
                String.format(Locale.US, "Target profit  $%.0f", s.winTargetUsd),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.winTargetUsd.toFloat().coerceIn(5f, 200f),
                onValueChange = { viewModel?.setWinTargetUsd(it.toDouble()) },
                valueRange = 5f..200f,
                steps = 38
            )
            Text(
                String.format(Locale.US, "Max stake  %.0f%% of bankroll", s.winTargetBankrollPct),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = s.winTargetBankrollPct.toFloat().coerceIn(1f, 50f),
                onValueChange = { viewModel?.setWinTargetBankrollPct(it.toDouble()) },
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
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = (s.winTargetAbsCapUsd ?: 0.0).toFloat().coerceIn(0f, 200f),
                onValueChange = {
                    viewModel?.setWinTargetAbsCapUsd(if (it < 1f) null else it.toDouble())
                },
                valueRange = 0f..200f,
                steps = 39
            )

            Section("Kalshi demo (play money)")
            Text(
                "Separate from the local \$100 paper book. Demo uses https://external-api.demo.kalshi.co/trade-api/v2 " +
                    "and a demo-only key stored next to the GitHub token — not the live Kalshi EncryptedSharedPreferences. " +
                    "Paper Buy still works with no key and never hits this host.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            ToggleRow("Kalshi demo environment", s.kalshiDemoEnabled, { viewModel?.setKalshiDemo(it) })
            if (s.demoCredentialsConfigured) {
                Text(
                    "Demo key saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedTextField(
                value = state.demoKeyIdDraft,
                onValueChange = { viewModel?.setDemoKeyIdDraft(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Demo API Key ID") },
                singleLine = true
            )
            OutlinedTextField(
                value = state.demoPemDraft,
                onValueChange = { viewModel?.setDemoPemDraft(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (s.demoCredentialsConfigured) "Replace demo PEM" else "Demo private key PEM") },
                minLines = 4
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel?.saveDemoCredentials() }) {
                    Text(if (s.demoCredentialsConfigured) "Update demo key" else "Save demo key")
                }
                OutlinedButton(onClick = { viewModel?.clearDemoCredentials() }) { Text("Clear demo") }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Notification channel: diphunter_signal_alerts (HIGH). Foreground: diphunter_live_signals_ongoing. Live WS: wss://external-api-ws.kalshi.com/trade-api/ws/v2. Demo WS: wss://external-api-ws.demo.kalshi.co/trade-api/ws/v2.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
    }

    val pending = state.pendingRaiseStake
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { viewModel?.cancelRaiseStake() },
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
                        onValueChange = { viewModel?.setRaiseDraft(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Type ${SignalConstants.TICKET_RAISE_CONFIRM_PHRASE}") },
                        singleLine = true
                    )
                    state.raiseError?.let {
                        Text(it, color = colors.accentOrange, style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel?.confirmRaiseStake() }) { Text("Confirm raise") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel?.cancelRaiseStake() }) { Text("Keep $5 max") }
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
