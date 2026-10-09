package com.dirk.kalshiodds.data.local.history

/** Manual paging so History stays off Paging 3 / extra heap. */
object HistoryPager {
    fun <T> page(all: List<T>, offset: Int, pageSize: Int): HistoryPage<T> {
        val size = pageSize.coerceIn(1, 200)
        val start = offset.coerceAtLeast(0)
        if (start >= all.size) return HistoryPage(emptyList(), start, false)
        val end = (start + size).coerceAtMost(all.size)
        return HistoryPage(
            items = all.subList(start, end),
            offset = start,
            hasMore = end < all.size
        )
    }
}
