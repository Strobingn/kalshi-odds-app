package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.AppIdentity
import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.signal.config.SignalConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Download the newest published edge model for this package.
 *
 * Looks for GitHub release tag `model-YYYYMMDD`, then
 * `ml/published/latest.json` on `kashi`. `edge-model-latest` and
 * `edge-model-main` are never activated. Package, sha256, beat-market,
 * non-synthetic, and at least 5,000 rows must all pass.
 */
data class ModelHttpResp(val code: Int, val body: String?)

class LatestModelClient(
    private val owner: String = SignalConstants.GITHUB_OWNER,
    private val repo: String = SignalConstants.GITHUB_REPO,
    private val packageId: String = AppIdentity.APPLICATION_ID,
    private val fetch: (url: String, token: String?, accept: String) -> ModelHttpResp = ::httpFetch
) {
    data class Fetch(
        val manifest: EdgeModelManifest,
        val modelJson: String,
        val model: EdgeModel,
        val decision: ModelActivationDecision
    )

    sealed class Outcome {
        data class Ready(val fetch: Fetch) : Outcome()
        data class NeedsAuth(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun download(githubToken: String? = null): Outcome {
        val token = githubToken?.trim()?.takeIf { it.isNotBlank() }
        val listed = fetch(
            PublishedRelease.releasesUrl(owner, repo),
            token,
            "application/vnd.github+json"
        )
        if (listed.code == 401 || listed.code == 403) {
            return Outcome.NeedsAuth(authMessage(listed.code))
        }
        if (listed.code in 200..299 && !listed.body.isNullOrBlank()) {
            val chosen = runCatching {
                PublishedRelease.choose(PublishedRelease.parseList(listed.body!!))
            }.getOrNull()
            if (chosen != null) {
                return loadRelease(chosen, token)
            }
        }
        val indexed = loadIndex(token)
        if (indexed != null) return indexed
        return Outcome.Failed(
            "No published model-YYYYMMDD release. edge-model-latest and edge-model-main are rejected. ${BundledEdge.NOTE}"
        )
    }

    private fun loadIndex(token: String?): Outcome? {
        val index = fetch(PublishedRelease.RAW_INDEX, token, "application/octet-stream")
        if (index.code !in 200..299 || index.body.isNullOrBlank()) return null
        val parsed = PublishedIndex.parse(index.body!!) ?: return null
        if (parsed.packageId != packageId) {
            return Outcome.Failed(
                "Published index package is '${parsed.packageId}', expected $packageId. ${BundledEdge.NOTE}"
            )
        }
        if (!parsed.beatMarket) {
            return Outcome.Failed(
                "Published index beat_market is false. ${BundledEdge.NOTE}"
            )
        }
        val modelUrl = parsed.modelLocation()
        val manifestUrl = parsed.manifestLocation()
        if (modelUrl.isNullOrBlank() || manifestUrl.isNullOrBlank()) return null
        val modelRaw = readUrl(modelUrl, token)
        val manifestRaw = readUrl(manifestUrl, token)
        if (modelRaw.isNullOrBlank() || manifestRaw.isNullOrBlank()) {
            return Outcome.Failed("Published index is missing the model files. ${BundledEdge.NOTE}")
        }
        return verify(modelRaw, manifestRaw)
    }

    private fun loadRelease(release: PublishedRelease.Release, token: String?): Outcome {
        if (release.draft) {
            return Outcome.Failed("Release ${release.tag} is a draft. ${BundledEdge.NOTE}")
        }
        val modelAsset = PublishedRelease.modelAsset(release)
        val manifestAsset = PublishedRelease.manifestAsset(release)
        if (modelAsset == null) {
            return Outcome.Failed("Release ${release.tag} has no edge_model.json asset. ${BundledEdge.NOTE}")
        }
        val modelRaw = readAsset(modelAsset, token)
        val manifestRaw = manifestAsset?.let { readAsset(it, token) }
        if (modelRaw.isNullOrBlank()) {
            return Outcome.Failed("Could not download edge_model.json from ${release.tag}. ${BundledEdge.NOTE}")
        }
        if (manifestRaw.isNullOrBlank()) {
            return Outcome.Failed("Release ${release.tag} has no manifest. ${BundledEdge.NOTE}")
        }
        return verify(modelRaw, manifestRaw, release.tag)
    }

    private fun verify(modelRaw: String, manifestRaw: String, releaseTag: String? = null): Outcome {
        val manifest = runCatching { EdgeModelManifest.parse(manifestRaw) }.getOrElse {
            return Outcome.Failed("Manifest failed validation: ${it.message}. ${BundledEdge.NOTE}")
        }
        ModelPullSafety.reject(manifest, modelRaw, packageId, releaseTag)?.let {
            return Outcome.Failed("$it. ${BundledEdge.NOTE}")
        }
        if (manifest.packageId != packageId) {
            return Outcome.Failed(
                "Model package is '${manifest.packageId}', expected $packageId. ${BundledEdge.NOTE}"
            )
        }
        if (!ModelActivation.acceptableTag(manifest.tag)) {
            return Outcome.Failed(
                "Release tag '${manifest.tag}' is not model-YYYYMMDD. ${BundledEdge.NOTE}"
            )
        }
        if (manifest.sha256.isBlank() || !ModelDigest.matches(manifest.sha256, modelRaw)) {
            return Outcome.Failed(
                "Model integrity check failed (sha256). ${BundledEdge.NOTE}"
            )
        }
        val model = runCatching { EdgeModel.parse(modelRaw) }.getOrElse {
            return Outcome.Failed("Model JSON failed validation: ${it.message}. ${BundledEdge.NOTE}")
        }
        val decision = ModelActivation.decide(manifest, modelValid = true)
        return Outcome.Ready(Fetch(manifest, modelRaw, model, decision))
    }

    private fun readAsset(asset: PublishedRelease.Asset, token: String?): String? {
        if (asset.id > 0L) {
            val api = fetch(
                PublishedRelease.assetUrl(asset.id, owner, repo),
                token,
                "application/octet-stream"
            )
            if (api.code in 200..299 && !api.body.isNullOrBlank()) return api.body
        }
        return readUrl(asset.browserUrl, token)
    }

    private fun readUrl(url: String?, token: String?): String? {
        if (url.isNullOrBlank()) return null
        val resp = fetch(url, token, "application/octet-stream")
        return resp.body.takeIf { resp.code in 200..299 && !it.isNullOrBlank() }
    }

    private fun authMessage(code: Int): String =
        "GitHub returned $code. A token with Contents: Read is needed for a private repo. " +
            "Import model JSON still works. ${BundledEdge.NOTE}"
}

object BundledEdge {
    const val NOTE =
        "Bundled DipHunter model stays active."
}

private fun httpFetch(url: String, token: String?, accept: String): ModelHttpResp {
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    val b = Request.Builder()
        .url(url)
        .header("Accept", accept)
        .header("User-Agent", NetworkModule.USER_AGENT)
    if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
    return try {
        http.newCall(b.build()).execute().use { resp ->
            ModelHttpResp(resp.code, resp.body?.string())
        }
    } catch (t: Exception) {
        ModelHttpResp(-1, t.message)
    }
}
