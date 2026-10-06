package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInstallerTest {
    @Test
    fun keepsAnOrdinaryApkName() {
        assertEquals("ClaudeBitcoin-v1.7.0-Claude-130.apk", AppInstaller.localName("ClaudeBitcoin-v1.7.0-Claude-130.apk"))
    }

    @Test
    fun stripsPathAndOddCharacters() {
        val n = AppInstaller.localName("../evil name/..\\x.apk")
        assertTrue(n, !n.contains('/') && !n.contains('\\') && !n.contains(' '))
        assertTrue(n.endsWith(".apk"))
    }

    @Test
    fun alwaysEndsInApk() {
        assertEquals("update.apk", AppInstaller.localName(""))
        assertTrue(AppInstaller.localName("build").endsWith(".apk"))
    }
}
