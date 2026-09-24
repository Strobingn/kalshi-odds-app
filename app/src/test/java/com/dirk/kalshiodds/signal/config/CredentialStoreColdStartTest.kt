package com.dirk.kalshiodds.signal.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/**
 * JVM stand-in for a Robolectric EncryptedSharedPreferences cold start:
 * save, drop the instance, construct a new store on the same file
 * (upgrade / process death), and assert the key loads. PEM is never
 * printed in assertion messages.
 */
class CredentialStoreColdStartTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pem = "-----BEGIN PRIVATE KEY-----\n${"C".repeat(80)}\n-----END PRIVATE KEY-----"

    @Test
    fun saveThenNewInstanceLoads() {
        val file = tmp.newFile("kalshi_signal_secrets")
        FileBackedCredentialStore(file).apply {
            apiKeyId = "kalshi-prod-d233"
            privateKeyPem = pem
        }
        val cold = FileBackedCredentialStore(file)
        assertEquals("kalshi-prod-d233", cold.apiKeyId)
        assertTrue(SecureCredentialStore.looksLikePem(cold.privateKeyPem))
        assertEquals(pem, cold.privateKeyPem)
        assertTrue(cold.hasCredentials)
    }

    @Test
    fun migratePlainPrefsOnceThenDestWins() {
        val legacy = tmp.newFile("kalshi_signal_secrets_legacy")
        val dest = tmp.newFile("kalshi_signal_secrets")
        FileBackedCredentialStore(legacy).apply {
            apiKeyId = "old-plain-d233"
            privateKeyPem = pem
        }
        val first = FileBackedCredentialStore(dest, legacy = legacy)
        assertEquals("old-plain-d233", first.apiKeyId)
        assertTrue(first.hasCredentials)
        FileBackedCredentialStore(legacy).apply {
            apiKeyId = "should-not-overwrite"
            privateKeyPem = pem
        }
        val second = FileBackedCredentialStore(dest, legacy = legacy)
        assertEquals("old-plain-d233", second.apiKeyId)
        assertFalse(CredentialMigration.destNeedsSource(second.apiKeyId, second.privateKeyPem, "x", pem))
    }
}

/**
 * Properties-file vault using the same key names and [CredentialMigration]
 * rule as [SecureCredentialStore].
 */
class FileBackedCredentialStore(
    private val file: File,
    private val legacy: File? = null
) {
    private val props = Properties()

    init {
        if (file.isFile) file.inputStream().use { props.load(it) }
        val destId = props.getProperty(KEY_ID, "")
        val destPem = props.getProperty(KEY_PEM, "")
        if (legacy != null && legacy.isFile) {
            val src = Properties()
            legacy.inputStream().use { src.load(it) }
            val srcId = src.getProperty(KEY_ID, "")
            val srcPem = src.getProperty(KEY_PEM, "")
            if (CredentialMigration.destNeedsSource(destId, destPem, srcId, srcPem)) {
                props.setProperty(KEY_ID, srcId)
                props.setProperty(KEY_PEM, srcPem)
                persist()
                src.remove(KEY_ID)
                src.remove(KEY_PEM)
                legacy.outputStream().use { src.store(it, null) }
            }
        }
    }

    var apiKeyId: String
        get() = props.getProperty(KEY_ID, "")
        set(value) {
            props.setProperty(KEY_ID, value.trim())
            persist()
        }

    var privateKeyPem: String
        get() = props.getProperty(KEY_PEM, "")
        set(value) {
            props.setProperty(KEY_PEM, value.trim())
            persist()
        }

    val hasCredentials: Boolean
        get() = apiKeyId.isNotBlank() && SecureCredentialStore.looksLikePem(privateKeyPem)

    private fun persist() {
        file.outputStream().use { props.store(it, "diphunter-test-vault") }
    }

    companion object {
        const val KEY_ID = "api_key_id"
        const val KEY_PEM = "private_key_pem"
    }
}
