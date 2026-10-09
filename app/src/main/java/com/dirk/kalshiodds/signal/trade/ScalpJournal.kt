package com.dirk.kalshiodds.signal.trade
import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
class ScalpJournal(context: Context) {
    private val prefs = context.getSharedPreferences("edge_scalp_replay", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): List<ScalpEngine.Replay> = prefs.getString("replays", null)?.let {
        json.decodeFromString<List<ScalpEngine.Replay>>(it)
    }.orEmpty()
    fun save(records: List<ScalpEngine.Replay>) {
        check(prefs.edit().putString("replays", json.encodeToString(records)).commit()) { "Replay save failed" }
    }
}
