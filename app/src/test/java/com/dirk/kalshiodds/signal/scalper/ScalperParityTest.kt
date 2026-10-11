package com.dirk.kalshiodds.signal.scalper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The app must feed the model the numbers it was trained on. A recorded
 * window (every public trade of the first 7 minutes of
 * KXBTC15M-26OCT050815-15) is replayed into [PrintGrid]; every feature row
 * and every model output must match what the Python study computed
 * (`src/test/resources/scalper_parity.json`, written by
 * `tools/research/scalp/export_app_model.py`).
 */
class ScalperParityTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("scalper_parity.json")
        assertNotNull("scalper_parity.json is on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().use { it.readText() })
    }

    private val model: ScalperModel by lazy {
        val file = listOf("src/main/assets/scalper_model.json", "app/src/main/assets/scalper_model.json")
            .map { File(it) }.first { it.isFile }
        ScalperModel.parse(file.readText())
    }

    private fun replay(): PrintGrid {
        val open = 1_000_000_000_000L
        val grid = PrintGrid(open)
        val trades = fixture.getJSONArray("trades")
        for (i in 0 until trades.length()) {
            val t = trades.getJSONArray(i)
            grid.add(open + t.getLong(0), takerYes = t.getInt(3) == 1, yesPrice = t.getDouble(1), contracts = t.getDouble(2))
        }
        return grid
    }

    @Test
    fun shippedModelLoadsWithTheGridsFeatureOrder() {
        assertEquals(PrintGrid.FEATURES, model.features)
        assertTrue(model.treeCount > 50)
        assertTrue("queue penalty rises with the queue", model.queuePenaltyCents(2_000.0) > model.queuePenaltyCents(100.0))
        assertEquals(0.0, model.queuePenaltyCents(0.0), 0.0)
    }

    @Test
    fun featuresRebuiltFromPrintsMatchThePythonStudy() {
        val grid = replay()
        val rows = fixture.getJSONArray("rows")
        assertTrue("fixture has rows", rows.length() >= 100)
        val names = PrintGrid.FEATURES
        var worst = 0.0
        var worstName = ""
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val x = grid.features(r.getString("side"), r.getInt("sec"))
            assertNotNull("row ${r.getString("side")} @ ${r.getInt("sec")} s is quotable in the app too", x)
            val want = r.getJSONArray("x")
            for (k in names.indices) {
                val w = want.getDouble(k)
                val d = abs(x!![k] - w) / maxOf(1.0, abs(w))
                if (d > worst) {
                    worst = d
                    worstName = "${names[k]} at ${r.getString("side")} ${r.getInt("sec")} s: app ${x[k]} vs study $w"
                }
            }
        }
        assertTrue("largest feature difference $worst ($worstName)", worst < 1e-5)
    }

    @Test
    fun modelOutputsMatchThePythonStudy() {
        val grid = replay()
        val rows = fixture.getJSONArray("rows")
        var worst = 0.0
        var flips = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val x = grid.features(r.getString("side"), r.getInt("sec"))!!
            val got = model.predict(x)
            val want = r.getDouble("pred")
            worst = maxOf(worst, abs(got - want))
            if ((got >= model.thetaCents) != (want >= model.thetaCents)) flips++
        }
        assertTrue("largest prediction difference $worst cents", worst < 1e-6)
        assertEquals("the act / skip decision never differs", 0, flips)
    }

    @Test
    fun noFeaturesBeforeTheGridIsWarmOrOutsideTheDecisionBand() {
        val grid = replay()
        assertTrue(grid.firstSecond in 0..5)
        assertEquals(null, grid.features("YES", 59))
        assertEquals(null, grid.features("YES", 781))
        // A grid that only started at 100 s is not warm at 120 s.
        val late = PrintGrid(0L)
        late.add(100_500L, true, 0.51, 10.0)
        late.add(100_600L, false, 0.50, 10.0)
        late.add(120_200L, true, 0.51, 10.0)
        late.add(120_300L, false, 0.50, 10.0)
        assertTrue(late.quotable("YES", 120))
        assertEquals(null, late.features("YES", 120))
    }
}
