package com.dirk.kalshiodds.signal.updown

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The Kotlin inputs must be the ones the model was trained on: rows written by updown_features.py from recorded windows. */
class UpDownFeaturesTest {

    private fun asset(name: String): File =
        listOf("src/main/assets/$name", "app/src/main/assets/$name").map { File(it) }.first { it.isFile }

    private val model by lazy { UpDownModel.parse(asset(UpDownModel.ASSET_NAME).readText()) }

    private fun fixture(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("updown_parity.json")
        assertNotNull(stream)
        return JSONObject(stream!!.bufferedReader().use { it.readText() })
    }

    private fun arr(a: JSONArray) = DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.getDouble(it) }
    private fun opt(o: JSONObject, k: String): Double? = if (o.isNull(k)) null else o.getDouble(k)

    @Test
    fun namesMatchTheTrainer() {
        val fx = fixture()
        val names = fx.getJSONArray("names")
        assertEquals(UpDownFeatures.NAMES, List(names.length()) { names.getString(it) })
        val mf = fx.getJSONArray("model_features")
        assertEquals(model.features, List(mf.length()) { mf.getString(it) })
    }

    @Test
    fun recordedMomentsGiveTheTrainersRowsAndChance() {
        val cases = fixture().getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        var worstRow = 0.0
        var worstUp = 0.0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val row = UpDownFeatures.row(
                spot = arr(c.getJSONArray("spot")), strike = c.getDouble("strike"), tteS = c.getDouble("tte_s"),
                secIn = c.getInt("sec_in"), yesBid = opt(c, "yes_bid"), yesBidQty = opt(c, "yes_bid_qty"),
                yesAsk = opt(c, "yes_ask"), yesAskQty = opt(c, "yes_ask_qty"), mid = arr(c.getJSONArray("mid"))
            )
            assertNotNull("${c.getString("ticker")} at ${c.getInt("sec_in")}", row)
            val want = arr(c.getJSONArray("features"))
            for (k in want.indices) worstRow = maxOf(worstRow, abs(row!![k] - want[k]))
            val x = UpDownFeatures.design(row!!, model.features)!!
            val wantX = arr(c.getJSONArray("x"))
            for (k in wantX.indices) assertEquals(wantX[k], x[k].toDouble(), 1e-5 * maxOf(1.0, abs(wantX[k])))
            worstUp = maxOf(worstUp, abs(model.upKotlin(x) - c.getDouble("up")))
        }
        assertTrue("worst feature difference $worstRow", worstRow < 1e-7)
        assertTrue("worst chance difference $worstUp", worstUp < 1e-5)
    }

    @Test
    fun noRowWithoutAFreshSpotAWarmVolatilityOrATwoSidedBook() {
        val walk = DoubleArray(1201) { 100.0 * kotlin.math.exp(0.0001 * kotlin.math.sin(it.toDouble())) }
        val mid = DoubleArray(601) { 0.41 }
        assertNotNull(UpDownFeatures.row(walk, 100.0, 300.0, 600, 0.40, 10.0, 0.42, 30.0, mid))
        val stale = walk.copyOf().also { for (k in 1195 until 1201) it[k] = Double.NaN }
        assertNull(UpDownFeatures.row(stale, 100.0, 300.0, 600, 0.40, 10.0, 0.42, 30.0, mid))
        assertNull(UpDownFeatures.row(walk.copyOfRange(1101, 1201), 100.0, 300.0, 600, 0.40, 10.0, 0.42, 30.0, mid))
        assertNull(UpDownFeatures.row(walk, 100.0, 300.0, 600, null, 10.0, 0.42, 30.0, mid))
        val row = UpDownFeatures.row(walk, 100.0, 300.0, 600, 0.40, 10.0, 0.42, 30.0, mid)!!
        assertEquals(0.41, UpDownFeatures.mid(row), 1e-12)
        assertEquals(-0.5, row[UpDownFeatures.NAMES.indexOf("imb")], 1e-12)
        assertEquals(0.0, row[UpDownFeatures.NAMES.indexOf("dmid30")], 1e-12)
    }
}
