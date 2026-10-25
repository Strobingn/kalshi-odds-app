package com.dirk.kalshiodds.ui.charts.rich

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The new chart reads stay off the main thread and follow the registry. */
class RichChartIoTest {
    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"), File("src/main/java/com/dirk/kalshiodds/ui/$path"))
        return candidates.first { it.isFile }.readText()
    }

    @Test fun scalpChartsQueryOnIoAndFollowTheRegistry() {
        val ui = source("src/main/java/com/dirk/kalshiodds/ui/ScalpRichCharts.kt")
        val io = ui.substringAfter("withContext(Dispatchers.IO)")
        assertTrue(io.contains("pageTrades"))
        assertTrue(ui.contains("RichChartLoader.scalpQueryIds"))
        assertTrue(ui.contains("ScalpModels.ALL"))
        assertFalse(ui.contains("sf42"))
        assertFalse(ui.contains("g14-t12"))
        assertTrue(ui.contains("RichTradeMath.MAX_ROWS") || io.contains("perQueryLimit"))
    }

    @Test fun paperChartsReadTheBookOnIo() {
        val ui = source("src/main/java/com/dirk/kalshiodds/ui/PaperRichCharts.kt")
        val io = ui.substringAfter("withContext(Dispatchers.IO)")
        assertTrue(io.contains("snapshot()"))
        assertTrue(io.contains("chartScalps()"))
        assertTrue(io.contains("paperActivity"))
        assertFalse(io.contains("predictionLedger"))
        assertTrue(ui.contains("Dispatchers.IO"))
        assertFalse(ui.substringBefore("withContext(Dispatchers.IO)").contains("snapshot()"))
    }
}
