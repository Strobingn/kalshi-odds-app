package com.dirk.kalshiodds.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a Claude-branch APK inside the app and hands it to Android's
 * installer. The browser hand-off this replaces left the user to find the
 * file and often never reached the install prompt.
 *
 * Android still asks for "Install unknown apps" once for this app and shows
 * its own Update prompt; nothing installs silently. CI APKs share one signing
 * key, so the new build replaces this one and keeps its data.
 */
object AppInstaller {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun updatesDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Safe local file name for [apkName]: keeps letters, digits, dot, dash, underscore. */
    fun localName(apkName: String): String =
        apkName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "update.apk" }.let {
            if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk"
        }

    /** Downloads [url] to the cache; [onProgress] gets 0..100 (or -1 when the size is unknown). */
    suspend fun download(context: Context, url: String, apkName: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "Update link is not https" }
            val dir = updatesDir(context)
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, localName(apkName))
            val part = File(dir, target.name + ".part")
            val request = Request.Builder().url(url).header("User-Agent", "MisBitcoin-updater").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Download failed: empty answer")
                val total = body.contentLength()
                var done = 0L
                var last = -2
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != last) {
                                last = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IllegalStateException("Download was cut short")
            }
            if (part.length() < 1_000_000L) {
                part.delete()
                throw IllegalStateException("Downloaded file is too small to be the app")
            }
            if (!part.renameTo(target)) throw IllegalStateException("Could not save the update")
            target
        }

    /** True when Android lets this app start an install (always true before Android 8). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for this app. */
    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Hands [apk] to Android's package installer. False when no installer could be opened. */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
