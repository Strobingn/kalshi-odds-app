package com.dirk.kalshiodds.signal.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCredentialVaultTest {
    private val pem = "-----BEGIN PRIVATE KEY-----\n${"K".repeat(80)}\n-----END PRIVATE KEY-----"

    @Test
    fun keystoreFailureRefusesSaveAndDoesNotWritePlaintext() {
        val legacy = LiveCredentialVault.RecordingBucket()
        val fallback = LiveCredentialVault.RecordingBucket()
        val vault = LiveCredentialVault(encrypted = null, legacy = legacy, fallback = fallback)
        val saved = vault.save("live-key", pem)
        assertTrue(saved is CredentialSave.Refused)
        assertEquals(LiveCredentialVault.REFUSE, (saved as CredentialSave.Refused).reason)
        assertFalse(vault.hasCredentials)
        assertFalse(vault.encryptedReady)
        assertEquals(0, legacy.writes)
        assertEquals(0, fallback.writes)
        assertTrue(legacy.values.isEmpty())
        assertTrue(fallback.values.isEmpty())
    }

    @Test
    fun existingEncryptedKeyStaysReadable() {
        val encrypted = LiveCredentialVault.memoryBucket(
            mapOf(LiveCredentialVault.KEY_ID to "keep-me", LiveCredentialVault.KEY_PEM to pem)
        )
        val vault = LiveCredentialVault(encrypted = encrypted)
        assertTrue(vault.hasCredentials)
        assertEquals("keep-me", vault.apiKeyId)
        assertTrue(SecureCredentialStore.looksLikePem(vault.privateKeyPem))
    }

    @Test
    fun upgradeAbsorbsPlaintextOnceThenClearsIt() {
        val encrypted = LiveCredentialVault.memoryBucket()
        val legacy = LiveCredentialVault.memoryBucket(
            mapOf(LiveCredentialVault.KEY_ID to "old-plain", LiveCredentialVault.KEY_PEM to pem)
        )
        val vault = LiveCredentialVault(encrypted = encrypted, legacy = legacy)
        assertEquals("old-plain", vault.apiKeyId)
        assertTrue(vault.hasCredentials)
        assertFalse(legacy.values.containsKey(LiveCredentialVault.KEY_ID))
        assertFalse(legacy.values.containsKey(LiveCredentialVault.KEY_PEM))
        val again = LiveCredentialVault(encrypted = encrypted, legacy = LiveCredentialVault.memoryBucket(
            mapOf(LiveCredentialVault.KEY_ID to "should-not-win", LiveCredentialVault.KEY_PEM to pem)
        ))
        assertEquals("old-plain", again.apiKeyId)
    }

    @Test
    fun storeSourceNeverReturnsThePlaintextFallback() {
        val src = listOf(
            java.io.File("app/src/main/java/com/dirk/kalshiodds/signal/config/SecureCredentialStore.kt"),
            java.io.File("src/main/java/com/dirk/kalshiodds/signal/config/SecureCredentialStore.kt")
        ).first { it.isFile }.readText()
        assertFalse(src.contains("return fallback"))
        assertFalse(src.contains("using private prefs"))
        assertTrue(src.contains("refusing plaintext fallback"))
        assertTrue(src.contains("trySave"))
    }
}
