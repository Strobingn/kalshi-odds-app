package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun labelReadsBuildConfigValues() {
        assertEquals(BuildConfig.VERSION_NAME, AppVersion.versionName)
        assertEquals(BuildConfig.VERSION_CODE, AppVersion.versionCode)
        assertEquals("Chat Bitcoin v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", AppVersion.label)
        assertTrue(AppVersion.label.startsWith("Chat Bitcoin v"))
        assertTrue(AppVersion.label.endsWith("(${BuildConfig.VERSION_CODE})"))
    }
    @Test fun debugVersionIncludesBuildNumberOrLocalMarker() {
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        assertEquals("1.0.${run ?: "local"}", BuildConfig.VERSION_NAME)
        assertEquals(1_000_000 + (run ?: 0), BuildConfig.VERSION_CODE)
        assertEquals("Chat Bitcoin v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", AppVersion.label)
    }
}
