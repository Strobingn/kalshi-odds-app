package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.ScalpControls
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 1.0.6: 1.0.5 crashed every chat send with ExceptionInInitializerError because SettingTools' tool-call Regex
 * started a character class with "[:", which Android's ICU regex engine rejects (POSIX set syntax) while the JVM
 * accepts it. Force-initialize every chat/tool object and guard regex sources against ICU-only failures.
 */
class ChatObjectsInitTest {
    @Test fun allChatAndToolObjectsInitialize() {
        for (n in listOf("SettingTools", "ChatHistoryStore", "LlmClient", "LlmPaperStrategy", "LlmSettings", "AiChatController"))
            Class.forName("com.dirk.kalshiodds.llm.$n", true, javaClass.classLoader)
        assertTrue(SettingTools.describe(ScalpControls()).isNotBlank())
        assertEquals(listOf("stopCents" to "5"), SettingTools.parseCalls("ok\nSET_SETTING stopCents = 5"))
        assertEquals(listOf("stopCents" to "5"), SettingTools.parseCalls("SET_SETTING(stopCents, 5)"))
        assertEquals(listOf("stopCents" to "5"), SettingTools.parseCalls("SET_SETTING: stopCents = 5"))
    }

    @Test fun noIcuHostileCharacterClassesInLlmRegexes() {
        val dir = listOf("src/main/java/com/dirk/kalshiodds/llm", "app/src/main/java/com/dirk/kalshiodds/llm").map(::File).first { it.isDirectory }
        dir.walk().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if ("Regex(" in line || "toRegex" in line)
                    assertFalse("${f.name}:${i + 1} has a '[:' character class (throws on Android ICU)", Regex("""(?<!\[)\[:(?![a-zA-Z]+:\])""").containsMatchIn(line))
            }
        }
    }

    @Test fun errorBubbleShowsRootCause() {
        val e = ExceptionInInitializerError(IllegalArgumentException("bad pattern"))
        assertEquals("IllegalArgumentException: bad pattern", rootCauseMessage(e))
        assertEquals("IllegalStateException", rootCauseMessage(IllegalStateException()))
    }
}
