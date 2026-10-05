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

/** Checks this build's rolling release and hands a verified package to Android. */
object AppUpdater {
    const val DOWNLOAD_PREFIX =
        "https://github.com/Strobingn/kalshi-odds-app/releases/download/"

    fun releaseTag(): String = BuildConfig.UPDATE_RELEASE_TAG

    fun releaseApiUrl(tag: String = releaseTag()): String =
        "https://api.github.com/repos/Strobingn/kalshi-odds-app/releases/tags/$tag"

    fun acceptsDownloadUrl(url: String): Boolean = url.startsWith(DOWNLOAD_PREFIX)

    /** True only when both sides have certificates and the SHA-256 sets match. */
    fun sameSigningCertificates(installed: List<ByteArray>, downloaded: List<ByteArray>): Boolean {
        if (installed.isEmpty() || downloaded.isEmpty()) return false
        return certificateSha256(installed) == certificateSha256(downloaded)
    }

    fun certificateSha256(certs: List<ByteArray>): Set<String> = certs.map { sha256(it) }.toSet()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    data class Asset(val url: String, val versionCode: Int, val applicationId: String?)

    sealed class Pick {
        data class Update(val asset: Asset) : Pick()
        /** Another app's release. Do not offer it and do not treat it as "already current". */
        data object ForeignApp : Pick()
        data object None : Pick()
    }

    sealed class Result {
        data object Current : Result()
        data class Skipped(val message: String) : Result()
        data class Ready(val apk: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    const val SKIPPED_OTHER_APP =
        "Skipped a release for another app; you're on the latest grokbot build."

    /** Null means the caller should download. A foreign package is skipped, not "up to date". */
    fun decide(pick: Pick): Result? = when (pick) {
        is Pick.ForeignApp -> Result.Skipped(SKIPPED_OTHER_APP)
        is Pick.None -> null
        is Pick.Update -> when {
            pick.asset.applicationId != null && pick.asset.applicationId != BuildConfig.APPLICATION_ID ->
                Result.Skipped(SKIPPED_OTHER_APP)
            pick.asset.versionCode <= BuildConfig.VERSION_CODE -> Result.Current
            else -> null
        }
    }

    fun parseAsset(releaseJson: String): Asset? = when (val pick = pickRelease(releaseJson)) {
        is Pick.Update -> pick.asset
        else -> null
    }

    /**
     * The rolling tag is the only release the updater reads. A body
     * `applicationId` that is not this app is skipped entirely, so a higher
     * version from another branch cannot block the next real update.
     */
    fun pickRelease(releaseJson: String): Pick {
        val root = runCatching { JSONObject(releaseJson) }.getOrNull() ?: return Pick.None
        val body = root.optString("body")
        val bodyApp = bodyField(body, "applicationId")
        val bodyCode = bodyField(body, "versionCode")?.toIntOrNull()
        if (bodyApp != null && bodyApp != BuildConfig.APPLICATION_ID) return Pick.ForeignApp
        val arr = root.optJSONArray("assets") ?: return Pick.None
        var fallback: Asset? = null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val url = item.optString("browser_download_url")
            if (!name.endsWith(".apk")) continue
            if (!acceptsDownloadUrl(url)) continue
            val code = bodyCode ?: runNumber(name)?.let { BuildConfig.VERSION_CODE_BASE + it } ?: continue
            val asset = Asset(url, code, bodyApp ?: BuildConfig.APPLICATION_ID)
            if (name == BuildConfig.UPDATE_ASSET_NAME) return Pick.Update(asset)
            if (fallback == null && name.startsWith("DipHunter")) fallback = asset
        }
        return fallback?.let { Pick.Update(it) } ?: Pick.None
    }

    suspend fun checkAndDownload(context: Context): Result = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient()
            val request = Request.Builder().url(releaseApiUrl()).header("User-Agent", "DipHunter-GTP").build()
            val release = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Release unavailable (HTTP ${response.code})")
                response.body?.string() ?: error("Empty release")
            }
            val pick = pickRelease(release)
            decide(pick)?.let { return@withContext it }
            val asset = (pick as? Pick.Update)?.asset ?: error("Release has no branch APK")
            if (!acceptsDownloadUrl(asset.url)) error("Download URL is not this repository")
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
                if (packageName != context.packageName) {
                    temp.delete()
                    return@withContext Result.Skipped(SKIPPED_OTHER_APP)
                }
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

    internal fun bodyField(body: String, key: String): String? =
        Regex("""(?:^|\n)\s*$key\s*[:=]\s*(\S+)""").find(body)?.groupValues?.get(1)

    internal fun runNumber(name: String): Int? =
        Regex("-(\\d+)\\.apk$").find(name)?.groupValues?.get(1)?.toIntOrNull()

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
