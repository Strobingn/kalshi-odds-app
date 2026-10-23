package com.dirk.kalshiodds.signal.scalper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The shipped ONNX model (`assets/scalper_model.onnx`) on ONNX Runtime must give what the Kotlin trees give, and the
 * app must fall back to the trees whenever it cannot.
 */
class OnnxTreesTest {

    private fun asset(name: String): File =
        listOf("src/main/assets/$name", "app/src/main/assets/$name").map { File(it) }.first { it.isFile }

    private val jsonBytes by lazy { asset(ScalperModel.ASSET_NAME).readBytes() }
    private val onnxBytes by lazy { asset(ScalperModel.ONNX_ASSET_NAME).readBytes() }
    private fun model() = ScalperModel.parse(String(jsonBytes, Charsets.UTF_8))

    private fun recordedRows(): List<Pair<FloatArray, Double>> {
        val stream = javaClass.classLoader!!.getResourceAsStream("scalper_parity.json")
        assertNotNull(stream)
        val rows = JSONObject(stream!!.bufferedReader().use { it.readText() }).getJSONArray("rows")
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            val x = r.getJSONArray("x")
            // ONNX gives the trees' sum: the margin (a classifier's log-odds), or the cents of an older regression file.
            FloatArray(x.length()) { x.getDouble(it).toFloat() } to (if (r.has("margin")) r.getDouble("margin") else r.getDouble("pred"))
        }
    }

    @Test
    fun onnxGivesTheTreesAnswerOnRecordedRowsAndOnEverySplit() {
        val m = model()
        OnnxTrees.open(onnxBytes, jsonBytes, m.features).use { onnx ->
            var worst = 0.0
            for ((x, study) in recordedRows()) {
                worst = maxOf(worst, abs(onnx.predict(x) - study), abs(onnx.predict(x) - m.marginTrees(x)))
            }
            for (x in m.probeRows(400)) worst = maxOf(worst, abs(onnx.predict(x) - m.marginTrees(x)))
            assertTrue("worst difference $worst cents", worst < ScalperModel.PARITY_TOLERANCE_CENTS)
        }
    }

    @Test
    fun theModelSwitchesToOnnxOnlyAfterItMatches() {
        val m = model()
        OnnxTrees.open(onnxBytes, jsonBytes, m.features).use { onnx ->
            assertNull(m.attachOnnx(onnx::predict))
            assertEquals("ONNX Runtime (CPU)", m.engine)
            val (x, study) = recordedRows().first()
            assertEquals(study, m.margin(x), ScalperModel.PARITY_TOLERANCE_CENTS)
            assertEquals(m.predictTrees(x), m.predict(x), 1e-3)
        }
    }

    @Test
    fun anOnnxFileFromOtherTreesIsRefused() {
        val m = model()
        val other = jsonBytes + " ".toByteArray()
        try {
            OnnxTrees.open(onnxBytes, other, m.features).close()
            fail("opened an ONNX model built from other trees")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("other trees"))
        }
    }

    @Test
    fun aWrongOrBrokenEngineLeavesTheKotlinTrees() {
        val (x, _) = recordedRows().first()
        val want = model().predictTrees(x)

        val off = model()
        assertTrue(off.attachOnnx({ off.marginTrees(it) + 0.5 }) != null)
        assertTrue(off.engine.startsWith("Kotlin trees"))
        assertEquals(want, off.predict(x), 1e-9)

        val throws = model()
        assertTrue(throws.attachOnnx({ error("no native library") }) != null)
        assertEquals(want, throws.predict(x), 1e-9)

        // Matches at first, then stops answering: predict falls back for good.
        val later = model()
        var broken = false
        assertNull(later.attachOnnx({ if (broken) error("session closed") else later.marginTrees(it) }))
        broken = true
        assertEquals(want, later.predict(x), 1e-9)
        assertTrue(later.engine.contains("stopped answering"))
    }
}
