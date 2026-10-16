package com.dirk.kalshiodds.ui

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpTrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 0.3.67: newest-first keyset pages over idx_scalp_scalper_closed (no OFFSET scans, never loads the whole table). */
class ScalpTradePagingSource(private val book: ScalpBook, private val scalperId: String) : PagingSource<Pair<Long, String>, ScalpTrade>() {
    override suspend fun load(params: LoadParams<Pair<Long, String>>): LoadResult<Pair<Long, String>, ScalpTrade> = try {
        val key = params.key
        val rows = withContext(Dispatchers.IO) { book.pageTrades(scalperId, key?.first, key?.second, params.loadSize) }
        val last = rows.lastOrNull()
        LoadResult.Page(rows, prevKey = null, nextKey = if (rows.size < params.loadSize || last == null) null else (last.closedAtMs ?: 0L) to last.id)
    } catch (t: Throwable) { LoadResult.Error(t) }

    override fun getRefreshKey(state: PagingState<Pair<Long, String>, ScalpTrade>): Pair<Long, String>? = null
}
