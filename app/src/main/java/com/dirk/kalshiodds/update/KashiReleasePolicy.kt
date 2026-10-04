package com.dirk.kalshiodds.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app updates for DipHunter (Kashi) only.
 *
 * Releases are listed from [RELEASES_URL]. `/releases/latest` is never
 * used — that pointer can belong to another branch's rolling release.
 * A build is eligible when the tag is `vX.Y.Z-debug`, the release notes
 * contain the exact line [APP_ID_MARKER], and the asset is exactly
 * [ASSET_NAME]. A higher tag from another branch without that line is
 * ignored and must not block a real Kashi update. GitHub's
 * target_commitish is a commit SHA, not a branch name, so it is not
 * used as a filter. The download URL is always the canonical release
 * asset, never an arbitrary browser_download_url.
 */
object KashiReleasePolicy {
    const val OWNER = "Strobingn"
    const val REPO = "kalshi-odds-app"
    const val BRANCH = "kashi"
    const val ASSET_NAME = "DipHunter-debug.apk"
    const val PACKAGE_ID = "com.dirk.kalshiodds.kashi"
    const val CERT_SHA256 = "64e2a43a6897c4556a36b82ea31dc89550c65e56b053658e1438bdf3c4cc4608"
    const val APP_ID_MARKER = "app-id: com.dirk.kalshiodds.kashi"
    const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"

    val TAG: Regex = Regex("^v\\d+\\.\\d+\\.\\d+-debug$")

    fun assetUrl(tag: String): String =
        "https://github.com/$OWNER/$REPO/releases/download/$tag/$ASSET_NAME"

    data class Asset(val name: String, val downloadUrl: String)

    data class Release(
        val tag: String,
        val targetCommitish: String?,
        val draft: Boolean,
        val asset: Asset?,
        val notes: String = ""
    ) {
        val versionName: String get() = tag.removePrefix("v").removeSuffix("-debug")
    }

    fun hasAppIdMarker(notes: String?): Boolean =
        notes.orEmpty().lineSequence().any { it.trim() == APP_ID_MARKER }

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
                    val tag = obj.optString("tag_name")
                    match = Asset(name = name, downloadUrl = assetUrl(tag))
                    break
                }
            }
        }
        return Release(
            tag = obj.optString("tag_name"),
            targetCommitish = obj.optString("target_commitish").takeIf { it.isNotBlank() },
            draft = obj.optBoolean("draft", false),
            asset = match,
            notes = obj.optString("body")
        )
    }

    fun eligible(release: Release): Boolean {
        if (release.draft) return false
        if (!hasAppIdMarker(release.notes)) return false
        if (!TAG.matches(release.tag)) return false
        val asset = release.asset ?: return false
        if (asset.downloadUrl != assetUrl(release.tag)) return false
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

    fun compareVersions(a: String, b: String): Int? {
        val left = versionOf(a) ?: return null
        val right = versionOf(b) ?: return null
        return compare(left, right)
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

/**
 * Compares a downloaded APK (package + signing cert from
 * PackageManager.getPackageArchiveInfo) with the app that is installed.
 */
object InstalledApkCheck {
    const val REFUSE_PACKAGE =
        "Refusing install — this APK is not Bitcoin Kalshi (com.dirk.kalshiodds.kashi)."
    const val REFUSE_CERT =
        "Refusing install — the APK is not signed with this app's certificate."
    const val REFUSE_UNREADABLE =
        "Refusing install — could not read the APK package or its signing certificate."

    fun decide(
        archivePackage: String?,
        archiveCertSha256: String?,
        installedPackage: String,
        installedCertSha256: String?
    ): ApkInstallDecision {
        val pkg = archivePackage?.trim().orEmpty()
        val cert = archiveCertSha256?.trim()?.lowercase()?.replace(":", "").orEmpty()
        val installedPkg = installedPackage.trim()
        val installedCert = installedCertSha256?.trim()?.lowercase()?.replace(":", "").orEmpty()
        if (pkg.isEmpty() || cert.isEmpty() || installedCert.isEmpty()) {
            return ApkInstallDecision.Reject(REFUSE_UNREADABLE)
        }
        if (pkg != KashiReleasePolicy.PACKAGE_ID || pkg != installedPkg) {
            return ApkInstallDecision.Reject(REFUSE_PACKAGE)
        }
        if (cert != installedCert) {
            return ApkInstallDecision.Reject(REFUSE_CERT)
        }
        return ApkInstallDecision.Allow
    }
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
