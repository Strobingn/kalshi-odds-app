package com.dirk.kalshiodds.signal.updown

import com.dirk.kalshiodds.signal.engine.TopOfBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpDownLiveTest {

    private val model by lazy {
        UpDownModel.parse(listOf("src/main/assets/updown_model.json", "app/src/main/assets/updown_model.json").map { File(it) }.first { it.isFile }.readText())
    }

    @Test
    fun readsEverySecondAndStartsOverOnANewWindow() {
        val open = 1_793_900_700_000L
        var ticker = "KXBTC15M-A"
        val live = UpDownLive(
            model,
            spot = { now -> DoubleArray(UpDownFeatures.SPOT_SECONDS) { 90_000.0 * kotlin.math.exp(0.00005 * kotlin.math.sin(it + now / 1000.0)) } },
            window = { _ -> UpDownLive.Window(ticker, open, open + 900_000L, 90_000.0) },
            top = { TopOfBook(yesBid = 0.48, yesBidQty = 100.0, yesAsk = 0.50, yesAskQty = 80.0, noBid = 0.50, noAsk = 0.52) }
        )
        for (s in 0 until 40) live.tick(open + s * 1000L + 300)
        val r = live.latest.value
        assertNotNull(r)
        assertEquals(39, r!!.secIn)
        assertTrue(r.up in 0.0..1.0)
        assertEquals(0.49, r.mid, 1e-12)
        assertEquals(0.52, r.noAsk!!, 1e-12)
        assertEquals(40, live.points.value.size)
        ticker = "KXBTC15M-B"
        live.tick(open + 41_000L)
        assertEquals(1, live.points.value.size)
        assertNull(live.tick(open + 900_500L)) // past the close of this window
    }
}
