package com.dirk.kalshiodds.signal.config

import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreLockoutTest {
    private val pem = "-----BEGIN PRIVATE KEY-----\n${"K".repeat(80)}\n-----END PRIVATE KEY-----"

    @Test
    fun aeadBadTagResetsBrokenPrefsAndSavesReplacementIntoEncryptedStore() {
        var resets = 0
        var creates = 0
        val encrypted = LiveCredentialVault.RecordingBucket()
        val plaintext = LiveCredentialVault.RecordingBucket()
        val opened = EncryptedStoreOpen(
            create = {
                creates += 1
                if (creates == 1) throw AEADBadTagException("tag mismatch")
                encrypted
            },
            resetBroken = { resets += 1 }
        ).open()
        assertSame(encrypted, opened)
        assertEquals(1, resets)
        val vault = LiveCredentialVault(encrypted = opened, legacy = plaintext, fallback = plaintext)
        val saved = vault.save("replacement-key", pem)
        assertTrue(saved is CredentialSave.Stored)
        assertEquals("replacement-key", encrypted.values[LiveCredentialVault.KEY_ID])
        assertEquals(pem, encrypted.values[LiveCredentialVault.KEY_PEM])
        assertTrue(plaintext.values.isEmpty())
    }

    @Test
    fun keyStoreExceptionResetsAndDoesNotTouchPlaintext() {
        var resets = 0
        val encrypted = LiveCredentialVault.RecordingBucket()
        val plaintext = LiveCredentialVault.RecordingBucket()
        var creates = 0
        val opened = EncryptedStoreOpen(
            create = {
                creates += 1
                if (creates == 1) throw KeyStoreException("AndroidKeyStore locked")
                encrypted
            },
            resetBroken = { resets += 1 }
        ).open()
        val vault = LiveCredentialVault(encrypted = opened, fallback = plaintext)
        assertTrue(vault.save("imported", pem) is CredentialSave.Stored)
        assertEquals(1, resets)
        assertEquals(0, plaintext.writes)
    }

    @Test
    fun unrelatedOpenFailureDoesNotDeletePrefs() {
        var resets = 0
        val opened = EncryptedStoreOpen<LiveCredentialVault.Bucket>(
            create = { throw java.io.IOException("disk") },
            resetBroken = { resets += 1 }
        ).open()
        assertNull(opened)
        assertEquals(0, resets)
        val plaintext = LiveCredentialVault.RecordingBucket()
        val vault = LiveCredentialVault(encrypted = null, legacy = plaintext, fallback = plaintext)
        assertTrue(vault.save("nope", pem) is CredentialSave.Refused)
        assertEquals(0, plaintext.writes)
    }
}
