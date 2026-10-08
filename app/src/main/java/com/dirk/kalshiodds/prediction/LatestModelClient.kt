package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.signal.config.SignalConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Download the rolling [SignalConstants.EDGE_MODEL_RELEASE_TAG] release
 * (`grok-bitcoin-edge-model`: manifest + model JSON).
 * Public repos work without a token. Private repos need a GitHub token
 * (classic `repo` or fine-grained Contents: Read) — never the Kalshi key.
 */
class LatestModelClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val owner: String = SignalConstants.GITHUB_OWNER,
    private val repo: String = SignalConstants.GITHUB_REPO,
    private val tag: String = SignalConstants.EDGE_MODEL_RELEASE_TAG
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
        val release = get(
            url = "https://api.github.com/repos/$owner/$repo/releases/tags/$tag",
            token = token,
            accept = "application/vnd.github+json"
        )
        if (release.code == 404 || release.code == 401 || release.code == 403) {
            return Outcome.NeedsAuth(
                when (release.code) {
                    404 ->
                        "Release `$tag` is missing or the repo is private. " +
                            "Paste a GitHub token (repo read) below, or use Import model JSON."
                    else ->
                        "GitHub returned ${release.code}. A token with Contents: Read is needed " +
                            "for a private repo. Import model JSON still works without one."
                }
            )
        }
        if (release.code !in 200..299 || release.body.isNullOrBlank()) {
            return Outcome.Failed("Could not read `$tag` (HTTP ${release.code}). Try Import model JSON.")
        }
        val assets = runCatching {
            val arr = JSONObject(release.body).optJSONArray("assets")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
        }.getOrElse { emptyList() }
        val manifestAsset = assets.firstOrNull { o ->
            o.optString("name").contains("manifest", ignoreCase = true)
        }
        val modelAsset = assets.firstOrNull { o ->
            val n = o.optString("name")
            n.endsWith("edge_model.json", true) || n == "edge_model.json"
        } ?: assets.firstOrNull { o ->
            o.optString("name").endsWith(".json") &&
                !o.optString("name").contains("manifest", true)
        }
        val manifestRaw = when {
            manifestAsset != null -> downloadAsset(manifestAsset.optLong("id"), token)
            else -> null
        }
        val modelRaw = when {
            modelAsset != null -> downloadAsset(modelAsset.optLong("id"), token)
            else -> publicFallback("edge_model.json", token)
        }
        if (modelRaw.isNullOrBlank()) {
            return Outcome.Failed("Release has no edge_model.json asset.")
        }
        val model = runCatching { EdgeModel.parse(modelRaw) }.getOrElse {
            return Outcome.Failed("Model JSON failed validation: ${it.message}")
        }
        val manifest = when {
            !manifestRaw.isNullOrBlank() -> runCatching { EdgeModelManifest.parse(manifestRaw) }.getOrNull()
            else -> null
        } ?: runCatching {
            EdgeModelManifest.fromModelMetrics(
                model,
                trainedAt = JSONObject(release.body!!).optString("published_at").ifBlank {
                    java.time.Instant.now().toString()
                }
            )
        }.getOrElse {
            return Outcome.Failed("Could not build a manifest from the model: ${it.message}")
        }
        val decision = ModelActivation.decide(manifest, modelValid = true)
        return Outcome.Ready(Fetch(manifest, modelRaw, model, decision))
    }

    private fun downloadAsset(id: Long, token: String?): String? {
        if (id <= 0L) return null
        val resp = get(
            url = "https://api.github.com/repos/$owner/$repo/releases/assets/$id",
            token = token,
            accept = "application/octet-stream"
        )
        return resp.body.takeIf { resp.code in 200..299 }
    }

    private fun publicFallback(name: String, token: String?): String? {
        val resp = get(
            url = "https://github.com/$owner/$repo/releases/download/$tag/$name",
            token = token,
            accept = "application/octet-stream"
        )
        return resp.body.takeIf { resp.code in 200..299 }
    }

    private fun get(url: String, token: String?, accept: String): Resp {
        val b = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", NetworkModule.USER_AGENT)
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        return try {
            http.newCall(b.build()).execute().use { resp ->
                Resp(resp.code, resp.body?.string())
            }
        } catch (t: Exception) {
            Resp(-1, t.message)
        }
    }

    private data class Resp(val code: Int, val body: String?)
}
