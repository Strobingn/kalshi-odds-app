package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version label must be built from [BuildConfig], not a hardcoded
 * string that can drift from versionName / versionCode in Gradle.
 */
class AppVersionTest {

    @Test
    fun labelReadsBuildConfigValues() {
        assertEquals(BuildConfig.VERSION_NAME, AppVersion.versionName)
        assertEquals(BuildConfig.VERSION_CODE, AppVersion.versionCode)
        assertEquals(
            "DipHunter v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            AppVersion.label
        )
        assertTrue(AppVersion.label.startsWith("DipHunter v"))
        assertTrue(AppVersion.label.endsWith("(${BuildConfig.VERSION_CODE})"))
    }
}
