package com.dirk.kalshiodds.prediction

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class EdgeFeaturesGoldenTest {

    @Test
    fun sharedGoldenVectorMatchesPython() {
        val raw = JSONObject(golden().readText())
        val closes = raw.getJSONArray("coinbase_closes").let { a ->
            (0 until a.length()).map { a.getDouble(it) }
        }
        val got = EdgeFeatures.build(
            EdgeFeatures.Raw(
                spot = raw.getDouble("spot"),
                strike = raw.getDouble("strike"),
                tteSeconds = raw.getDouble("tte_seconds"),
                sigmaAnnual = raw.getDouble("sigma_annual"),
                marketMid = raw.getDouble("market_mid"),
                imbalance = raw.getDouble("imbalance"),
                spread = raw.getDouble("spread"),
                crossAssetRet = raw.getDouble("cross_asset"),
                nowMs = raw.getLong("end_ts") * 1000L,
                coinbaseCloses = closes
            )
        )
        val expected = raw.getJSONArray("expected")
        assertEquals(expected.length(), got.size)
        for (i in got.indices) {
            assertEquals(
                "feature ${EdgeFeatures.NAMES[i]}",
                expected.getDouble(i),
                got[i].toDouble(),
                1e-5
            )
        }
    }

    @Test
    fun timeOfDayIsUtcHourNotNewYork() {
        // 2026-09-20T18:00:00Z is 14:00 America/New_York (EDT).
        val ts = 1_779_357_600_000L // will be asserted via Calendar
        val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.SEPTEMBER, 20, 18, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val ny = EdgeFeatures.timeOfDayFrac(utc, TimeZone.getTimeZone("America/New_York"))
        val got = EdgeFeatures.timeOfDayFrac(utc)
        assertEquals(18.0 / 24.0, got.toDouble(), 1e-6)
        assertTrue("NY would be 14/24=$ny; UTC must be 18/24=$got", kotlin.math.abs(got - ny) > 0.05)
    }

    @Test
    fun candleWindowMatchesTrainerFormula() {
        val closes = listOf(100000.0, 100020.0, 100040.0, 100080.0, 100100.0, 100140.0, 100180.0, 100200.0)
        val w = EdgeFeatures.candleWindow(closes)!!
        assertEquals(0.002, w.momentum, 1e-9)
        val mu = closes.average()
        val sd = kotlin.math.sqrt(closes.map { val d = it - mu; d * d }.average())
        assertEquals(sd / mu, w.realizedVol, 1e-9)
    }

    private fun golden(): File {
        val resource = javaClass.classLoader?.getResource("edge_features_golden.json")
        if (resource != null) return File(resource.toURI())
        val candidates = listOf(
            File("ml/fixtures/edge_features_golden.json"),
            File("../ml/fixtures/edge_features_golden.json"),
            File("../../ml/fixtures/edge_features_golden.json")
        )
        return candidates.first { it.isFile }
    }
}
