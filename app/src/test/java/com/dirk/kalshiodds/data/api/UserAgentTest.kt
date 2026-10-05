package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentTest {
    @Test
    fun versionComesFromBuildConfigWithoutAPersonalName() {
        val ua = NetworkModule.USER_AGENT
        assertTrue(ua.startsWith("DipHunter/${BuildConfig.VERSION_NAME}"))
        assertTrue(ua.contains("(Android)"))
        assertFalse(ua.contains("Dirk"))
        assertFalse(ua.contains("Diggler"))
        assertFalse(ua.contains("0.3.11"))
    }
}
