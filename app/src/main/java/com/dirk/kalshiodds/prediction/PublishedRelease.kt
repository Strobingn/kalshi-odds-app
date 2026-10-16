package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.signal.config.SignalConstants
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dated edge-model releases (`model-YYYYMMDD`) and the `ml/published/latest.json` index.
 * The phone picks the newest tag that matches this package.
 */
object PublishedRelease {
    val DATED: Regex = Regex("^model-(\\d{8})$")

    const val RAW_INDEX: String =
        "https://raw.githubusercontent.com/Strobingn/kalshi-odds-app/kashi/ml/published/latest.json"
    const val RAW_ROOT: String =
        "https://raw.githubusercontent.com/Strobingn/kalshi-odds-app/kashi/"

    data class Asset(val name: String, val id: Long, val browserUrl: String)

    data class Release(val tag: String, val draft: Boolean, val assets: List<Asset>)

    fun releasesUrl(owner: String = SignalConstants.GITHUB_OWNER, repo: String = SignalConstants.GITHUB_REPO): String =
        "https://api.github.com/repos/$owner/$repo/releases?per_page=100"

    fun tagUrl(tag: String, owner: String = SignalConstants.GITHUB_OWNER, repo: String = SignalConstants.GITHUB_REPO): String =
        "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"

    fun assetUrl(id: Long, owner: String = SignalConstants.GITHUB_OWNER, repo: String = SignalConstants.GITHUB_REPO): String =
        "https://api.github.com/repos/$owner/$repo/releases/assets/$id"

    fun parseList(json: String): List<Release> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val arr = JSONArray(trimmed)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Release(
                tag = o.optString("tag_name"),
                draft = o.optBoolean("draft"),
                assets = assetsOf(o)
            )
        }
    }

    fun parseOne(json: String): Release? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = o.optString("tag_name")
        if (tag.isBlank()) return null
        return Release(tag = tag, draft = o.optBoolean("draft"), assets = assetsOf(o))
    }

    /** Newest non-draft `model-YYYYMMDD` that has both model and manifest assets. */
    fun choose(releases: List<Release>): Release? =
        releases
            .asSequence()
            .filter { !it.draft && DATED.matches(it.tag) }
            .filter { modelAsset(it) != null && manifestAsset(it) != null }
            .maxByOrNull { DATED.matchEntire(it.tag)!!.groupValues[1] }

    fun modelAsset(release: Release): Asset? =
        release.assets.firstOrNull { it.name.equals("edge_model.json", ignoreCase = true) }
            ?: release.assets.firstOrNull {
                it.name.endsWith("edge_model.json", ignoreCase = true) &&
                    !it.name.contains("manifest", ignoreCase = true)
            }

    fun manifestAsset(release: Release): Asset? =
        release.assets.firstOrNull {
            it.name.contains("manifest", ignoreCase = true) && it.name.endsWith(".json", ignoreCase = true)
        }

    fun rawFile(path: String): String = RAW_ROOT + path.removePrefix("/")

    private fun assetsOf(o: JSONObject): List<Asset> {
        val arr = o.optJSONArray("assets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            Asset(
                name = a.optString("name"),
                id = a.optLong("id"),
                browserUrl = a.optString("browser_download_url")
            )
        }
    }
}

data class PublishedIndex(
    val tag: String,
    val packageId: String,
    val beatMarket: Boolean,
    val sha256: String,
    val modelUrl: String?,
    val manifestUrl: String?,
    val modelPath: String?,
    val manifestPath: String?
) {
    fun modelLocation(): String? = modelUrl?.takeIf { it.isNotBlank() }
        ?: modelPath?.takeIf { it.isNotBlank() }?.let { PublishedRelease.rawFile(it) }

    fun manifestLocation(): String? = manifestUrl?.takeIf { it.isNotBlank() }
        ?: manifestPath?.takeIf { it.isNotBlank() }?.let { PublishedRelease.rawFile(it) }

    companion object {
        fun parse(raw: String): PublishedIndex? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val tag = o.optString("tag")
            if (!PublishedRelease.DATED.matches(tag)) return null
            return PublishedIndex(
                tag = tag,
                packageId = o.optString("package"),
                beatMarket = o.optBoolean("beat_market", false),
                sha256 = o.optString("sha256"),
                modelUrl = o.optString("model_url").ifBlank { null },
                manifestUrl = o.optString("manifest_url").ifBlank { null },
                modelPath = o.optString("model_path").ifBlank { null },
                manifestPath = o.optString("manifest_path").ifBlank { null }
            )
        }
    }
}
