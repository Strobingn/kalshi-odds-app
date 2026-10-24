package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin / ONNX CPU / NPU, your choice; anything that fails or dies falls back to the Kotlin trees. */
class MlEngineChoiceTest {
    private val json = """{"features":["mid","spread"],"theta_cents":0.5,
        "trees":[{"f":[0,-1,-1],"t":[0.5,0,0],"l":[1,-1,-1],"r":[2,-1,-1],"v":[0,-1.0,2.0]}]}"""

    @Test
    fun kotlinChosenRunsTheTreesAndOpensNothing() {
        val m = ScalperModel.parse(json)
        var opened = false
        assertNull(ModelEngines.use(m, MlEngine.KOTLIN, { opened = true; error("no") }))
        assertFalse(opened)
        assertTrue(m.engine.startsWith("Kotlin trees"))
        assertEquals("in use (your choice)", m.engines.single().note)
    }

    @Test
    fun aChosenEngineThatMatchesTheTreesIsUsedUntilItDies() {
        val m = ScalperModel.parse(json)
        var alive = true
        val e = ModelEngines.use(m, MlEngine.NPU, { ModelEngines.Opened("NPU · separate process", { x -> check(alive); m.marginTrees(x) }, {}) })
        assertEquals("NPU · separate process", e!!.label)
        assertEquals("NPU · separate process", m.engine)
        // The ML process dies: the next prediction is the Kotlin trees' and says why.
        alive = false
        val x = floatArrayOf(0.7f, 0.01f)
        assertEquals(m.marginTrees(x), m.margin(x), 0.0)
        assertTrue(m.engine.startsWith("Kotlin trees"))
    }

    @Test
    fun anEngineThatCannotOpenOrDiffersLeavesTheKotlinTrees() {
        val m = ScalperModel.parse(json)
        assertNull(ModelEngines.use(m, MlEngine.ONNX_CPU, { error("the ML process did not answer") }))
        assertTrue(m.engine.contains("did not answer"))
        assertFalse(m.engines.first().ok)
        assertNull(ModelEngines.use(m, MlEngine.ONNX_CPU, { ModelEngines.Opened("x", { it[0] + 1.0 }, {}) }))
        assertTrue(m.engine.startsWith("Kotlin trees"))
        assertEquals(MlEngine.ONNX_CPU, MlEngine.of(null))
        assertEquals(MlEngine.NPU, MlEngine.of("NPU"))
    }
}
