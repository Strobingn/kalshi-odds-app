package com.dirk.kalshiodds.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsExtendedAiCopyTest {

    @Test
    fun extendedAiSectionUsesTenDollarMaxNotConfiguredFive() {
        val src = listOf(
            File("src/main/java/com/dirk/kalshiodds/ui/SettingsScreen.kt"),
            File("app/src/main/java/com/dirk/kalshiodds/ui/SettingsScreen.kt")
        ).first { it.isFile }.readText()
        val start = src.indexOf("Extended AI (0.3.0")
        assertTrue("Extended AI section missing", start >= 0)
        val end = src.indexOf("Section(", start + 10).let { if (it < 0) src.length else it }
        val section = src.substring(start, end)
        assertTrue(section.contains("\$10 max"))
        assertFalse(section.contains("configured \$5 default"))
        assertFalse(section.contains("\$5 default"))
    }
}
