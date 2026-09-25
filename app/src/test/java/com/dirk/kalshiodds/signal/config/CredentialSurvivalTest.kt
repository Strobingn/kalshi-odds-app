package com.dirk.kalshiodds.signal.config

import com.dirk.kalshiodds.signal.paper.PaperBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialSurvivalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pem = "-----BEGIN PRIVATE KEY-----\n${"C".repeat(80)}\n-----END PRIVATE KEY-----"

    @Test
    fun blankSettingsSaveDoesNotEraseExistingKey() {
        val file = tmp.newFile("kalshi_signal_secrets")
        val store = FileBackedCredentialStore(file).apply {
            apiKeyId = "keep-me"
            privateKeyPem = pem
        }
        assertNull(CredentialWriteGuard.rejectReason("keep-me", pem))
        assertEquals(CredentialWriteGuard.REJECT_BLANK, CredentialWriteGuard.rejectReason("", ""))
        assertEquals(CredentialWriteGuard.REJECT_BLANK, CredentialWriteGuard.rejectReason("keep-me", ""))
        assertEquals(CredentialWriteGuard.REJECT_BLANK, CredentialWriteGuard.rejectReason("", pem))
        if (CredentialWriteGuard.rejectReason("", "") == null) {
            store.apiKeyId = ""
            store.privateKeyPem = ""
        }
        val cold = FileBackedCredentialStore(file)
        assertEquals("keep-me", cold.apiKeyId)
        assertTrue(SecureCredentialStore.looksLikePem(cold.privateKeyPem))
    }

    @Test
    fun paperBookResetDoesNotTouchVault() {
        val file = tmp.newFile("kalshi_signal_secrets")
        FileBackedCredentialStore(file).apply {
            apiKeyId = "keep-me"
            privateKeyPem = pem
        }
        val book = PaperBook()
        book.reset()
        assertEquals(100.0, book.snapshot().cashUsd, 1e-9)
        val cold = FileBackedCredentialStore(file)
        assertEquals("keep-me", cold.apiKeyId)
        assertTrue(cold.hasCredentials)
    }

    @Test
    fun decryptFailureSurfacesErrorAndLeavesVault() {
        val file = tmp.newFile("kalshi_signal_secrets")
        FileBackedCredentialStore(file).apply {
            apiKeyId = "keep-me"
            privateKeyPem = pem
        }
        val bytes = CredentialBackup.encrypt("keep-me", pem, "correct-horse".toCharArray())
        val err = try {
            CredentialBackup.decryptAll(bytes, "wrong".toCharArray())
            null
        } catch (e: CredentialBackup.WrongPassphrase) {
            e.message
        }
        assertEquals("wrong passphrase", err)
        assertNotNull(err)
        val cold = FileBackedCredentialStore(file)
        assertEquals("keep-me", cold.apiKeyId)
    }

    @Test
    fun extraSecretsAreExcludedFromBackupRules() {
        val roots = listOf(
            java.io.File("src/main/res/xml"),
            java.io.File("app/src/main/res/xml")
        )
        val backup = roots.map { java.io.File(it, "backup_rules.xml") }.first { it.isFile }.readText()
        val extract = roots.map { java.io.File(it, "data_extraction_rules.xml") }.first { it.isFile }.readText()
        assertTrue(backup.contains("diphunter_extra_secrets.xml"))
        assertTrue(extract.contains("diphunter_extra_secrets.xml"))
        assertTrue(backup.contains("kalshi_signal_secrets.xml"))
    }
}
