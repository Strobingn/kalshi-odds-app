package com.dirk.kalshiodds.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 1.0.4: one message in the AI tab's persistent chat. [role] is "user", "ai", "error", "proposal" (a settings
 * confirm card) or "change" (the logged result of Apply/Cancel). Proposals carry key/old/new and a [status]
 * of "pending", "applied", "cancelled" or "rejected".
 */
@Serializable
data class ChatMessage(
    val id: Long, val role: String, val text: String, val atMs: Long,
    val key: String? = null, val oldValue: String? = null, val newValue: String? = null, val status: String? = null
) {
    companion object {
        const val USER = "user"; const val AI = "ai"; const val ERROR = "error"; const val PROPOSAL = "proposal"; const val CHANGE = "change"
        const val PENDING = "pending"; const val APPLIED = "applied"; const val CANCELLED = "cancelled"
    }
}

/**
 * Local, file-backed chat history (JSON in the app's private files dir). Restored on launch; writes are
 * atomic (temp file + rename) so a crash mid-write never loses the previous history. Blocking: call off Main.
 */
class ChatHistoryStore(private val file: File, private val maxMessages: Int = 500) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun load(): List<ChatMessage> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching { json.decodeFromString<List<ChatMessage>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun saveAll(messages: List<ChatMessage>) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(messages.takeLast(maxMessages)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun append(message: ChatMessage): List<ChatMessage> = synchronized(lock) { (load() + message).also { saveAll(it) } }

    fun update(message: ChatMessage): List<ChatMessage> = synchronized(lock) {
        load().map { if (it.id == message.id) message else it }.also { saveAll(it) }
    }

    fun clear() = synchronized(lock) { file.delete(); File(file.parentFile, file.name + ".tmp").delete(); Unit }

    companion object { const val FILE_NAME = "ai_chat_history.json" }
}
