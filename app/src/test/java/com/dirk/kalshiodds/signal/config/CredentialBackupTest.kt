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
    fun committedDebugKeystoreHasExpectedSha256() {
        val jks = listOf(
            java.io.File("signing/diphunter-debug.jks"),
            java.io.File("../signing/diphunter-debug.jks"),
            java.io.File("app/signing/diphunter-debug.jks")
        ).firstOrNull { it.isFile } ?: java.io.File("signing/diphunter-debug.jks")
        assertTrue("missing debug keystore at ${jks.absolutePath}", jks.isFile)
        val proc = ProcessBuilder(
            "keytool", "-list", "-v",
            "-keystore", jks.absolutePath,
            "-storepass", "diphunter-debug",
            "-alias", "diphunter-debug"
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        assertTrue(out.contains("64:E2:A4:3A:68:97:C4:55:6A:36:B8:2E:A3:1D:C8:95:50:C6:5E:56:B0:53:65:8E:14:38:BD:F3:C4:CC:46:08"))
    }
}
