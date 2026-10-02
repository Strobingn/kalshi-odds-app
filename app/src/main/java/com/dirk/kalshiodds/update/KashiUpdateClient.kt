package com.dirk.kalshiodds.update

import com.dirk.kalshiodds.data.api.KalshiRequestStatus
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class UpdateCheck {
    data object UpToDate : UpdateCheck()
    data class Available(val release: KashiReleasePolicy.Release) : UpdateCheck()
    data class Failed(val message: String) : UpdateCheck()
}

/**
 * Lists GitHub releases and keeps only Kashi `v*-debug` builds.
 * Download bytes are checked for package id + cert before install.
 */
class KashiUpdateClient(
    private val fetchText: (String) -> String,
    private val fetchBytes: (String) -> ByteArray = { throw IOException("download not configured") }
) {
    fun check(installedVersionName: String): UpdateCheck {
        val body = try {
            fetchText(KashiReleasePolicy.RELEASES_URL)
        } catch (e: Exception) {
            return UpdateCheck.Failed(KalshiRequestStatus.message(e, 0L))
        }
        if (!KashiReleasePolicy.RELEASES_URL.contains("/releases?") ||
            KashiReleasePolicy.RELEASES_URL.contains("/releases/latest")
        ) {
            return UpdateCheck.Failed("Update check refused — wrong release endpoint")
        }
        val offer = try {
            KashiReleasePolicy.choose(KashiReleasePolicy.parse(body), installedVersionName)
        } catch (e: Exception) {
            return UpdateCheck.Failed("Update check failed")
        }
        return if (offer == null) UpdateCheck.UpToDate else UpdateCheck.Available(offer)
    }

    fun downloadVerified(release: KashiReleasePolicy.Release): DownloadResult {
        if (!KashiReleasePolicy.eligible(release)) {
            return DownloadResult.Rejected("Release is not a Kashi v*-debug build")
        }
        val url = release.asset?.downloadUrl.orEmpty()
        if (!KashiReleasePolicy.allowedDownloadUrl(url)) {
            return DownloadResult.Rejected("Refusing download host")
        }
        val bytes = try {
            fetchBytes(url)
        } catch (e: Exception) {
            return DownloadResult.Failed(KalshiRequestStatus.message(e, 0L))
        }
        val facts = ApkArchiveInspector.inspect(bytes)
        return when (val gate = ApkInstallGate.decide(facts.packageName, facts.certSha256)) {
            ApkInstallDecision.Allow -> DownloadResult.Verified(bytes, facts)
            is ApkInstallDecision.Reject -> DownloadResult.Rejected(gate.reason)
        }
    }

    companion object {
        fun http(): KashiUpdateClient {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            fun get(url: String, accept: String): okhttp3.Response {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", accept)
                    .header("User-Agent", "DipHunter-Kashi")
                    .build()
                return client.newCall(request).execute()
            }
            return KashiUpdateClient(
                fetchText = { url ->
                    get(url, "application/vnd.github+json").use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.string().orEmpty()
                    }
                },
                fetchBytes = { url ->
                    get(url, "application/vnd.android.package-archive").use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.bytes() ?: throw IOException("empty apk")
                    }
                }
            )
        }
    }
}

sealed class DownloadResult {
    data class Verified(val bytes: ByteArray, val facts: ApkArchiveInspector.Facts) : DownloadResult()
    data class Rejected(val reason: String) : DownloadResult()
    data class Failed(val message: String) : DownloadResult()
}
