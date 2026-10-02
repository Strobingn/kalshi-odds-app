package com.dirk.kalshiodds.signal.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialBackupTest {
    @Test
    fun roundTrip() {
        val pem = """
            -----BEGIN PRIVATE KEY-----
            ${"A".repeat(80)}
            -----END PRIVATE KEY-----
        """.trimIndent()
        val bytes = CredentialBackup.encrypt("key-abc-d233", pem, "hunter2".toCharArray())
        val (id, out) = CredentialBackup.decrypt(bytes, "hunter2".toCharArray())
        assertEquals("key-abc-d233", id)
        assertTrue(out.contains("BEGIN PRIVATE KEY"))
    }

    @Test(expected = CredentialBackup.WrongPassphrase::class)
    fun wrongPassphraseFails() {
        val pem = "-----BEGIN PRIVATE KEY-----\n${"B".repeat(80)}\n-----END PRIVATE KEY-----"
        val bytes = CredentialBackup.encrypt("id", pem, "correct-horse".toCharArray())
        CredentialBackup.decrypt(bytes, "wrong-pass".toCharArray())
    }

    @Test
    fun demoBlockRoundTrip() {
        val pem = "-----BEGIN PRIVATE KEY-----\n${"A".repeat(80)}\n-----END PRIVATE KEY-----"
        val demo = "-----BEGIN PRIVATE KEY-----\n${"D".repeat(80)}\n-----END PRIVATE KEY-----"
        val bytes = CredentialBackup.encrypt("live-id", pem, "hunter2".toCharArray(), "demo-id", demo)
        val all = CredentialBackup.decryptAll(bytes, "hunter2".toCharArray())
        assertEquals("live-id", all.keyId)
        assertEquals("demo-id", all.demoKeyId)
        assertTrue(all.pem.contains("BEGIN PRIVATE KEY"))
        assertTrue(all.demoPem.contains("BEGIN PRIVATE KEY"))
        val liveOnly = CredentialBackup.encrypt("live-id", pem, "hunter2".toCharArray())
        val back = CredentialBackup.decryptAll(liveOnly, "hunter2".toCharArray())
        assertEquals("", back.demoKeyId)
    }

    @Test
    fun maskedShowsLast4() {
        assertEquals("····d233", CredentialBackup.maskedKeyId("kalshi-prod-d233"))
        assertEquals("(none)", CredentialBackup.maskedKeyId(""))
    }

    @Test
    fun committedDebugKeystoreIsNotInTheTree() {
        val present = listOf(
            java.io.File("signing/diphunter-debug.jks"),
            java.io.File("../signing/diphunter-debug.jks"),
            java.io.File("app/signing/diphunter-debug.jks")
        ).filter { it.isFile }
        assertTrue("keystore must not be committed: $present", present.isEmpty())
    }
}
