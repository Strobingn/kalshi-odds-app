package com.dirk.kalshiodds.decision

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GbmModelTest {
    private fun root() = listOf(File("src/main/assets/btc15_gbm.json"), File("app/src/main/assets/btc15_gbm.json")).first { it.isFile }

    @Test fun kotlinMatchesPythonExport() {
        GbmModel.load(root().readText())
        assertTrue(GbmModel.loaded)
        val par = JSONObject(javaClass.classLoader!!.getResource("gbm_parity.json")!!.readText())
        val xs = par.getJSONArray("x"); val ps = par.getJSONArray("p")
        for (i in 0 until xs.length()) {
            val a = xs.getJSONArray(i); val x = DoubleArray(a.length()) { a.getDouble(it) }
            assertEquals(ps.getDouble(i), GbmModel.predict(x)!!, 1e-6)
        }
    }

    @Test fun exportedModelIsNoWorseThanMarketOnHeldOutDays() {
        val m = JSONObject(root().readText()).getJSONObject("metrics")
        assertTrue(m.getDouble("model") <= m.getDouble("market_mid"))
    }
}
