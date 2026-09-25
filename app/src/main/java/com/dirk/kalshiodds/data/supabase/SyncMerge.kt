package com.dirk.kalshiodds.data.supabase

/**
 * Incremental upsert + last-write-wins conflict handling for History /
 * bets / signals / settings. Never carries Kalshi keys or API secrets.
 */
object SyncMerge {

    data class Record(
        val key: String,
        val kind: String,
        val updatedAtMs: Long,
        val payload: String,
        val deleted: Boolean = false
    ) {
        init {
            require(key.isNotBlank()) { "sync key blank" }
            require(kind.isNotBlank()) { "sync kind blank" }
        }
    }

    data class Result(
        val upserts: List<Record>,
        val skipped: Int,
        val conflictsWon: Int,
        val conflictsLost: Int
    )

    val FORBIDDEN_KEYS = setOf(
        "apiKeyId", "api_key_id", "privateKeyPem", "private_key_pem",
        "pem", "secret", "apiSecret", "api_secret", "kalshiKey",
        "githubToken", "github_token", "service_role", "serviceRole",
        "demoApiKeyId", "demo_api_key_id", "demoPrivateKeyPem", "kalshi_demo_private_key_pem"
    )

    fun isForbiddenPayload(payload: String): Boolean {
        val lower = payload.lowercase()
        return FORBIDDEN_KEYS.any { token ->
            lower.contains("\"${token.lowercase()}\"") ||
                (token.contains("BEGIN") && lower.contains("begin") && lower.contains("private"))
        } || (lower.contains("begin") && lower.contains("private key"))
    }

    /**
     * Merge [remote] into [local]. Same [Record.key] keeps the newer
     * [Record.updatedAtMs]. Equal timestamps keep the local row.
     */
    fun merge(local: List<Record>, remote: List<Record>): Result {
        val byKey = LinkedHashMap<String, Record>()
        local.forEach { rec ->
            if (isForbiddenPayload(rec.payload)) return@forEach
            byKey[rec.key] = rec
        }
        var skipped = 0
        var won = 0
        var lost = 0
        for (incoming in remote) {
            if (incoming.key.isBlank() || isForbiddenPayload(incoming.payload)) {
                skipped++
                continue
            }
            val existing = byKey[incoming.key]
            if (existing == null) {
                byKey[incoming.key] = incoming
                continue
            }
            when {
                incoming.updatedAtMs > existing.updatedAtMs -> {
                    byKey[incoming.key] = incoming
                    won++
                }
                incoming.updatedAtMs < existing.updatedAtMs -> {
                    lost++
                }
                else -> {
                    // tie → keep local
                    lost++
                }
            }
        }
        return Result(
            upserts = byKey.values.toList(),
            skipped = skipped,
            conflictsWon = won,
            conflictsLost = lost
        )
    }

    fun outgoing(local: List<Record>, lastPushMs: Long): List<Record> =
        local.filter { it.updatedAtMs >= lastPushMs && !isForbiddenPayload(it.payload) }
}
