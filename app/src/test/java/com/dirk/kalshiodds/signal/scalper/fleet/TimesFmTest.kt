package com.dirk.kalshiodds.signal.scalper.fleet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * TimesFM's pre/post-processing against a reference run of the real model (`timesfm_reference.json`: a 512-point sine,
 * the graph's raw outputs and the forecast, made with onnxruntime 1.29 and the forecast-web steps). The full model
 * runs too when `TIMESFM_ONNX` points at it (it is 249 MB, so CI skips that part).
 */
class TimesFmTest {
    private val ref by lazy { JSONObject(javaClass.classLoader!!.getResourceAsStream("fleet/timesfm_reference.json")!!.bufferedReader().use { it.readText() }) }

    private fun arr(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
    private fun tensor(a: JSONArray) = Array(a.length()) { i -> val b = a.getJSONArray(i); Array(b.length()) { j -> val c = b.getJSONArray(j); FloatArray(c.length()) { c.getDouble(it).toFloat() } } }

    @Test
    fun prepareMatches() {
        val series = arr(ref.getJSONArray("series"))
        val p = TimesFm.prepare(series)!!
        val want = ref.getJSONArray("inputs")
        for (r in 0..1) for (i in 0 until 512) assertEquals(want.getJSONArray(r).getDouble(i), p.inputs[r * 512 + i].toDouble(), 1e-5)
        assertTrue(p.masks.all { it == 0f })
        // Gaps: leading NaNs dropped and padded, an interior gap filled on a line.
        val gappy = DoubleArray(20) { if (it < 3) Double.NaN else if (it == 10) Double.NaN else it.toDouble() }
        val g = TimesFm.prepare(gappy)!!
        assertEquals(2 * (512 - 17), g.masks.count { it == 1f })
    }

    @Test
    fun postprocessMatches() {
        val series = arr(ref.getJSONArray("series"))
        val p = TimesFm.prepare(series)!!
        val out = TimesFm.postprocess(tensor(ref.getJSONArray("point")), tensor(ref.getJSONArray("qspread")), p.mu, p.sigma, p.positive)
        val want = ref.getJSONArray("out")
        for (t in 0 until 128) for (q in 0 until 10) assertEquals("t $t q $q", want.getJSONArray(t).getDouble(q), out[t][q], 1e-6)
        for (row in out) for (q in 1 until 9) assertTrue(row[q] <= row[q + 1])
    }

    @Test
    fun theRealModelContinuesASine() {
        val path = System.getenv("TIMESFM_ONNX")
        Assume.assumeTrue("set TIMESFM_ONNX to the 249 MB model to run it", path != null && File(path).length() == TimesFm.SIZE)
        val f = File(System.getProperty("java.io.tmpdir"), TimesFm.FILE_NAME)
        if (!f.exists()) File(path!!).copyTo(f)
        TimesFm(f).use { m ->
            val series = arr(ref.getJSONArray("series"))
            val q = m.forecast(series)!!
            val want = ref.getJSONArray("out")
            var err = 0.0
            for (t in 0 until 64) {
                err += abs(q[t][4] - (100_000 + 50 * sin(2 * PI * (512 + t) / 64)))
                assertEquals(want.getJSONArray(t).getDouble(5), q[t][4], 0.05)
            }
            assertTrue("mean error ${err / 64}", err / 64 < 1.0)
        }
    }

    @Test
    fun steppedCoversTheWholeWindowAtTwoSeconds() {
        val sec = DoubleArray(TimesFm.CONTEXT * TimesFm.STEP_S) { if (it % 7 == 3) Double.NaN else it.toDouble() }
        val st = TimesFm.stepped(sec)
        assertEquals(TimesFm.CONTEXT, st.size)
        // Each step keeps its last second's value; 1,024 s in, so a 900 s window fits with room to spare.
        assertEquals(1.0, st[0], 0.0)
        assertEquals((sec.size - 1).toDouble(), st.last(), 0.0)
        assertTrue(TimesFm.CONTEXT * TimesFm.STEP_S >= 900)
        // A step whose seconds are both missing stays missing; one missing second falls back to the other.
        val gap = TimesFm.stepped(doubleArrayOf(Double.NaN, Double.NaN, 5.0, Double.NaN))
        assertTrue(gap[0].isNaN())
        assertEquals(5.0, gap[1], 0.0)
    }
}
