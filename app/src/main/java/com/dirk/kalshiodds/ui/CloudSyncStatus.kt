package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings → Data cloud-sync line. [lastSyncAtMs] is the last success.
 * A failed attempt keeps that clock and shows the HTTP / offline reason.
 */
object CloudSyncStatus {
    private val FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)

    fun lastSynced(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (atMs <= 0L) return "Last synced: never"
        val local = Instant.ofEpochMilli(atMs).atZone(zone)
        return "Last synced: ${FORMAT.format(local)}"
    }

    fun isError(message: String): Boolean {
        val m = message.trim().lowercase()
        if (m.isBlank() || m.startsWith("synced")) return false
        if (m == "cloud sync off" || m.startsWith("cloud sync ready") || m.startsWith("cloud sync is off")) {
            return false
        }
        return m == "offline" ||
            m.startsWith("401") ||
            m.startsWith("403") ||
            m.startsWith("404") ||
            m.startsWith("5") ||
            m.contains("rls") ||
            m.contains("bad key") ||
            m.contains("table missing") ||
            m.contains("sync failed") ||
            m.contains("not configured")
    }

    fun line(settings: DataHubSettings, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!settings.supabaseConfigured) return "Supabase not configured — History stays on this phone."
        if (!settings.syncEnabled) return "Cloud sync off"
        val head = lastSynced(settings.lastSyncAtMs, zone)
        val detail = settings.lastSyncMessage.trim()
        return if (detail.isBlank()) {
            "$head\nCloud sync ready — never uploads the Kalshi key."
        } else {
            "$head\n$detail"
        }
    }
}

@Composable
fun CloudSyncStatusBlock(settings: DataHubSettings, zone: ZoneId = ZoneId.systemDefault()) {
    val colors = DipTheme.colors
    val text = CloudSyncStatus.line(settings, zone)
    val error = CloudSyncStatus.isError(settings.lastSyncMessage)
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (error) colors.accentRed else colors.accentBlue,
        modifier = Modifier.fillMaxWidth()
    )
}
