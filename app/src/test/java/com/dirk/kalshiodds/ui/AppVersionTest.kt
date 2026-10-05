package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.BuildConfig
import com.dirk.kalshiodds.data.api.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "Bitcoin Kalshi v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            AppVersion.label
        )
        assertTrue(AppVersion.label.startsWith("Bitcoin Kalshi v"))
        assertTrue(AppVersion.label.endsWith("(${BuildConfig.VERSION_CODE})"))
    }

    @Test
    fun userAgentUsesTheInstalledVersionName() {
        val ua = NetworkModule.USER_AGENT
        assertEquals(BuildConfig.VERSION_NAME, AppVersion.versionName)
        assertTrue(ua.startsWith("DipHunter/${AppVersion.versionName}"))
        assertTrue(ua.contains("Bitcoin Kalshi"))
        assertFalse(ua.contains("DipHunter/0.3.11"))
    }
}
