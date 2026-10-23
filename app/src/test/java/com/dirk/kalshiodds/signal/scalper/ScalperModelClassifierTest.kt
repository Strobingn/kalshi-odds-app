package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.exp

/** The calibrated classifier file: log-odds trees, two curves, node values for Explain, strict parsing. */
class ScalperModelClassifierTest {

    private val json = """
        {"version":2,"kind":"classifier","features":["mid","spread"],"theta_cents":-1.5,
         "trained":"a..b","valid":"c..d","test":"e..f",
         "calibration":{"x":[0.0,0.5,1.0],"y":[0.0,0.4,1.0]},
         "cents_by_chance":{"x":[0.0,1.0],"y":[-4.0,1.0]},
         "report":{"max_depth":5,"test":{"n":10}},
         "trees":[
           {"f":[0,-1,-1],"t":[0.5,0,0],"l":[1,-1,-1],"r":[2,-1,-1],"v":[0,-0.4,0.6],"iv":[0.1,-0.4,0.6]},
           {"f":[1,-1,-1],"t":[0.02,0,0],"l":[1,-1,-1],"r":[2,-1,-1],"v":[0,0.3,-0.2],"iv":[0.05,0.3,-0.2]}
         ]}
    """.trimIndent()

    private fun sigmoid(m: Double) = 1.0 / (1.0 + exp(-m))

    @Test
    fun chanceAndCentsComeThroughBothCurves() {
        val m = ScalperModel.parse(json)
        assertTrue(m.isClassifier)
        val x = floatArrayOf(0.4f, 0.01f)
        assertEquals(-0.1, m.margin(x), 1e-12)
        val cal = 0.4 * sigmoid(-0.1) / 0.5
        assertEquals(cal, m.probability(x)!!, 1e-12)
        assertEquals(-4.0 + 5.0 * cal, m.predict(x), 1e-12)
        assertEquals(m.predict(x), m.predictTrees(x), 0.0)
        // Higher log-odds, higher chance and cents: the curves are monotone.
        val y = floatArrayOf(0.6f, 0.01f)
        assertTrue(m.probability(y)!! > m.probability(x)!!)
        assertTrue(m.predict(y) > m.predict(x))
        assertEquals(5, m.report!!.getInt("max_depth"))
    }

    @Test
    fun contributionsAddUpToTheMarginExactly() {
        val m = ScalperModel.parse(json)
        assertTrue(m.explainable)
        for (x in listOf(floatArrayOf(0.4f, 0.01f), floatArrayOf(0.9f, 0.05f), floatArrayOf(0.5f, 0.02f))) {
            val c = m.contributions(x)!!
            assertEquals(m.marginTrees(x), m.bias + c.sum(), 1e-12)
        }
        val c = m.contributions(floatArrayOf(0.4f, 0.01f))!!
        assertEquals(-0.5, c[0], 1e-12)
        assertEquals(0.25, c[1], 1e-12)
    }

    @Test
    fun curvesClampOutsideTheirKnotsAndInterpolateInside() {
        val c = ScalperModel.Curve(doubleArrayOf(0.1, 0.2, 0.4), doubleArrayOf(1.0, 2.0, 6.0))
        assertEquals(1.0, c.at(-5.0), 0.0)
        assertEquals(6.0, c.at(9.0), 0.0)
        assertEquals(1.5, c.at(0.15), 1e-12)
        assertEquals(4.0, c.at(0.3), 1e-12)
        assertEquals(2.0, c.at(0.2), 0.0)
        try {
            ScalperModel.Curve(doubleArrayOf(0.2, 0.1), doubleArrayOf(0.0, 1.0))
            fail("descending knots must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aRegressionFileStillWorksAndHasNoChance() {
        val reg = json.replace("\"kind\":\"classifier\",", "").replace(",\"iv\":[0.1,-0.4,0.6]", "")
        val m = ScalperModel.parse(reg)
        assertFalse(m.isClassifier)
        val x = floatArrayOf(0.4f, 0.01f)
        assertNull(m.probability(x))
        assertEquals(-0.1, m.predict(x), 1e-12)
        assertFalse("one tree lacks node values", m.explainable)
        assertNull(m.contributions(x))
    }

    @Test
    fun brokenFilesAreRefused() {
        fun refused(bad: String, why: String) {
            try {
                ScalperModel.parse(bad)
                fail(why)
            } catch (_: IllegalArgumentException) {
            } catch (_: org.json.JSONException) {
            }
        }
        refused(json.replace("\"features\":[\"mid\",\"spread\"]", "\"features\":[\"mid\",\"spot_z\"]"), "a feature the app cannot rebuild")
        refused(json.replace("\"features\":[\"mid\",\"spread\"]", "\"features\":[\"mid\",\"mid\"]"), "a repeated feature")
        refused(json.replace("\"l\":[1,-1,-1],\"r\":[2,-1,-1],\"v\":[0,-0.4,0.6]", "\"l\":[7,-1,-1],\"r\":[2,-1,-1],\"v\":[0,-0.4,0.6]"), "a child out of range")
        refused(json.replace("\"cents_by_chance\":{\"x\":[0.0,1.0],\"y\":[-4.0,1.0]},", ""), "a classifier without its cents curve")
        refused(json.replace("\"f\":[0,-1,-1],\"t\":[0.5,0,0]", "\"f\":[5,-1,-1],\"t\":[0.5,0,0]"), "a split on a feature it does not have")
    }

    @Test
    fun onnxIsComparedOnTheMarginNotTheCents() {
        val m = ScalperModel.parse(json)
        assertNull(m.attachOnnx({ m.marginTrees(it) }))
        assertEquals("ONNX Runtime (CPU)", m.engine)
        val n = ScalperModel.parse(json)
        assertTrue("cents are not margins", n.attachOnnx({ n.predictTrees(it) }) != null)
    }
}
