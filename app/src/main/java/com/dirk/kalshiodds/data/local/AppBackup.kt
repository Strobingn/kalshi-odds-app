package com.dirk.kalshiodds.data.local

import com.dirk.kalshiodds.data.importing.ResultsImporter
import com.dirk.kalshiodds.signal.config.CredentialBackup
import com.dirk.kalshiodds.signal.config.SignalSettings
import java.io.StringReader
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * One Settings file that restores the app after a signing-key reinstall.
 * Settings and results/history are plain JSON. API credentials are optional,
 * clearly labelled, and encrypted with a passphrase the user types.
 * The file never contains a plaintext PEM.
 */
object AppBackup {
    const val FORMAT = "diphunter-full-backup-v1"
    const val ABOUT =
        "DipHunter backup of settings, results, and history. " +
            "The credentials block, when present, is encrypted with your passphrase " +
            "(PBKDF2-HMAC-SHA256 and AES-256-GCM) and is not readable without it."
    const val CREDENTIALS_INCLUDED =
        "OPTIONAL Kalshi API credentials. Encrypted with the passphrase you typed. " +
            "Not plaintext. Restore asks for the same passphrase. Leave the passphrase blank when exporting to omit this block."
    const val CREDENTIALS_OMITTED = "API credentials were not included in this backup."

    data class Exported(
        val text: String,
        val includesCredentials: Boolean
    )

    data class Restored(
        val settingsJson: String,
        val results: ResultsImporter.Parsed,
        val predictionLogJson: String,
        val credentials: CredentialBackup.Contents?,
        val credentialNote: String
    )

    class BadFile(message: String = "Not a DipHunter full backup") : IllegalArgumentException(message)

    /** Paper / live / demo mode stays whatever the phone is using now. */
    fun stripTradingMode(json: String): String {
        val o = runCatching { JSONObject(json) }.getOrElse { return json }
        o.remove("paperTradingEnabled")
        o.remove("kalshiDemoEnabled")
        return o.toString()
    }

    /** Restore must not bring ETH/SOL rows back into the BTC learner. */
    fun btcPredictionLog(raw: String): String {
        val arr = runCatching { JSONArray(raw) }.getOrElse { return "[]" }
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (com.dirk.kalshiodds.domain.CryptoMarkets.isBtc15m(row.optString("series"), row.optString("ticker"))) {
                kept.put(row)
            }
        }
        return kept.toString()
    }

    fun settingsJson(settings: SignalSettings): String {
        val o = JSONObject()
        o.put("watchBtc", settings.watchBtc)
        o.put("watchEth", settings.watchEth)
        o.put("watchSol", settings.watchSol)
        o.put("notificationsEnabled", settings.notificationsEnabled)
        o.put("opportunityAlertsEnabled", settings.opportunityAlertsEnabled)
        o.put("opportunityQuiet", settings.opportunityQuiet)
        o.put("liveSignalsEnabled", settings.liveSignalsEnabled)
        o.put("subscribeTrades", settings.subscribeTrades)
        o.put("edgeThresholdPp", settings.edgeThresholdPp)
        o.put("autoTuneEnabled", settings.autoTuneEnabled)
        o.put("autoTuneManualOverride", settings.autoTuneManualOverride)
        o.put("minConfidence", settings.minConfidence)
        o.put("minLiquidity", settings.minLiquidity)
        o.put("maxSpreadCents", settings.maxSpreadCents)
        o.put("hideWeakOpportunities", settings.hideWeakOpportunities)
        o.put("bankrollUsd", settings.bankrollUsd)
        o.put("feeRate", settings.feeRate)
        o.put("ticketsEnabled", settings.ticketsEnabled)
        o.put("paperTradingEnabled", settings.paperTradingEnabled)
        o.put("ticketStakeUsd", settings.ticketStakeUsd)
        o.put("ticketRespectGates", settings.ticketRespectGates)
        o.put("hunterValueStakeUsd", settings.hunterValueStakeUsd)
        o.put("hunterValuePayoutUsd", settings.hunterValuePayoutUsd)
        o.put("longShotMaxAsk", settings.longShotMaxAsk)
        o.put("winTargetEnabled", settings.winTargetEnabled)
        o.put("winTargetUsd", settings.winTargetUsd)
        o.put("winTargetBankrollPct", settings.winTargetBankrollPct)
        if (settings.winTargetAbsCapUsd != null) o.put("winTargetAbsCapUsd", settings.winTargetAbsCapUsd)
        o.put("minProfitIfWinUsd", settings.minProfitIfWinUsd)
        o.put("kalshiDemoEnabled", settings.kalshiDemoEnabled)
        o.put("streakPauseN", settings.streakPauseN)
        o.put("drawdownUsd", settings.drawdownUsd)
        o.put("resumeOnNewSession", settings.resumeOnNewSession)
        return o.toString()
    }

    fun export(
        settings: SignalSettings,
        resultsJson: String,
        predictionLogJson: String,
        credentialBytes: ByteArray?,
        exportedAtMs: Long
    ): Exported {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("exportedAtMs", exportedAtMs)
        root.put("about", ABOUT)
        root.put("settings", JSONObject(settingsJson(settings)))
        val results = runCatching { JSONObject(resultsJson) }.getOrElse { JSONObject() }
        root.put("results", results)
        val log = runCatching { JSONArray(predictionLogJson) }.getOrElse { JSONArray() }
        root.put("predictionLog", log)
        val creds = JSONObject()
        if (credentialBytes != null && credentialBytes.isNotEmpty()) {
            creds.put("included", true)
            creds.put("label", CREDENTIALS_INCLUDED)
            creds.put("encoding", "base64")
            creds.put("cipher", Base64.getEncoder().encodeToString(credentialBytes))
        } else {
            creds.put("included", false)
            creds.put("label", CREDENTIALS_OMITTED)
        }
        root.put("credentials", creds)
        return Exported(root.toString(), credentialBytes != null && credentialBytes.isNotEmpty())
    }

    fun restore(text: String, passphrase: CharArray?): Restored {
        val root = runCatching { JSONObject(text) }.getOrElse { throw BadFile() }
        if (root.optString("format") != FORMAT) throw BadFile()
        val settings = stripTradingMode(root.optJSONObject("settings")?.toString().orEmpty())
        val resultsObj = root.optJSONObject("results")
        val results = if (resultsObj == null) {
            ResultsImporter.Parsed(
                com.dirk.kalshiodds.data.importing.ImportBatch(),
                com.dirk.kalshiodds.data.importing.ImportSummary("empty", message = "No results in backup")
            )
        } else {
            ResultsImporter.parse(StringReader(resultsObj.toString()))
        }
        val prediction = btcPredictionLog(root.optJSONArray("predictionLog")?.toString() ?: "[]")
        val creds = root.optJSONObject("credentials")
        val included = creds?.optBoolean("included") == true
        if (!included) {
            return Restored(settings, results, prediction, null, CREDENTIALS_OMITTED)
        }
        val cipher = creds?.optString("cipher").orEmpty()
        if (cipher.isBlank()) {
            return Restored(settings, results, prediction, null, "Backup labelled credentials but the encrypted block was empty. Keys were not changed.")
        }
        if (passphrase == null || passphrase.isEmpty()) {
            return Restored(
                settings,
                results,
                prediction,
                null,
                "This backup includes encrypted API credentials. Enter the passphrase to restore them. Settings and history were still restored; the key was not changed."
            )
        }
        return try {
            val bytes = Base64.getDecoder().decode(cipher)
            val contents = CredentialBackup.decryptAll(bytes, passphrase)
            Restored(
                settings,
                results,
                prediction,
                contents,
                "Kalshi key restored (${CredentialBackup.maskedKeyId(contents.keyId)})"
            )
        } catch (_: CredentialBackup.WrongPassphrase) {
            Restored(
                settings,
                results,
                prediction,
                null,
                "Wrong passphrase — API key was not changed. Settings and history were still restored."
            )
        } catch (_: IllegalArgumentException) {
            Restored(
                settings,
                results,
                prediction,
                null,
                "Could not read the encrypted key block. Settings and history were still restored; the key was not changed."
            )
        }
    }
}
