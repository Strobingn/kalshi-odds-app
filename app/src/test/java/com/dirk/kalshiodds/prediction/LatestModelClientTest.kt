package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestModelClientTest {

    private val model = """{"version":2,"kind":"logistic","feature_names":["dist_to_strike_vol","tte_frac","market_mid","imbalance","spread","momentum","realized_vol","cross_asset","time_of_day","digital_fair"],"weights":[0.1,0,0,0,0,0,0,0,0,0.2],"bias":0.0,"mean":[0,0,0,0,0,0,0,0,0,0],"std":[1,1,1,1,1,1,1,1,1,1],"platt_a":1.0,"platt_b":0.0,"blend_weight":0.35,"fee_margin":0.07,"confidence_margin":0.03}"""

    private fun manifest(
        sha: String = ModelDigest.sha256Hex(model),
        pkg: String = "com.dirk.kalshiodds.kashi",
        beat: Boolean = true,
        tag: String = "model-20261005",
        modelBrier: Double = 0.16,
        simPnl: Double = 12.5,
        marketPnl: Double = -3.0
    ): String = """
        {
          "version": "2",
          "trained_at": "2026-10-05T08:17:00Z",
          "package": "$pkg",
          "sha256": "$sha",
          "tag": "$tag",
          "n_samples": 12000,
          "n_rows": 12000,
          "n_markets": 2500,
          "n_holdout": 800,
          "model_brier": $modelBrier,
          "market_brier": 0.186,
          "model_logloss": 0.48,
          "market_logloss": 0.52,
          "sim_pnl": $simPnl,
          "sim_trades": 40,
          "market_pnl": $marketPnl,
          "beat_market": $beat,
          "synthetic": false,
          "data_source": "kalshi_settled_coinbase_spot_v1"
        }
    """.trimIndent()

    private fun asset(name: String, id: Long): String =
        """{"name":"$name","id":$id,"browser_download_url":"https://example.test/$name"}"""

    private fun release(tag: String, draft: Boolean, modelId: Long, manifestId: Long): String =
        """{"tag_name":"$tag","draft":$draft,"assets":[${asset("edge_model.json", modelId)},${asset("edge_model_manifest.json", manifestId)}]}"""

    private fun client(routes: Map<String, Pair<Int, String>>): LatestModelClient =
        LatestModelClient { url, _, _ ->
            val hit = routes[url]
            if (hit == null) ModelHttpResp(404, "")
            else ModelHttpResp(hit.first, hit.second)
        }

    private fun releasesUrl() = PublishedRelease.releasesUrl()

    @Test
    fun choosesNewestDatedReleaseAndActivatesWhenItBeatsMarket() {
        val routes = mapOf(
            releasesUrl() to (200 to """[
              ${release("model-20261001", false, 21, 22)},
              ${release("model-20261006", true, 31, 32)},
              ${release("v0.3.33-debug", false, 41, 42)},
              ${release("model-20261005", false, 11, 12)}
            ]"""),
            PublishedRelease.assetUrl(11) to (200 to model),
            PublishedRelease.assetUrl(12) to (200 to manifest(tag = "model-20261005")),
            PublishedRelease.assetUrl(21) to (200 to model),
            PublishedRelease.assetUrl(22) to (200 to manifest(tag = "model-20261001"))
        )
        val out = client(routes).download(null)
        assertTrue(out is LatestModelClient.Outcome.Ready)
        val ready = out as LatestModelClient.Outcome.Ready
        assertEquals("model-20261005", ready.fetch.manifest.tag)
        assertTrue(ready.fetch.decision.activate)
        assertEquals("com.dirk.kalshiodds.kashi", ready.fetch.manifest.packageId)
    }

    @Test
    fun rejectsShaMismatch() {
        val routes = mapOf(
            releasesUrl() to (200 to "[${release("model-20261005", false, 11, 12)}]"),
            PublishedRelease.assetUrl(11) to (200 to model),
            PublishedRelease.assetUrl(12) to (200 to manifest(sha = "ab".repeat(32)))
        )
        val out = client(routes).download(null)
        assertTrue(out is LatestModelClient.Outcome.Failed)
        assertTrue((out as LatestModelClient.Outcome.Failed).message.contains("sha256"))
        assertTrue(out.message.contains("Bundled"))
    }

    @Test
    fun rejectsOtherPackage() {
        val routes = mapOf(
            releasesUrl() to (200 to "[${release("model-20261005", false, 11, 12)}]"),
            PublishedRelease.assetUrl(11) to (200 to model),
            PublishedRelease.assetUrl(12) to (200 to manifest(pkg = "com.dirk.kalshiodds"))
        )
        val out = client(routes).download(null)
        assertTrue(out is LatestModelClient.Outcome.Failed)
        assertTrue((out as LatestModelClient.Outcome.Failed).message.contains("package"))
    }

    @Test
    fun doesNotActivateWhenModelLosesToMarket() {
        val routes = mapOf(
            releasesUrl() to (200 to "[${release("model-20261005", false, 11, 12)}]"),
            PublishedRelease.assetUrl(11) to (200 to model),
            PublishedRelease.assetUrl(12) to (
                200 to manifest(beat = false, modelBrier = 0.30, simPnl = -4.0, marketPnl = 2.0)
            )
        )
        val out = client(routes).download(null)
        assertTrue(out is LatestModelClient.Outcome.Ready)
        val ready = out as LatestModelClient.Outcome.Ready
        assertFalse(ready.fetch.decision.activate)
        assertTrue(ready.fetch.decision.reason.contains("does not beat"))
    }

    @Test
    fun fallsBackToPublishedIndexOnKashi() {
        val modelUrl = "https://github.com/Strobingn/kalshi-odds-app/releases/download/model-20261005/edge_model.json"
        val manifestUrl = "https://github.com/Strobingn/kalshi-odds-app/releases/download/model-20261005/edge_model_manifest.json"
        val index = """
            {
              "tag": "model-20261005",
              "package": "com.dirk.kalshiodds.kashi",
              "beat_market": true,
              "sha256": "${ModelDigest.sha256Hex(model)}",
              "model_url": "$modelUrl",
              "manifest_url": "$manifestUrl"
            }
        """.trimIndent()
        val routes = mapOf(
            releasesUrl() to (200 to "[]"),
            PublishedRelease.RAW_INDEX to (200 to index),
            modelUrl to (200 to model),
            manifestUrl to (200 to manifest())
        )
        val out = client(routes).download(null)
        assertTrue(out is LatestModelClient.Outcome.Ready)
        assertTrue((out as LatestModelClient.Outcome.Ready).fetch.decision.activate)
    }

    @Test
    fun indexThatLosesIsNotUsed() {
        val index = """
            {"tag":"model-20261005","package":"com.dirk.kalshiodds.kashi","beat_market":false,"sha256":"abc"}
        """.trimIndent()
        val routes = mapOf(
            releasesUrl() to (200 to "[]"),
            PublishedRelease.RAW_INDEX to (200 to index)
        )
        val out = client(routes).download(null)
        assertTrue(out is LatestModelClient.Outcome.Failed)
        assertTrue((out as LatestModelClient.Outcome.Failed).message.contains("beat_market"))
    }

    @Test
    fun missingReleaseKeepsBundledModel() {
        val routes = mapOf(
            releasesUrl() to (200 to "[]"),
            PublishedRelease.RAW_INDEX to (404 to ""),
            PublishedRelease.tagUrl("edge-model-latest") to (404 to "")
        )
        val out = client(routes).download(null)
        assertTrue(out is LatestModelClient.Outcome.Failed)
        assertTrue((out as LatestModelClient.Outcome.Failed).message.contains("Bundled"))
    }
}
