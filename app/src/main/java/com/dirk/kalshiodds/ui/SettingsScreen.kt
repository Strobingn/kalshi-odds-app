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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.Bg
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state.settings

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
                "Crypto-only analysis. Alerts and scoring never place orders. WTI and other non-crypto markets are ignored.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

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
            Text(
                String.format(Locale.US, "%.1f pp  — alert when |fair − mid| crosses this", s.edgeThresholdPp),
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

            Section("Alerts")
            ToggleRow("Notifications", s.notificationsEnabled, viewModel::setNotifications)
            ToggleRow("Live signals (WS foreground)", s.liveSignalsEnabled, viewModel::setLiveSignals)
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
            Text(
                "Live signals keep a foreground WebSocket for instant local alerts. " +
                    "Killed-app remote push would need FCM later — this release is local-only. " +
                    "Without an API key the app stays on fast REST and shows “WS needs API key — using REST”.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )

            Section("Kalshi API key (WS handshake only)")
            Text(
                "Create a key at kalshi.com → Account → API Keys. Paste Key ID + private key PEM. " +
                    "RSA-PSS/SHA-256 or Ed25519. Stored in EncryptedSharedPreferences. Never logged. " +
                    "Used only to authenticate the public ticker / trade / orderbook_delta WebSocket — no portfolio or order-placement channels.",
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
                Button(onClick = viewModel::saveCredentials) { Text("Save key") }
                OutlinedButton(onClick = viewModel::clearCredentials) { Text("Clear key") }
            }
            state.credentialMessage?.let {
                Text(it, color = AccentBlue, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Notification channel: diphunter_signal_alerts (HIGH). Foreground: diphunter_live_signals (LOW). WS: wss://external-api-ws.kalshi.com/trade-api/ws/v2",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
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
