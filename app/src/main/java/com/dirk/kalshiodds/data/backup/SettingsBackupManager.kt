package com.dirk.kalshiodds.data.backup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dirk.kalshiodds.data.prefs.DataPrefs
import com.dirk.kalshiodds.signal.config.SecureExtraStore
import com.dirk.kalshiodds.signal.config.SignalPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Keeps the settings backup ([SettingsBackup]) in Downloads/ClaudeBitcoin up to date while a passphrase is set
 * (every 10 minutes and when the app goes to the background, only when something changed), and restores one.
 * Restoring writes every store and then restarts the app, so nothing running keeps the old values.
 */
class SettingsBackupManager(
    context: Context,
    private val preferences: SignalPreferences,
    private val dataPrefs: DataPrefs,
    private val secrets: SecureExtraStore,
    private val appVersion: String
) {
    private val app = context.applicationContext

    data class Status(val on: Boolean, val lastMs: Long? = null, val where: String? = null, val error: String? = null)

    private val _status = MutableStateFlow(Status(on = secrets.backupPassphrase.isNotBlank()))
    val status: StateFlow<Status> = _status.asStateFlow()
    @Volatile private var lastHash: Int? = null
    @Volatile private var fileUri: Uri? = null
    private val lock = Any()

    /** Turn the automatic backup on with [passphrase] (6+ characters) and write it now. */
    suspend fun enable(passphrase: String): Status {
        require(passphrase.length >= 6) { "The passphrase must be at least 6 characters" }
        secrets.backupPassphrase = passphrase
        lastHash = null
        return backupNow()
    }

    fun disable() {
        secrets.backupPassphrase = ""
        _status.value = Status(on = false)
    }

    /** Write the backup if a passphrase is set and anything changed since the last one. */
    suspend fun auto() {
        if (secrets.backupPassphrase.isBlank()) return
        runCatching { backupNow(onlyIfChanged = true) }
    }

    suspend fun backupNow(onlyIfChanged: Boolean = false): Status {
        val pass = secrets.backupPassphrase
        if (pass.isBlank()) return Status(on = false, error = "Set a passphrase first").also { _status.value = it }
        return runCatching {
            val content = snapshot()
            val hash = content.toString().hashCode()
            if (onlyIfChanged && hash == lastHash) return _status.value
            val bytes = SettingsBackup.seal(
                buildJsonObject {
                    put("v", SettingsBackup.VERSION); put("app", appVersion); put("savedAt", System.currentTimeMillis())
                    put("data", content)
                }.toString(),
                pass.toCharArray()
            )
            val where = write(bytes)
            lastHash = hash
            Status(on = true, lastMs = System.currentTimeMillis(), where = where)
        }.getOrElse { e -> Status(on = true, lastMs = _status.value.lastMs, where = _status.value.where, error = e.message ?: e.javaClass.simpleName) }
            .also { _status.value = it }
    }

    /** Everything that is backed up, as JSON (no timestamps, so an unchanged phone gives the same text). */
    suspend fun snapshot(): JsonObject {
        val signal = preferences.exportAll()
        val hub = dataPrefs.exportAll()
        val (id, pem) = preferences.credentialSnapshot()
        val (demoId, demoPem) = preferences.demoSnapshot()
        return buildJsonObject {
            put("signal", SettingsBackup.encode(signal))
            put("hub", SettingsBackup.encode(hub))
            put("shared", buildJsonObject {
                for (name in SHARED) {
                    val all = app.getSharedPreferences(name, Context.MODE_PRIVATE).all.filterKeys { it !in (SKIP[name] ?: emptySet()) }
                    put(name, SettingsBackup.encode(all))
                }
            })
            put("secrets", buildJsonObject {
                put("groq", secrets.groqApiKey); put("cerebras", secrets.cerebrasApiKey)
                put("gemini", secrets.geminiApiKey); put("mistral", secrets.mistralApiKey)
                put("github", secrets.githubToken)
                put("paperAiOn", secrets.paperAiEnabled); put("supervisorOn", secrets.supervisorEnabled)
                put("kalshiKeyId", id); put("kalshiPem", pem)
                put("demoKeyId", demoId); put("demoPem", demoPem)
            })
        }
    }

    /**
     * Restore [bytes] with [passphrase]: every setting, switch, arena setting, the paper record and the keys. Keeps
     * [passphrase] so the automatic backup carries on. Returns a short summary; call [restartApp] after it.
     */
    suspend fun restore(bytes: ByteArray, passphrase: String): String {
        val text = SettingsBackup.open(bytes, passphrase.toCharArray())
        val root = SettingsBackup.parse(text)
        val data = SettingsBackup.obj(root, "data") ?: throw SettingsBackup.BadFile()
        var n = 0
        SettingsBackup.obj(data, "signal")?.let { val m = SettingsBackup.decode(it); preferences.importAll(m); n += m.size }
        SettingsBackup.obj(data, "hub")?.let { val m = SettingsBackup.decode(it); dataPrefs.importAll(m); n += m.size }
        SettingsBackup.obj(data, "shared")?.let { sh ->
            for (name in SHARED) SettingsBackup.obj(sh, name)?.let { o ->
                val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                val keep = sp.all.filterKeys { it in (SKIP[name] ?: emptySet()) }
                val m = SettingsBackup.decode(o)
                SettingsBackup.intoShared(sp, SettingsBackup.decode(SettingsBackup.encode(keep)) + m)
                n += m.size
            }
        }
        var keys = 0
        SettingsBackup.obj(data, "secrets")?.let { s ->
            fun v(k: String) = SettingsBackup.str(s, k).orEmpty()
            if (v("groq").isNotBlank()) { secrets.groqApiKey = v("groq"); keys++ }
            if (v("cerebras").isNotBlank()) { secrets.cerebrasApiKey = v("cerebras"); keys++ }
            if (v("gemini").isNotBlank()) { secrets.geminiApiKey = v("gemini"); keys++ }
            if (v("mistral").isNotBlank()) { secrets.mistralApiKey = v("mistral"); keys++ }
            if (v("github").isNotBlank()) secrets.githubToken = v("github")
            SettingsBackup.str(s, "paperAiOn")?.toBooleanStrictOrNull()?.let { secrets.paperAiEnabled = it }
            SettingsBackup.str(s, "supervisorOn")?.toBooleanStrictOrNull()?.let { secrets.supervisorEnabled = it }
            if (v("kalshiKeyId").isNotBlank() && v("kalshiPem").isNotBlank()) { preferences.saveCredentials(v("kalshiKeyId"), v("kalshiPem")); keys++ }
            if (v("demoKeyId").isNotBlank() && v("demoPem").isNotBlank()) preferences.saveDemoCredentials(v("demoKeyId"), v("demoPem"))
        }
        secrets.backupPassphrase = passphrase
        val from = SettingsBackup.str(root, "app")?.let { " from $it" }.orEmpty()
        return "Restored $n settings and $keys keys$from. The app restarts now."
    }

    /** Start the app again from its launcher screen, so every part reads the restored settings. */
    fun restartApp() {
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        app.startActivity(i)
        Runtime.getRuntime().exit(0)
    }

    /** Writes the file to Downloads/ClaudeBitcoin (Android 10+: no permission needed for the app's own file). */
    private fun write(bytes: ByteArray): String = synchronized(lock) {
        check(Build.VERSION.SDK_INT >= 29) { "needs Android 10 or newer" }
        val resolver = app.contentResolver
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + SettingsBackup.FOLDER + "/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = fileUri ?: resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(rel, SettingsBackup.FILE_NAME), null
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
            ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, SettingsBackup.FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            })
            ?: error("could not create the backup file")
        val ok = runCatching { resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("could not open the backup file") }
        if (ok.isFailure) {
            // The file was deleted from outside: make a new one next time.
            fileUri = null
            throw ok.exceptionOrNull()!!
        }
        fileUri = uri
        "Downloads/${SettingsBackup.FOLDER}/${SettingsBackup.FILE_NAME}"
    }

    companion object {
        /** The app's SharedPreferences files in the backup: the arena (settings, record, gate, engine), the AIs, the on-phone LLM. */
        val SHARED = listOf("bitcoin_claude_scalper", "bitcoin_claude_supervisor", "bitcoin_claude_local_llm")
        /** Large logs left out (the AI tab's call log and the chat); they are not settings. */
        val SKIP = mapOf("bitcoin_claude_supervisor" to setOf("ai_activity", "chat_json"))
    }
}
