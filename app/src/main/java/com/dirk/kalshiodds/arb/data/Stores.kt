package com.dirk.kalshiodds.arb.data

import android.content.Context
import com.dirk.kalshiodds.arb.scan.PaperEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Paper log persisted as a small JSON file in app-private storage. */
class PaperLogStore(context: Context) {

    private val file = File(context.filesDir, "arb_paper_log.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(PaperEntry.serializer())

    suspend fun load(): List<PaperEntry> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) emptyList() else json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun save(entries: List<PaperEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, entries))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
        Unit
    }

    suspend fun clear() = save(emptyList())
}

/** On/off switches. No secrets are ever stored: this app has no API key. */
class ArbSettings(context: Context) {

    private val prefs = context.getSharedPreferences("arb_settings", Context.MODE_PRIVATE)

    var autoScan: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO, v).apply()

    var notify: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY, v).apply()

    var lightTheme: Boolean
        get() = prefs.getBoolean(KEY_LIGHT, false)
        set(v) = prefs.edit().putBoolean(KEY_LIGHT, v).apply()

    private companion object {
        const val KEY_AUTO = "auto_scan"
        const val KEY_NOTIFY = "notify"
        const val KEY_LIGHT = "light_theme"
    }
}
