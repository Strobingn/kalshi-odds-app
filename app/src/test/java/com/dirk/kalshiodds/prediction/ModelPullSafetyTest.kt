package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPullSafetyTest {
    private val model = """{"kind":"logit","version":"3"}"""
    private val pkg = "com.dirk.kalshiodds.kashi"

    private fun good() = EdgeModelManifest(
        version = "3",
        trainedAt = "2026-10-05T00:00:00Z",
        nSamples = 12_000,
        nRows = 12_000,
        nMarkets = 2_500,
        nHoldout = 800,
        modelBrier = 0.16,
        marketBrier = 0.19,
        modelLogLoss = 0.48,
        marketLogLoss = 0.52,
        tag = "model-20261005",
        packageId = pkg,
        sha256 = ModelDigest.sha256Hex(model),
        beatMarketFlag = true,
        synthetic = false,
        dataSource = EdgeModelManifest.PROVENANCE_LIVE
    )

    private fun reject(m: EdgeModelManifest, raw: String = model, release: String? = "model-20261005") =
        ModelPullSafety.reject(m, raw, pkg, release)

    @Test
    fun acceptsDatedBeatMarketRealMatchingRelease() {
        assertNull(reject(good()))
    }

    @Test
    fun rejectsEdgeModelMainAndUndatedTags() {
        assertNotNull(reject(good(), release = "edge-model-main"))
        assertNotNull(reject(good().copy(tag = "edge-model-main"), release = null))
        assertNotNull(reject(good().copy(tag = "edge-model-latest"), release = null))
        assertNotNull(reject(good(), release = "model-2026105"))
        assertNotNull(reject(good(), release = "model-20261006"))
    }

    @Test
    fun rejectsMissingOrFalseBeatMarket() {
        assertTrue(reject(good().copy(beatMarketFlag = null))!!.contains("beat_market"))
        assertTrue(reject(good().copy(beatMarketFlag = false))!!.contains("beat_market"))
    }

    @Test
    fun rejectsSyntheticWrongPackageBadShaAndSmallTraining() {
        assertTrue(reject(good().copy(synthetic = true))!!.contains("synthetic"))
        assertTrue(reject(good().copy(packageId = "com.dirk.kalshiodds"))!!.contains("package"))
        assertTrue(reject(good(), raw = model + " ")!!.contains("sha256"))
        assertTrue(reject(good().copy(sha256 = ""))!!.contains("sha256"))
        assertTrue(reject(good().copy(nRows = 4_999, nSamples = 4_999))!!.contains("5000"))
        assertEquals(5_000, ModelActivation.MIN_ACTIVATE_ROWS)
    }
}
