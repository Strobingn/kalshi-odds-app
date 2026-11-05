package com.dirk.kalshiodds.signal.updown

import com.dirk.kalshiodds.signal.scalper.OnnxTrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The shipped ONNX file on ONNX Runtime (JVM build) must give the Kotlin math's chance, and the model must fall back when it cannot. */
class UpDownModelTest {

    private fun asset(name: String): File =
        listOf("src/main/assets/$name", "app/src/main/assets/$name").map { File(it) }.first { it.isFile }

    private val jsonBytes by lazy { asset(UpDownModel.ASSET_NAME).readBytes() }
    private val onnxBytes by lazy { asset(UpDownModel.ONNX_ASSET_NAME).readBytes() }
    private fun model() = UpDownModel.parse(String(jsonBytes, Charsets.UTF_8))

    @Test
    fun theFileCarriesItsTestNumbers() {
        val r = model().report
        assertTrue(r.testWindows > 100)
        assertTrue(r.model.logLoss in 0.2..0.7 && r.market.logLoss in 0.2..0.7)
        assertEquals(10, r.calibration.size)
        assertTrue(r.replay.isNotEmpty())
    }

    @Test
    fun onnxGivesTheKotlinChance() {
        val m = model()
        OnnxTrees.open(onnxBytes, jsonBytes, m.features).use { onnx ->
            val worst = m.probeRows(400).maxOf { abs(onnx.predict(it) - m.upKotlin(it)) }
            assertTrue("worst $worst", worst < UpDownModel.PARITY_TOLERANCE)
            assertNull(m.attachOnnx(onnx::predict, "ONNX Runtime (CPU)"))
            assertEquals("ONNX Runtime (CPU)", m.engine)
            val x = m.probeRows(1).first()
            assertEquals(m.upKotlin(x), m.up(x), UpDownModel.PARITY_TOLERANCE)
        }
    }

    @Test
    fun aStaleOnnxFileIsRefused() {
        val m = model()
        val other = jsonBytes + " ".toByteArray()
        try {
            OnnxTrees.open(onnxBytes, other, m.features).close()
            fail("opened an ONNX file built from other JSON")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("other trees"))
        }
    }

    @Test
    fun aWrongOrFailingEngineLeavesKotlinRunning() {
        val m = model()
        assertNotNull(m.attachOnnx({ m.upKotlin(it) + 0.01 }, "bad"))
        assertTrue(m.engine.startsWith("Kotlin"))
        var calls = 0
        assertNull(m.attachOnnx({ x -> if (++calls > 64) error("process died") else m.upKotlin(x) }, "flaky"))
        val x = m.probeRows(2).last()
        assertEquals(m.upKotlin(x), m.up(x), 1e-12)
        assertTrue(m.engine, m.engine.startsWith("Kotlin (ONNX Runtime failed"))
    }
}
