package com.dirk.kalshiodds.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Data screen must not promise a weekly publish. GitHub schedules
 * run only on the repository default branch, which is not kashi.
 */
class ModelPublishCopyTest {
    @Test
    fun dataCopyKeepsTheGateAndDropsTheWeeklyPromise() {
        val screen = source("app/src/main/java/com/dirk/kalshiodds/ui/DataScreen.kt")
        assertFalse(screen.contains("Weekly GitHub Action"))
        assertTrue(screen.contains("beats the market"))
        assertTrue(screen.contains("does not beat the market"))
        assertTrue(screen.contains("edge-model-latest"))
        assertTrue(screen.contains("diphunter_sync"))
        val yml = source(".github/workflows/train-edge-model.yml")
        assertTrue(yml.contains("default branch"))
        assertTrue(yml.contains("cron:"))
    }

    private fun source(relative: String): String {
        var dir = File(".").absoluteFile
        for (i in 0 until 6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: break
        }
        error("missing $relative from ${File(".").absolutePath}")
    }
}
