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

    /**
     * Rows to upload. A key missing from [remote] is included even when it is
     * older than the last attempt, so a failed sync cannot skip it.
     * Equal timestamps upload only when the payload differs (local won the tie).
     */
    fun pendingUpload(local: List<Record>, remote: List<Record>): List<Record> {
        val remoteByKey = HashMap<String, Record>(remote.size)
        remote.forEach { remoteByKey[it.key] = it }
        return local.filter { row ->
            if (isForbiddenPayload(row.payload)) return@filter false
            val existing = remoteByKey[row.key] ?: return@filter true
            row.updatedAtMs > existing.updatedAtMs ||
                (row.updatedAtMs == existing.updatedAtMs && row.payload != existing.payload)
        }
    }

    /** Remote rows that should be written locally. Ties keep the local row. */
    fun incomingWinners(local: List<Record>, remote: List<Record>): List<Record> {
        val localByKey = HashMap<String, Record>(local.size)
        local.forEach { localByKey[it.key] = it }
        val out = ArrayList<Record>()
        for (row in remote) {
            if (!ownsNamespace(row.key) || isForbiddenPayload(row.payload)) continue
            val mine = localByKey[row.key]
            if (mine == null || row.updatedAtMs > mine.updatedAtMs) out.add(row)
        }
        return out
    }
}
