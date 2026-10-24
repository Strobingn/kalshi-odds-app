package com.dirk.kalshiodds.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupTest {
    private val sample: Map<String, Any> = mapOf(
        "on" to true, "n" to 42, "big" to 9_000_000_000L, "f" to 0.25f, "d" to 0.125,
        "s" to "hello \"quoted\" \n line", "set" to setOf("a", "b")
    )

    @Test
    fun sealAndOpenRoundTrip() {
        val text = """{"x":"secret key 123"}"""
        val bytes = SettingsBackup.seal(text, "correct horse".toCharArray())
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret key"))
        assertEquals(text, SettingsBackup.open(bytes, "correct horse".toCharArray()))
    }

    @Test
    fun wrongPassphraseIsRefused() {
        val bytes = SettingsBackup.seal("{}", "correct horse".toCharArray())
        assertThrows(SettingsBackup.WrongPassphrase::class.java) { SettingsBackup.open(bytes, "wrong horse".toCharArray()) }
    }

    @Test
    fun otherFilesAreRefused() {
        assertThrows(SettingsBackup.BadFile::class.java) { SettingsBackup.open("hello world, not a backup".toByteArray(), "whatever".toCharArray()) }
    }

    @Test
    fun shortPassphraseIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.seal("{}", "abc".toCharArray()) }
    }

    @Test
    fun typedValuesRoundTripThroughJson() {
        val back = SettingsBackup.decode(SettingsBackup.parse(SettingsBackup.encode(sample).toString()))
        assertEquals(sample, back)
        assertTrue(back["n"] is Int)
        assertTrue(back["big"] is Long)
        assertTrue(back["f"] is Float)
    }

    @Test
    fun sharedPreferencesRestoreExactly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences("backup_test", Context.MODE_PRIVATE)
        sp.edit().putString("stale", "gone after restore").commit()
        SettingsBackup.intoShared(sp, SettingsBackup.decode(SettingsBackup.encode(sample)))
        val all = sp.all
        assertFalse(all.containsKey("stale"))
        assertEquals(42, all["n"])
        assertEquals(9_000_000_000L, all["big"])
        assertEquals(setOf("a", "b"), all["set"])
        assertEquals("hello \"quoted\" \n line", all["s"])
    }
}
