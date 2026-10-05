package com.dirk.kalshiodds.data.local

import com.dirk.kalshiodds.data.local.results.ResultsBundle
import com.dirk.kalshiodds.data.local.results.ResultsExporter
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.signal.config.CredentialBackup
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupTest {
    private val pem = "-----BEGIN PRIVATE KEY-----\n${"A".repeat(80)}\n-----END PRIVATE KEY-----"

    @Test
    fun oneFileRestoresSettingsHistoryAndEncryptedCredentials() {
        val settings = SignalSettings(watchBtc = false, ticketStakeUsd = 3.0, paperTradingEnabled = true)
        val results = ResultsExporter.json(
            ResultsBundle(
                tickets = listOf(
                    TicketAttemptRow(
                        ticker = "KXBTC15M-H",
                        side = "NO",
                        stakeUsd = 4.0,
                        approved = true,
                        result = "submitted",
                        createdAtMs = 50L,
                        clientOrderId = "cid-h"
                    )
                )
            )
        )
        val cipher = CredentialBackup.encrypt("kid-1234", pem, "secret1".toCharArray(), "demo-1", pem)
        val exported = AppBackup.export(settings, results, """[{"ticker":"KXBTC15M-H"}]""", cipher, 9L)
        assertTrue(exported.includesCredentials)
        assertTrue(exported.text.contains(AppBackup.CREDENTIALS_INCLUDED))
        assertFalse(exported.text.contains("BEGIN PRIVATE"))
        assertFalse(exported.text.contains("kid-1234"))
        val restored = AppBackup.restore(exported.text, "secret1".toCharArray())
        assertTrue(restored.settingsJson.contains("\"watchBtc\":false"))
        assertEquals(3.0, org.json.JSONObject(restored.settingsJson).getDouble("ticketStakeUsd"), 0.0)
        assertEquals("KXBTC15M-H", restored.results.batch.tickets.single().ticker)
        assertEquals("cid-h", restored.results.batch.tickets.single().clientOrderId)
        assertTrue(restored.predictionLogJson.contains("KXBTC15M-H"))
        assertEquals("kid-1234", restored.credentials?.keyId)
        assertEquals(pem, restored.credentials?.pem)
        assertEquals("demo-1", restored.credentials?.demoKeyId)
    }

    @Test
    fun wrongPassphraseRestoresSettingsWithoutChangingTheKey() {
        val settings = SignalSettings(watchBtc = true)
        val cipher = CredentialBackup.encrypt("kid-1234", pem, "secret1".toCharArray())
        val exported = AppBackup.export(settings, "{}", """[]""", cipher, 1L)
        val restored = AppBackup.restore(exported.text, "nope12".toCharArray())
        assertNull(restored.credentials)
        assertTrue(restored.credentialNote.contains("Wrong passphrase"))
        assertTrue(restored.settingsJson.contains("\"watchBtc\":true"))
    }

    @Test
    fun restoreDropsModeFlagsAndEthSolRows() {
        val settings = SignalSettings(
            watchBtc = true,
            paperTradingEnabled = false,
            kalshiDemoEnabled = true,
            ticketStakeUsd = 4.0
        )
        val log = """[
            {"ticker":"KXBTC15M-A","series":"KXBTC15M","predictedYes":0.6},
            {"ticker":"KXETH15M-B","series":"KXETH15M","predictedYes":0.7},
            {"ticker":"KXSOL15M-C","series":"KXSOL15M","predictedYes":0.4}
        ]"""
        val exported = AppBackup.export(settings, "{}", log, null, 3L)
        assertTrue(exported.text.contains("paperTradingEnabled"))
        assertTrue(exported.text.contains("kalshiDemoEnabled"))
        val restored = AppBackup.restore(exported.text, null)
        val json = org.json.JSONObject(restored.settingsJson)
        assertFalse(json.has("paperTradingEnabled"))
        assertFalse(json.has("kalshiDemoEnabled"))
        assertEquals(4.0, json.getDouble("ticketStakeUsd"), 0.0)
        assertTrue(restored.predictionLogJson.contains("KXBTC15M-A"))
        assertFalse(restored.predictionLogJson.contains("KXETH15M"))
        assertFalse(restored.predictionLogJson.contains("KXSOL15M"))
        val parsed = com.dirk.kalshiodds.data.local.history.SettingsRestore.parse(
            """{"paperTradingEnabled":false,"ticketStakeUsd":2.0,"kalshiDemoEnabled":true}"""
        )
        assertEquals(null, parsed.paperTradingEnabled)
        assertEquals(2.0, parsed.ticketStakeUsd!!, 0.0)
    }

    @Test
    fun blankPassphraseOmitsCredentials() {
        val exported = AppBackup.export(SignalSettings(), "{}", "[]", null, 1L)
        assertFalse(exported.includesCredentials)
        assertTrue(exported.text.contains(AppBackup.CREDENTIALS_OMITTED))
        val restored = AppBackup.restore(exported.text, null)
        assertNull(restored.credentials)
        assertEquals(AppBackup.CREDENTIALS_OMITTED, restored.credentialNote)
    }
}
