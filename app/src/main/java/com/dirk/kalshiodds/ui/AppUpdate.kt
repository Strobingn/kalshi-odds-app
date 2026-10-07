package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-app update check for the Mis_bitcoin-branch build.
 *
 * Every push to `Mis_bitcoin` runs the Build APK workflow, which publishes the
 * APK on a rolling GitHub prerelease tagged `v<versionName>-Mis_bitcoin` with an
 * asset named `MisBitcoin-v<versionName>-<branch>-<run number>.apk`. The build
 * stamps the same run number into [BuildConfig.CI_RUN_NUMBER], so "is there
 * a newer build?" is a comparison of run numbers. Every CI APK is signed
 * with the committed debug keystore, so the new one installs over this one
 * and keeps the saved Kalshi key.
 *
 * Reads the public GitHub releases list (no token). Downloading is handed
 * to the browser; nothing is installed without the Android install prompt.
 */
object AppUpdate {

    const val RELEASES_URL = "https://api.github.com/repos/Strobingn/kalshi-odds-app/releases?per_page=30"
    const val RELEASES_PAGE = "https://github.com/Strobingn/kalshi-odds-app/releases"
    const val BRANCH = "Mis_bitcoin"

    private val TAG = Regex("""^v(\d+(?:\.\d+)*(?:-[A-Za-z0-9.]+)?)-(.+)$""")
    private val ASSET_RUN = Regex("""-(\d+)\.apk$""")
    private val BODY_RUN = Regex("""run #(\d+)""")
    private val BODY_CODE = Regex("""\((\d+)\)""")
    private val BODY_SHA = Regex("""@ ([0-9a-f]{7,40})""")

    data class Release(
        val tag: String,
        val versionName: String,
        /** Build APK run number; 0 when it cannot be read. */
        val runNumber: Int,
        /** versionCode from the release notes; null when missing. */
        val versionCode: Int?,
        val sha: String?,
        val apkUrl: String,
        val apkName: String,
        val pageUrl: String?,
        val publishedAt: String?
    ) {
        val label: String
            get() = "v$versionName" + (if (runNumber > 0) " build #$runNumber" else "")
    }

    sealed class Status {
        data object Idle : Status()
        data object Checking : Status()
        data class UpToDate(val latest: Release) : Status()
        data class Available(val latest: Release) : Status()
        data class Failed(val reason: String) : Status()
    }

    /** `Build #57 · abc1234`, or `Local build` when not built by CI. */
    fun buildLabel(runNumber: Int = BuildConfig.CI_RUN_NUMBER, sha: String = BuildConfig.GIT_SHA): String =
        if (runNumber > 0) "Build #$runNumber · $sha" else "Local build (not from GitHub)"

    /** Releases of [branch] that carry an APK, parsed from the GitHub releases JSON. */
    fun parse(json: String, branch: String = BRANCH): List<Release> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.bool("draft") == true) return@mapNotNull null
            val tag = o.string("tag_name") ?: return@mapNotNull null
            val m = TAG.matchEntire(tag) ?: return@mapNotNull null
            if (m.groupValues[2] != branch) return@mapNotNull null
            val asset = (o["assets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("name")?.endsWith(".apk", ignoreCase = true) == true }
                ?: return@mapNotNull null
            val apkName = asset.string("name") ?: return@mapNotNull null
            val apkUrl = asset.string("browser_download_url")?.takeIf { it.startsWith("https://") }
                ?: return@mapNotNull null
            val body = o.string("body").orEmpty()
            Release(
                tag = tag,
                versionName = m.groupValues[1],
                runNumber = ASSET_RUN.find(apkName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: BODY_RUN.find(body)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0,
                versionCode = BODY_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull(),
                sha = BODY_SHA.find(body)?.groupValues?.get(1),
                apkUrl = apkUrl,
                apkName = apkName,
                pageUrl = o.string("html_url"),
                publishedAt = o.string("published_at")
            )
        }
    }

    /** Newest build: highest run number, then highest versionCode. */
    fun latest(releases: List<Release>): Release? =
        releases.maxWithOrNull(compareBy<Release> { it.runNumber }.thenBy { it.versionCode ?: 0 })

    /**
     * Compare [latest] with this install. CI builds compare run numbers; a
     * local build (run 0) can only compare versionCode.
     */
    fun decide(
        latest: Release?,
        currentRun: Int = BuildConfig.CI_RUN_NUMBER,
        currentCode: Int = BuildConfig.VERSION_CODE
    ): Status {
        latest ?: return Status.Failed("No $BRANCH build with an APK was found on GitHub")
        val newer = when {
            currentRun > 0 && latest.runNumber > 0 -> latest.runNumber > currentRun
            latest.versionCode != null -> latest.versionCode > currentCode
            else -> false
        }
        return if (newer) Status.Available(latest) else Status.UpToDate(latest)
    }

    fun statusLine(status: Status): String = when (status) {
        Status.Idle -> "Not checked yet"
        Status.Checking -> "Checking GitHub…"
        is Status.UpToDate -> "Up to date. Newest on GitHub: ${status.latest.label}"
        is Status.Available -> "Update available: ${status.latest.label}"
        is Status.Failed -> "Could not check: ${status.reason}"
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        string(key)?.toBooleanStrictOrNull()
}

/** One GET of the public releases list. No token, no retries. */
class AppUpdateChecker(
    private val fetch: suspend () -> String = { httpGet(AppUpdate.RELEASES_URL) }
) {
    suspend fun check(): AppUpdate.Status =
        runCatching { AppUpdate.decide(AppUpdate.latest(AppUpdate.parse(fetch()))) }
            .getOrElse { AppUpdate.Status.Failed(it.message?.take(160) ?: it.javaClass.simpleName) }

    companion object {
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MisBitcoin/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        if (response.code == 403 || response.code == 429) {
                            "GitHub rate limit (HTTP ${response.code}). Try again in a few minutes"
                        } else {
                            "GitHub answered HTTP ${response.code}"
                        }
                    )
                }
                response.body?.string() ?: throw IllegalStateException("Empty answer from GitHub")
            }
        }
    }
}
