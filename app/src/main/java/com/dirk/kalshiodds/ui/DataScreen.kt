package com.dirk.kalshiodds.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(
    viewModel: DataViewModel,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    showBack: Boolean = true
) {
    val colors = DipTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::importUri) }
    val importModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::importModelUri) }
    val backupCreds = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? -> uri?.let(viewModel::backupCredentials) }
    val restoreCreds = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::restoreCredentials) }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = dipContentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("Data") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary
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
                "Import history, backfill settled 15m windows, and load an offline-trained model. Nothing here places a Kalshi order.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            StatsCard(state)

            Button(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("History") }

            Section("Back up Kalshi credentials")
            Text(
                "Sideloaded debug APKs used to be signed by a different machine each time, so Android required uninstall — which wiped the Keystore-encrypted key. Future 0.3.7+ debug APKs share one cert so updates keep your data. Still: back up the key with a passphrase and keep the file in Drive/Downloads.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            OutlinedTextField(
                value = state.credPassphrase,
                onValueChange = viewModel::setCredPassphrase,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backup passphrase") },
                singleLine = true
            )
            Button(
                onClick = { backupCreds.launch("diphunter-kalshi-key.dhcred") },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Back up credentials") }
            OutlinedButton(
                onClick = { restoreCreds.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Restore credentials") }

            Section("Import file")
            Text(
                "Pick a Bitcoin Kalshi CSV/JSON export or a Kalshi account fill-history CSV. Parsing is streamed; duplicates (id / timestamp) are skipped.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Button(
                onClick = { importFile.launch(arrayOf("text/*", "application/json", "text/csv", "*/*")) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Import file") }
            state.importSummary?.let { s ->
                Text(
                    "${s.imported} imported · ${s.skipped} skipped · ${s.dateRangeLabel}\n" +
                        "snapshots ${s.snapshots} · alerts ${s.alerts} · fills ${s.fills} · tickets ${s.tickets}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary
                )
            }

            Section("Backfill from Kalshi")
            Text(
                "WorkManager job: settled KXBTC15M / KXETH15M / KXSOL15M + 1-minute candlesticks (live then historical), then matching Coinbase spot candles. Rate-limited, resumable, cancellable.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                "Lookback  ${state.days} days",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = state.days.toFloat(),
                onValueChange = { viewModel.setDays(it.toInt()) },
                valueRange = 7f..90f,
                steps = 11
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::startBackfill,
                    enabled = !state.backfillRunning,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("Backfill Kalshi + spot") }
                OutlinedButton(
                    onClick = viewModel::cancelBackfill,
                    enabled = state.backfillRunning,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("Cancel") }
            }
            state.backfillMsg?.let {
                Text(
                    if (state.backfillRunning) "Running · $it · ${state.processed} windows" else it,
                    color = if (state.backfillRunning) colors.accentOrange else colors.accentBlue,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Section("Restore from Supabase")
            Text(
                "Optional mirror. Paste the project URL and the publishable/anon key — never a service_role key. Reads diphunter_snapshots / diphunter_results / diphunter_settled.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            OutlinedTextField(
                value = state.supabaseUrlDraft,
                onValueChange = { viewModel.setSupabaseDrafts(it, state.supabaseKeyDraft) },
                label = { Text("Supabase URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.supabaseKeyDraft,
                onValueChange = { viewModel.setSupabaseDrafts(state.supabaseUrlDraft, it) },
                label = { Text("Anon / publishable key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::saveSupabase, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Save")
                }
                Button(
                    onClick = viewModel::restoreSupabase,
                    enabled = state.settings.supabaseConfigured,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Restore") }
            }
            androidx.compose.material3.Switch(
                checked = state.settings.syncEnabled,
                onCheckedChange = viewModel::setSyncEnabled
            )
            Text(
                "Live sync of History / bets / signals / settings (never the Kalshi key).",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            CloudSyncStatusBlock(state.settings)
            OutlinedButton(
                onClick = viewModel::syncNow,
                enabled = state.settings.supabaseConfigured && state.settings.syncEnabled,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Sync now") }

            Section("Import model")
            Text(
                "Load the JSON weights from `python3 ml/train_edge.py`. On-device inference is this file plus the existing light online learner — no extra heavy nets.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Text(
                "Weekly GitHub Action publishes edge-model-latest. Get latest model downloads the manifest + JSON, shows holdout Brier/log-loss, and activates only if it beats the market. Previous model stays for rollback. Private repo: paste a GitHub token (encrypted, not the Kalshi key) or use Import model JSON.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            Button(
                onClick = viewModel::getLatestModel,
                enabled = !state.modelBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (state.modelBusy) "Fetching…" else "Get latest model") }
            OutlinedTextField(
                value = state.githubTokenDraft,
                onValueChange = viewModel::setGithubTokenDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitHub token (private repo)") },
                singleLine = true
            )
            OutlinedButton(
                onClick = viewModel::saveGithubToken,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Save GitHub token") }
            Button(
                onClick = { importModel.launch(arrayOf("application/json", "text/*", "*/*")) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Import model JSON") }
            OutlinedButton(
                onClick = viewModel::rollbackModel,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Roll back previous model") }
            state.modelNote?.let {
                Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium)
            }

            state.message?.takeIf { it != state.modelNote }?.let {
                Text(it, color = colors.accentBlue, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatsCard(state: DataUiState) {
    val colors = DipTheme.colors
    val s = state.stats
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, com.dirk.kalshiodds.ui.theme.FieldShapes.card)
            .padding(16.dp)
    ) {
        Text("DATA STATS", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, fontWeight = FontWeight.Bold)
        Text(
            "${s.settledCount} settled windows",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(s.dateRangeLabel, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        Text(
            String.format(
                Locale.US,
                "BTC %d · ETH %d · SOL %d · YES %d · NO %d",
                s.btc, s.eth, s.sol, s.yesSettled, s.noSettled
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Price path ${s.pathPoints} · spot candles ${s.spotCandles} · fills ${s.fills}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}
