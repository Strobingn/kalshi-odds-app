package com.dirk.kalshiodds.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dirk.kalshiodds.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Checks this build's own branch release and hands a verified package to Android. */
object AppUpdater {
    private const val VERSION_BASE = 1_000_000

    fun releaseTag(): String = BuildConfig.UPDATE_RELEASE_TAG

    fun releaseApiUrl(tag: String = releaseTag()): String =
        "https://api.github.com/repos/Strobingn/kalshi-odds-app/releases/tags/$tag"

    /** True only when both sides have certificates and the sets match. */
    fun sameSigningCertificates(installed: List<ByteArray>, downloaded: List<ByteArray>): Boolean {
        if (installed.isEmpty() || downloaded.isEmpty()) return false
        return installed.map { sha256(it) }.toSet() == downloaded.map { sha256(it) }.toSet()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    data class Asset(val url: String, val versionCode: Int)

    sealed class Result {
        data object Current : Result()
        data class Ready(val apk: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun parseAsset(releaseJson: String): Asset? {
        val arr = JSONObject(releaseJson).getJSONArray("assets")
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val name = item.optString("name")
            if (!name.startsWith("DipHunter-GTP-") || !name.endsWith(".apk")) continue
            val run = Regex("-(\\d+)\\.apk$").find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val url = item.optString("browser_download_url")
            if (!url.startsWith("https://github.com/Strobingn/kalshi-odds-app/releases/download/")) continue
            return Asset(url, VERSION_BASE + run)
        }
        return null
    }

    suspend fun checkAndDownload(context: Context): Result = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient()
            val request = Request.Builder().url(releaseApiUrl()).header("User-Agent", "DipHunter-GTP").build()
            val release = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Release unavailable (HTTP ${response.code})")
                response.body?.string() ?: error("Empty release")
            }
            val asset = parseAsset(release) ?: error("Release has no branch APK")
            if (asset.versionCode <= BuildConfig.VERSION_CODE) return@withContext Result.Current
            val dest = File(context.cacheDir, "updates/DipHunter-GTP.apk")
            dest.parentFile?.mkdirs()
            val temp = File(dest.parentFile, "DipHunter-GTP.pending.apk")
            try {
                val get = Request.Builder().url(asset.url).header("User-Agent", "DipHunter-GTP").build()
                http.newCall(get).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
                    val body = response.body ?: error("Empty APK")
                    body.byteStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (temp.length() == 0L) error("Empty APK")
                val packageName = context.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)?.packageName
                if (packageName != context.packageName) error("Downloaded APK is for a different app")
                val flags = signingFlags()
                val installed = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, flags)
                }.getOrNull()
                val downloaded = context.packageManager.getPackageArchiveInfo(temp.absolutePath, flags)
                if (!sameSigningCertificates(certificateBytes(installed), certificateBytes(downloaded))) {
                    error("Downloaded APK signing certificate does not match this install")
                }
                if (!temp.renameTo(dest)) error("Could not save APK")
            } finally {
                temp.delete()
            }
            Result.Ready(dest)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Update check failed")
        }
    }

    /** Returns a message when the user needs to allow installs from this app. */
    fun showInstaller(context: Context, apk: File): String {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Allow updates from this app, then tap Check for app update again."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        return "Confirm the Android update prompt to keep your existing app data."
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun certificateBytes(info: PackageInfo?): List<ByteArray> {
        if (info == null) return emptyList()
        val sigs: Array<out Signature> = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: return emptyList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptyList()
        }
        return sigs.map { it.toByteArray() }
    }
}
