package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.model.SignalAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LazyListKeysTest {

    @Test
    fun dirkDuplicateDisplayRowsGetUniqueStableKeys() {
        val title = "BTC · 1:45 PM window"
        val call = "NO BET"
        val modelLine = "Model 70% vs market 61% · edge +9 pts"
        val crashKey = "$title-$call-$modelLine"
        assertEquals(
            "BTC · 1:45 PM window-NO BET-Model 70% vs market 61% · edge +9 pts",
            crashKey
        )

        val first = SignalAlert(
            id = "sig-1",
            ticker = "KXBTC15M-26SEP251345-45",
            series = "KXBTC15M",
            deltaPp = 9.0,
            fairValuePp = 39.0,
            marketMidPp = 61.0,
            reason = "QUIET/LATE · AI 70% vs mkt 61%",
            createdAtMs = 1_725_000_000_000L,
            receiveElapsedNanos = 0L,
            predictedSide = "NO"
        )
        val second = first.copy(id = "sig-2", createdAtMs = 1_725_000_060_000L)
        val cards = listOf(SignalCopy.card(first), SignalCopy.card(second))
        assertEquals(title, cards[0].title)
        assertEquals(call, cards[0].call)
        assertEquals(modelLine, cards[0].modelLine)
        assertEquals(cards[0].title, cards[1].title)
        assertEquals(cards[0].call, cards[1].call)
        assertEquals(cards[0].modelLine, cards[1].modelLine)

        val oldKeys = cards.map { "${it.title}-${it.call}-${it.modelLine}" }
        assertEquals(1, oldKeys.distinct().size)

        val keys = LazyListKeys.assign(cards) { it.stableKey }
        assertEquals(2, keys.toSet().size)
        assertTrue(keys[0].contains("KXBTC15M"))
        assertTrue(keys[0].contains("sig-1"))
        assertTrue(keys[1].contains("sig-2"))
        assertFalse(keys.any { it.contains("NO BET") })
        assertFalse(keys.any { it.contains("edge +9") })
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun indexSuffixDedupesIdenticalBaseKeys() {
        val rows = listOf("KXBTC15M:1", "KXBTC15M:1", "KXETH15M:2")
        val keys = LazyListKeys.assign(rows) { it }
        assertEquals(listOf("KXBTC15M:1", "KXBTC15M:1#1", "KXETH15M:2"), keys)
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun everyLazyItemsKeyUsesLazyListKeysNotDisplayText() {
        val files = listOf(
            "app/src/main/java/com/dirk/kalshiodds/ui/SignalHistoryScreen.kt",
            "app/src/main/java/com/dirk/kalshiodds/ui/ScorecardScreen.kt",
            "app/src/main/java/com/dirk/kalshiodds/ui/HomeScreen.kt",
            "app/src/main/java/com/dirk/kalshiodds/ui/HistoryScreen.kt"
        ).map { File(it) }.filter { it.isFile }
        assertEquals(4, files.size)
        files.forEach { file ->
            val src = file.readText()
            assertTrue("${file.name} must use LazyListKeys", src.contains("LazyListKeys.keyed"))
            assertFalse(
                "${file.name} must not key on title-call-modelLine",
                src.contains("it.title}-{it.call}-{it.modelLine}")
            )
            assertFalse(
                "${file.name} must not key Scorecard recent on display line",
                src.contains("it.ticker}-\${it.settledAtMs}-\${it.line}")
            )
        }
    }
}
