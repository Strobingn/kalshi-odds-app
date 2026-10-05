package com.dirk.kalshiodds.signal.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

class UnencryptedLiveKeyGuardTest {
    @Test
    fun messageTellsTheUserTheKeyWasNotSaved() {
        assertTrue(CredentialWriteGuard.REJECT_UNENCRYPTED.contains("not saved"))
        assertTrue(CredentialWriteGuard.REJECT_UNENCRYPTED.contains("plain storage"))
    }
}

@RunWith(RobolectricTestRunner::class)
class LiveKeyEncryptionTest {
    @Test
    fun refusesLiveKeyWhenKeystoreCannotEncrypt() {
        val store = SecureCredentialStore(RuntimeEnvironment.getApplication())
        if (store.encryptionAvailable) return
        val pem = "-----BEGIN PRIVATE KEY-----\n${"A".repeat(40)}\n-----END PRIVATE KEY-----"
        val error = store.saveLiveCredentials("live-key", pem)
        assertEquals(CredentialWriteGuard.REJECT_UNENCRYPTED, error)
        assertEquals("", store.apiKeyId)
        assertEquals("", store.privateKeyPem)
    }
}
