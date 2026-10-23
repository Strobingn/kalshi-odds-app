package com.dirk.kalshiodds.signal.scalper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The NPU copy of the trees (`assets/scalper_model_npu.onnx`): its bins, its leaf mask and the sum the app makes
 * from it must be the Kotlin trees' sum to the bit. On the desktop the same graph runs on ONNX Runtime's CPU; on the
 * phone it runs on the QNN HTP backend and is checked the same way before it is used.
 */
class NpuTreesTest {

    private fun asset(name: String): File =
        listOf("src/main/assets/$name", "app/src/main/assets/$name").map { File(it) }.first { it.isFile }

    private val jsonBytes by lazy { asset(ScalperModel.ASSET_NAME).readBytes() }
    private val npuBytes by lazy { asset(ScalperModel.NPU_ASSET_NAME).readBytes() }
    private val onnxBytes by lazy { asset(ScalperModel.ONNX_ASSET_NAME).readBytes() }
    private fun model() = ScalperModel.parse(String(jsonBytes, Charsets.UTF_8))

    private fun recordedRows(): List<FloatArray> {
        val stream = javaClass.classLoader!!.getResourceAsStream("scalper_parity.json")
        assertNotNull(stream)
        val rows = JSONObject(stream!!.bufferedReader().use { it.readText() }).getJSONArray("rows")
        return (0 until rows.length()).map { i ->
            val x = rows.getJSONObject(i).getJSONArray("x")
            FloatArray(x.length()) { x.getDouble(it).toFloat() }
        }
    }

    @Test
    fun binsSplitExactlyWhereTheTreesSplit() {
        val m = model()
        for (x in m.probeRows(300) + recordedRows()) {
            val b = m.bins(x)
            for (f in x.indices) {
                val e = m.binEdges[f]
                assertTrue(b[f] >= 0f && b[f] <= e.size && b[f] < 2048f)
                for (k in e.indices) {
                    // x <= threshold k exactly when bins <= k (a missing value goes right everywhere, as in the trees).
                    assertEquals("feature $f split $k", x[f].toDouble() <= e[k], b[f] <= k)
                }
            }
        }
    }

    @Test
    fun theNpuGraphPicksTheTreesLeavesToTheBit() {
        val m = model()
        NpuTrees.open(npuBytes, jsonBytes, m, onNpu = false).use { npu ->
            for (x in m.probeRows(400) + recordedRows()) assertEquals(m.marginTrees(x), npu.margin(x), 0.0)
        }
    }

    @Test
    fun aBadLeafMaskIsRefused() {
        val m = model()
        val n = m.treeCount * m.leafSlots
        for (bad in listOf(FloatArray(n), FloatArray(n) { 1f }, FloatArray(n) { if (it % m.leafSlots == 0) 0.5f else 0f })) {
            try {
                m.marginFromLeaves(bad)
                fail("accepted a bad leaf mask")
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun anNpuFileFromOtherTreesIsRefused() {
        try {
            NpuTrees.open(npuBytes, jsonBytes + " ".toByteArray(), model(), onNpu = false)
            fail("opened an NPU graph built from other trees")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("other trees"))
        }
    }

    @Test
    fun withoutTheNpuTheCpuOnnxRunsAndEveryEngineIsTimed() {
        // The desktop ONNX Runtime has no QNN provider: the NPU fails with a reason, ONNX on the CPU is used.
        val m = model()
        val used = ModelEngines.choose(
            m,
            npu = {
                val t = NpuTrees.open(npuBytes, jsonBytes, m, onNpu = true)
                ModelEngines.Opened(t.label, t::margin, t::close)
            },
            cpu = {
                val t = OnnxTrees.open(onnxBytes, jsonBytes, m.features)
                ModelEngines.Opened("ONNX Runtime (CPU)", t::predict, t::close)
            }
        )
        try {
            assertNotNull(used)
            assertEquals("ONNX Runtime (CPU)", m.engine)
            val names = m.engines.map { it.name }
            assertEquals(listOf(NpuTrees.NPU_LABEL, "ONNX Runtime (CPU)", "Kotlin trees"), names)
            assertFalse(m.engines[0].ok)
            assertTrue(m.engines[0].note.isNotBlank())
            assertTrue(m.engines[1].ok && m.engines[1].warmMicros > 0)
        } finally {
            used?.close?.invoke()
        }
    }

    @Test
    fun theNpuIsUsedFirstWhenItOpensAndMatches() {
        val m = model()
        var cpuClosed = false
        val used = ModelEngines.choose(
            m,
            npu = {
                val t = NpuTrees.open(npuBytes, jsonBytes, m, onNpu = false)
                ModelEngines.Opened(NpuTrees.NPU_LABEL, t::margin, t::close)
            },
            cpu = {
                val t = OnnxTrees.open(onnxBytes, jsonBytes, m.features)
                ModelEngines.Opened("ONNX Runtime (CPU)", t::predict) { cpuClosed = true; t.close() }
            }
        )
        try {
            assertEquals(NpuTrees.NPU_LABEL, m.engine)
            assertTrue(cpuClosed)
            assertTrue(m.engines.all { it.ok })
            val x = recordedRows().first()
            assertEquals(m.predictTrees(x), m.predict(x), 0.0)
        } finally {
            used?.close?.invoke()
        }
    }

    @Test
    fun anNpuThatAnswersWrongIsNotUsed() {
        val m = model()
        val used = ModelEngines.choose(
            m,
            npu = { ModelEngines.Opened(NpuTrees.NPU_LABEL, { m.marginTrees(it) + 0.01 }, {}) },
            cpu = { error("no ONNX here") }
        )
        assertNull(used)
        assertTrue(m.engine.startsWith("Kotlin trees"))
        assertFalse(m.engines[0].ok)
        assertTrue(m.engines.last().ok)
    }
}
