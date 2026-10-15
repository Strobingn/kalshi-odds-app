package com.dirk.kalshiodds.ui

/**
 * Unique, stable keys for Compose [androidx.compose.foundation.lazy.items].
 * [base] must be an identity (ticker, id, timestamp) — never display copy.
 * Duplicate bases get an index suffix so LazyColumn never throws
 * `IllegalArgumentException: Key "…" was already used`.
 */
object LazyListKeys {
    fun <T> assign(items: List<T>, base: (T) -> Any): List<String> {
        val seen = HashMap<String, Int>(items.size)
        return items.map { item ->
            val raw = base(item).toString().ifBlank { "row" }
            val n = seen[raw] ?: 0
            seen[raw] = n + 1
            if (n == 0) raw else "$raw#$n"
        }
    }

    fun <T> keyed(items: List<T>, base: (T) -> Any): List<Keyed<T>> {
        val keys = assign(items, base)
        return items.mapIndexed { i, item -> Keyed(keys[i], item) }
    }

    data class Keyed<T>(val key: String, val value: T)
}
