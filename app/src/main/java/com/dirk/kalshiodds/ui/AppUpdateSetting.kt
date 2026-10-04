package com.dirk.kalshiodds.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.launch

/**
 * Settings → App update. Checks GitHub for a newer Claude-branch build when
 * Settings opens and on tap; Download hands the APK link to the browser and
 * Android shows its own install prompt.
 */
@Composable
fun AppUpdateSetting(
    checker: AppUpdateChecker = remember { AppUpdateChecker() },
    autoCheck: Boolean = true
) {
    val colors = DipTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AppUpdate.Status>(AppUpdate.Status.Idle) }
    val check: () -> Unit = {
        if (status != AppUpdate.Status.Checking) {
            status = AppUpdate.Status.Checking
            scope.launch { status = checker.check() }
        }
    }
    LaunchedEffect(autoCheck) { if (autoCheck) check() }

    Text(
        "App update",
        style = MaterialTheme.typography.titleMedium,
        color = colors.textPrimary,
        fontWeight = FontWeight.Bold
    )
    Text(
        AppUpdate.buildLabel(),
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary
    )
    val available = status as? AppUpdate.Status.Available
    Text(
        AppUpdate.statusLine(status),
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            available != null -> colors.accentOrange
            status is AppUpdate.Status.Failed -> colors.accentRed
            else -> colors.textPrimary
        },
        fontWeight = FontWeight.SemiBold
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (available != null) {
            Button(onClick = { openLink(context, available.latest.apkUrl) }, modifier = Modifier.height(44.dp)) {
                Text("Download ${available.latest.label}")
            }
        }
        OutlinedButton(onClick = check, modifier = Modifier.height(44.dp)) {
            Text(if (status == AppUpdate.Status.Checking) "Checking…" else "Check for update")
        }
    }
    Text(
        if (available != null) {
            "Download opens the APK in your browser. Open the downloaded file and tap Update: " +
                "it installs over this app and keeps your settings and Kalshi key."
        } else {
            "Builds come from the Claude branch on GitHub. A new one installs over this app and keeps your settings and Kalshi key."
        },
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary
    )
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
