package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongshotAndScorecardTest {
    @Test
    fun bundledLongshotAssetIsSchemaOneAndValidatesNoLongshot() {
        val f = listOf("src/main/assets/longshot_residual.json", "app/src/main/assets/longshot_residual.json")
            .map { java.io.File(it) }.first { it.isFile }
        val m = LongshotResidual.parse(f.readText())
        assertNotNull(m)
        assertEquals(1, m!!.schemaVersion)
        assertFalse(m.synthetic)
        assertTrue(m.buckets.size >= 3)
        // Real-data export (2026-10-06): no sub-15c bucket clears net_ci_lo > 0 after fees.
        listOf(0.02, 0.07, 0.12).forEach { assertFalse("price $it", m.validated(it)) }
    }

    private val json = """
        {"schema_version":1,"version":"flb-test","min_n":300,"synthetic":false,
         "buckets":[
           {"lo":0.0,"hi":0.1,"n":5000,"price_mean":0.05,"win_rate":0.03,"residual":-0.02,"net_per_contract":-0.03,"net_ci_lo":-0.04,"net_ci_hi":-0.02},
           {"lo":0.1,"hi":0.2,"n":4000,"price_mean":0.15,"win_rate":0.17,"residual":0.02,"net_per_contract":0.01,"net_ci_lo":0.002,"net_ci_hi":0.02},
           {"lo":0.9,"hi":1.0,"n":100,"price_mean":0.95,"win_rate":0.97,"residual":0.02,"net_per_contract":0.01,"net_ci_lo":0.001,"net_ci_hi":0.02}
         ]}
    """.trimIndent()

    @Test
    fun parsesSchemaAndValidatesOnlyPositiveFeeAwareCi() {
        val m = LongshotResidual.parse(json)!!
        assertEquals(1, m.schemaVersion)
        assertFalse(m.validated(0.05))
        assertTrue(m.validated(0.12))
        assertFalse("n below min_n", m.validated(0.95))
        assertEquals(-0.02, m.residual(0.05)!!, 1e-12)
    }

    @Test
    fun rejectsWrongSchemaAndSynthetic() {
        assertNull(LongshotResidual.parse(json.replace("\"schema_version\":1", "\"schema_version\":2")))
        val synth = LongshotResidual.parse(json.replace("\"synthetic\":false", "\"synthetic\":true"))!!
        assertFalse(synth.validated(0.12))
        assertFalse(LongshotResidualModel.EMPTY.validated(0.12))
    }

    @Test
    fun honestScorecardExcludesTopWinAndSeparatesSubTenCents() {
        val rows = listOf(
            HonestScorecard.Row(0.05, 19.0, 1.0, "A"),
            HonestScorecard.Row(0.60, 6.0, 10.0, "B"),
            HonestScorecard.Row(0.60, -10.0, 10.0, "C"),
            HonestScorecard.Row(0.92, 0.8, 10.0, "D"),
            HonestScorecard.Row(0.92, 0.8, 10.0, "E")
        )
        val v = HonestScorecard.of(rows)
        assertEquals(16.6, v.rawPnlUsd, 1e-9)
        assertEquals(-2.4, v.scoredPnlUsd, 1e-9)
        assertEquals(6.0, v.biggestWinUsd, 1e-9)
        assertEquals(-8.4, v.excludingBiggestWinUsd, 1e-9)
        assertEquals(1, v.longshotCount)
        assertEquals(19.0, v.longshotUnscoredUsd, 1e-9)
        assertNotNull(v.roiPerDollar)
        assertNotNull(v.roiCi95)
        assertTrue(v.byPrice.first().label.contains("10"))
        assertTrue(HonestScorecard.lines(v).any { it.contains("Excluding the top win") })
    }

    @Test
    fun fullHistoryMergeHasNoTruncation() {
        val persisted = (0 until 500).map { "f$it" to HonestScorecard.Row(0.5, 1.0, 5.0, "M$it") }
        val v = HonestScorecard.fromHistory(persisted, emptyList())
        assertEquals(500, v.scoredCount)
    }
}
