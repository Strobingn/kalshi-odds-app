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
    // null = idle; -1 = size unknown; 0..100 = downloading
    var progress by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }
    val startInstall: (java.io.File) -> Unit = { apk ->
        if (!AppInstaller.canInstall(context)) {
            downloaded = apk
            note = "Android needs your OK once: turn on “Allow from this source”, go back, then tap Install."
            AppInstaller.openInstallPermission(context)
        } else if (!AppInstaller.install(context, apk)) {
            note = "Android could not open the installer. Use “Open in browser” instead."
        } else {
            note = null
        }
    }
    val install: (AppUpdate.Release) -> Unit = { release ->
        if (progress == null) {
            val ready = downloaded?.takeIf { it.exists() && it.name == AppInstaller.localName(release.apkName) }
            if (ready != null) {
                startInstall(ready)
            } else {
                note = null
                progress = -1
                scope.launch {
                    runCatching {
                        AppInstaller.download(context, release.apkUrl, release.apkName) { progress = it }
                    }.onSuccess {
                        progress = null
                        downloaded = it
                        startInstall(it)
                    }.onFailure {
                        progress = null
                        note = "Download failed: ${it.message?.take(120) ?: it.javaClass.simpleName}. Use “Open in browser” instead."
                    }
                }
            }
        }
    }

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
            Button(
                onClick = { install(available.latest) },
                enabled = progress == null,
                modifier = Modifier.height(44.dp)
            ) {
                Text(
                    when {
                        progress == null && downloaded != null -> "Install ${available.latest.label}"
                        progress == null -> "Download & install ${available.latest.label}"
                        progress == -1 -> "Downloading…"
                        else -> "Downloading ${progress}%"
                    }
                )
            }
        }
        OutlinedButton(onClick = check, modifier = Modifier.height(44.dp)) {
            Text(if (status == AppUpdate.Status.Checking) "Checking…" else "Check for update")
        }
    }
    if (available != null) {
        OutlinedButton(onClick = { openLink(context, available.latest.apkUrl) }, modifier = Modifier.height(36.dp)) {
            Text("Open in browser instead")
        }
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accentRed)
    }
    Text(
        if (available != null) {
            "Downloads here, then Android shows its Update prompt. It installs over this app and keeps your settings and Kalshi key."
        } else {
            "Builds come from the grok-bitcoin branch on GitHub. A new one installs over this app and keeps your settings and Kalshi key."
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
