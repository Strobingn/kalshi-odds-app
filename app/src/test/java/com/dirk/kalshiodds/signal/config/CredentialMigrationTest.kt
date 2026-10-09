package com.dirk.kalshiodds.signal.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialMigrationTest {
    private val pem = "-----BEGIN PRIVATE KEY-----\n${"A".repeat(80)}\n-----END PRIVATE KEY-----"

    @Test
    fun destWinsWhenAlreadyGood() {
        assertFalse(CredentialMigration.destNeedsSource("good-id", pem, "old-id", pem))
    }

    @Test
    fun emptyDestTakesSource() {
        assertTrue(CredentialMigration.destNeedsSource("", "", "old-id", pem))
    }

    @Test
    fun looksLikePemRequiresBeginEnd() {
        assertTrue(SecureCredentialStore.looksLikePem(pem))
        assertFalse(SecureCredentialStore.looksLikePem("not-a-key"))
        assertFalse(SecureCredentialStore.looksLikePem("BEGIN PRIVATE"))
    }
}
