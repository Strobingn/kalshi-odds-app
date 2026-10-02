package com.dirk.kalshiodds.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app updates for DipHunter (Kashi) only.
 *
 * Releases are listed from [RELEASES_URL]. `/releases/latest` is never
 * used — that pointer can belong to another branch's rolling release.
 * A build is eligible only when the tag is `vX.Y.Z-debug`, GitHub says
 * it was created from branch `kashi`, and the asset is exactly
 * [ASSET_NAME].
 */
object KashiReleasePolicy {
    const val OWNER = "Strobingn"
    const val REPO = "kalshi-odds-app"
    const val BRANCH = "kashi"
    const val ASSET_NAME = "DipHunter-debug.apk"
    const val PACKAGE_ID = "com.dirk.kalshiodds.kashi"
    const val CERT_SHA256 = "64e2a43a6897c4556a36b82ea31dc89550c65e56b053658e1438bdf3c4cc4608"
    const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"

    val TAG: Regex = Regex("^v\\d+\\.\\d+\\.\\d+-debug$")

    data class Asset(val name: String, val downloadUrl: String)

    data class Release(
        val tag: String,
        val targetCommitish: String?,
        val draft: Boolean,
        val asset: Asset?
    ) {
        val versionName: String get() = tag.removePrefix("v").removeSuffix("-debug")
    }

    fun parse(json: String): List<Release> {
        val array = JSONArray(json)
        val out = ArrayList<Release>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out += parseOne(obj)
        }
        return out
    }

    fun parseOne(obj: JSONObject): Release {
        val assets = obj.optJSONArray("assets")
        var match: Asset? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name == ASSET_NAME) {
                    match = Asset(
                        name = name,
                        downloadUrl = asset.optString("browser_download_url")
                    )
                    break
                }
            }
        }
        return Release(
            tag = obj.optString("tag_name"),
            targetCommitish = obj.optString("target_commitish").takeIf { it.isNotBlank() },
            draft = obj.optBoolean("draft", false),
            asset = match
        )
    }

    fun eligible(release: Release): Boolean {
        if (release.draft) return false
        if (!TAG.matches(release.tag)) return false
        if (release.targetCommitish != BRANCH) return false
        val asset = release.asset ?: return false
        if (asset.name != ASSET_NAME) return false
        if (!allowedDownloadUrl(asset.downloadUrl)) return false
        return versionOf(release.versionName) != null
    }

    fun choose(releases: List<Release>, installedVersionName: String): Release? {
        val installed = versionOf(installedVersionName) ?: return null
        return releases
            .filter { eligible(it) }
            .filter { release ->
                val version = versionOf(release.versionName) ?: return@filter false
                compare(version, installed) > 0
            }
            .maxWithOrNull { a, b ->
                compare(versionOf(a.versionName)!!, versionOf(b.versionName)!!)
            }
    }

    fun allowedDownloadUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "github.com" ||
            host == "api.github.com" ||
            host.endsWith(".githubusercontent.com")
    }

    fun versionOf(name: String): Triple<Int, Int, Int>? {
        val parts = name.trim().removePrefix("v").removeSuffix("-debug").split(".")
        if (parts.size != 3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return Triple(nums[0], nums[1], nums[2])
    }

    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return a.first - b.first
        if (a.second != b.second) return a.second - b.second
        return a.third - b.third
    }
}

sealed class ApkInstallDecision {
    data object Allow : ApkInstallDecision()
    data class Reject(val reason: String) : ApkInstallDecision()
}

object ApkInstallGate {
    fun decide(packageName: String?, certSha256: String?): ApkInstallDecision {
        val pkg = packageName?.trim().orEmpty()
        val cert = certSha256?.trim()?.lowercase()?.replace(":", "").orEmpty()
        if (pkg != KashiReleasePolicy.PACKAGE_ID) {
            return ApkInstallDecision.Reject(
                "Refusing install — package is '$pkg', expected ${KashiReleasePolicy.PACKAGE_ID}"
            )
        }
        if (cert != KashiReleasePolicy.CERT_SHA256) {
            return ApkInstallDecision.Reject("Refusing install — signing cert does not match this Kashi build")
        }
        return ApkInstallDecision.Allow
    }
}
