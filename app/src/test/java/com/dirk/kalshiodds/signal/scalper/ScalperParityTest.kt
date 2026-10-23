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
        // The window's real open (UTC hour and weekday are features); older fixtures carry none.
        val open = if (fixture.has("open")) fixture.getLong("open") * 1000L else 1_000_000_000_000L
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
        assertEquals(fixture.getJSONArray("features").let { a -> (0 until a.length()).map { a.getString(it) } }, model.features)
        assertTrue(model.features.all { it in PrintGrid.CANDIDATES })
        assertTrue(model.treeCount > 20)
        assertTrue("queue penalty rises with the queue", model.queuePenaltyCents(2_000.0) > model.queuePenaltyCents(100.0))
        assertEquals(0.0, model.queuePenaltyCents(0.0), 0.0)
    }

    @Test
    fun featuresRebuiltFromPrintsMatchThePythonStudy() {
        val grid = replay()
        val rows = fixture.getJSONArray("rows")
        assertTrue("fixture has rows", rows.length() >= 100)
        val names = model.features
        var worst = 0.0
        var worstName = ""
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val x = grid.features(r.getString("side"), r.getInt("sec"), names)
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
            val x = grid.features(r.getString("side"), r.getInt("sec"), model.features)!!
            val got = model.predict(x)
            val want = r.getDouble("pred")
            worst = maxOf(worst, abs(got - want))
            if ((got >= model.thetaCents) != (want >= model.thetaCents)) flips++
        }
        assertTrue("largest prediction difference $worst cents", worst < 1e-6)
        assertEquals("the act / skip decision never differs", 0, flips)
    }

    /** Every feature the app can rebuild ([PrintGrid.CANDIDATES]), not only the model's, matches build_ml.py. */
    @Test
    fun everyCandidateFeatureMatchesThePythonStudy() {
        // Older fixtures (the 23-feature regression file) carry only the model's own features.
        if (!fixture.getJSONArray("rows").getJSONObject(0).has("all")) return
        val names = fixture.getJSONArray("candidates").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals("the study's candidate list is the app's", PrintGrid.CANDIDATES.toList(), names)
        val grid = replay()
        val rows = fixture.getJSONArray("rows")
        val worst = DoubleArray(names.size)
        var checked = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            // Rows the long lookbacks are not warm for are left out (the app would not trade them).
            val x = grid.features(r.getString("side"), r.getInt("sec"), names) ?: continue
            checked++
            val want = r.getJSONArray("all")
            for (k in names.indices) {
                val w = want.getDouble(k)
                worst[k] = maxOf(worst[k], abs(x[k] - w) / maxOf(1.0, abs(w)))
            }
        }
        assertTrue("rows checked: $checked", checked >= 50)
        val bad = names.indices.filter { worst[it] >= 1e-5 }.map { "${names[it]} ${worst[it]}" }
        assertTrue("features that differ from the study: $bad", bad.isEmpty())
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
