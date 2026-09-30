package com.dirk.kalshiarb.data

import com.dirk.kalshiarb.scan.EventInfo
import com.dirk.kalshiarb.scan.MarketBook
import com.dirk.kalshiarb.scan.ScanResult
import com.dirk.kalshiarb.scan.Scanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanBudget(
    /** 200 events per page. */
    val maxEventPages: Int = 40,
    /** Order books fetched per scan (~8/s → under 20 s). */
    val maxBooks: Int = 150
)

data class ScanReport(
    val startedMs: Long,
    val finishedMs: Long,
    val events: Int,
    val markets: Int,
    val candidates: Int,
    val booksFetched: Int,
    val result: ScanResult,
    val warnings: List<String>
)

/** Pulls public data and runs the pure [Scanner]. */
class ScanRunner(private val client: KalshiPublicClient = KalshiPublicClient()) {

    suspend fun scan(
        budget: ScanBudget = ScanBudget(),
        progress: (String) -> Unit = {}
    ): ScanReport {
        val started = System.currentTimeMillis()
        val warnings = ArrayList<String>()
        val events = ArrayList<EventInfo>()
        var cursor: String? = null
        var pages = 0
        try {
            do {
                progress("Listing open events… ${events.size}")
                val page = client.eventsPage(cursor)
                events += page.events
                cursor = page.cursor
                pages++
            } while (cursor != null && pages < budget.maxEventPages)
            if (cursor != null) warnings += "Stopped listing after $pages pages (budget)."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (events.isEmpty()) throw e
            warnings += "Event listing cut short: ${e.message}"
        }

        val candidates = withContext(Dispatchers.Default) { Scanner.plan(events) }
        val tickers = Scanner.booksToFetch(candidates, budget.maxBooks)
        val books = HashMap<String, MarketBook>()
        var failed = 0
        for ((i, t) in tickers.withIndex()) {
            progress("Order books ${i + 1}/${tickers.size}")
            try {
                books[t] = client.orderbook(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        if (failed > 0) warnings += "$failed order books failed to load."
        val skipped = candidates.count { c -> c.tickers.any { it !in books } }
        if (skipped > 0) warnings += "$skipped candidates not priced (book budget)."

        val result = withContext(Dispatchers.Default) { Scanner.evaluate(candidates, books) }
        return ScanReport(
            startedMs = started,
            finishedMs = System.currentTimeMillis(),
            events = events.size,
            markets = events.sumOf { it.markets.size },
            candidates = candidates.size,
            booksFetched = books.size,
            result = result,
            warnings = warnings
        )
    }
}
