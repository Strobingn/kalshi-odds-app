package com.dirk.kalshiodds.data.supabase

import java.security.MessageDigest

/** This install's rows in the shared `diphunter_sync` table. Other apps keep their own prefix. */
internal const val SYNC_NAMESPACE = "grokbot:"

/**
 * Supabase REST returns at most 1000 rows. Asking for limit=2000 still
 * stops at that cap, so callers page at this size.
 */
internal const val SYNC_PAGE_SIZE = 1000

internal fun namespacedKey(logical: String): String {
    val trimmed = logical.trim()
    require(trimmed.isNotBlank()) { "sync key blank" }
    return if (trimmed.startsWith(SYNC_NAMESPACE)) trimmed else SYNC_NAMESPACE + trimmed
}

internal fun ownsNamespace(key: String): Boolean = key.startsWith(SYNC_NAMESPACE)

/**
 * One row per key for a single upsert request.
 * Identical payloads collapse to the newest timestamp.
 * Distinct payloads that share a key are kept, each under its own key.
 */
internal fun dedupeUpsertBatch(rows: List<SyncMerge.Record>): List<SyncMerge.Record> {
    if (rows.isEmpty()) return emptyList()
    val groups = LinkedHashMap<String, MutableList<SyncMerge.Record>>()
    for (row in rows) {
        groups.getOrPut(row.key) { ArrayList() }.add(row)
    }
    val staged = ArrayList<SyncMerge.Record>(rows.size)
    for ((key, group) in groups) {
        val newestByPayload = LinkedHashMap<String, SyncMerge.Record>()
        for (row in group) {
            val prev = newestByPayload[row.payload]
            if (prev == null || row.updatedAtMs >= prev.updatedAtMs) {
                newestByPayload[row.payload] = row
            }
        }
        val distinct = newestByPayload.values
        if (distinct.size == 1) {
            staged.add(distinct.first())
        } else {
            for (row in distinct) {
                staged.add(row.copy(key = "$key#${payloadFingerprint(row.payload)}"))
            }
        }
    }
    val seen = HashSet<String>(staged.size)
    val out = ArrayList<SyncMerge.Record>(staged.size)
    for (row in staged) {
        var key = row.key
        var n = 2
        while (!seen.add(key)) {
            key = "${row.key}#$n"
            n++
        }
        out.add(if (key == row.key) row else row.copy(key = key))
    }
    return out
}

internal fun payloadFingerprint(payload: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
}
