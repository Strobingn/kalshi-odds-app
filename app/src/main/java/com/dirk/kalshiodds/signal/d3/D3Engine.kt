package com.dirk.kalshiodds.signal.d3

import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-day D3 bookkeeping: resting paper bids, one-position latch, and
 * the snapshot the home card reads. Never places a live order.
 */
class D3Engine(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    var schedule: D3Fees.Schedule = D3Fees.fromSeries("quadratic", 1.0, 0.0)
        private set

    private val resting = ConcurrentHashMap<String, D3RestingBid>()
    private val notified = ConcurrentHashMap.newKeySet<String>()

    fun applySchedule(next: D3Fees.Schedule) {
        schedule = next
    }

    fun forgetStale(liveTickers: Set<String>) {
        val keep = liveTickers.map { it.uppercase() }.toSet()
        resting.keys.toList().forEach { if (it !in keep) resting.remove(it) }
    }

    fun restingBids(): List<D3RestingBid> = resting.values.toList()

    fun snapshot(
        quotes: List<D3Quote>,
        store: D3Store,
        books: Map<String, BookLevelSnapshot> = emptyMap(),
        bankrollUsd: Double = 0.0,
        now: Long = nowMs()
    ): D3Snapshot {
        val fivePm = quotes.filter { D3Window.closeIsFivePmEt(it.closeTimeEpochMs) }
        val close = fivePm.mapNotNull { it.closeTimeEpochMs }.minOrNull()
            ?: quotes.mapNotNull { it.closeTimeEpochMs }.firstOrNull { D3Window.closeIsFivePmEt(it) }
            ?: D3Window.impliedCloseMs(now)
        val phase = D3Window.phase(now, close)
        val (start, end) = D3Window.windowBounds(close)
        val event = fivePm.firstOrNull()?.eventTicker
        val qualifying = if (phase == D3Phase.ACTIVE) {
            fivePm.mapNotNull { q ->
                D3Strategy.evaluate(
                    D3Strategy.Inputs(
                        quote = q,
                        nowMs = now,
                        alreadyTakenToday = store.takenToday(q.ticker, now),
                        book = books[q.ticker],
                        bankrollUsd = bankrollUsd,
                        schedule = schedule
                    )
                )
            }
        } else {
            emptyList()
        }
        val today = store.today(now)
        return D3Snapshot(
            phase = phase,
            eventTicker = event,
            closeTimeEpochMs = close,
            windowStartMs = start,
            windowEndMs = end,
            startsInMs = D3Window.startsInMs(now, close),
            qualifying = qualifying,
            resting = restingBids(),
            todayPicks = today,
            todayLine = D3Copy.todayPaperLine(today),
            feeType = schedule.feeType,
            makerFeeUsd = schedule.makerFeeRate
        )
    }

    /**
     * Advance paper resting bids. Returns newly fired signals (for alerts
     * + live tickets) and never calls Kalshi.
     */
    fun tickPaper(
        quotes: List<D3Quote>,
        store: D3Store,
        tradesByTicker: Map<String, List<D3TradePrint>>,
        books: Map<String, BookLevelSnapshot> = emptyMap(),
        paperAutopilot: Boolean,
        bankrollUsd: Double,
        now: Long = nowMs()
    ): List<D3Signal> {
        val snap = snapshot(quotes, store, books, bankrollUsd, now)
        val quoteBy = quotes.associateBy { it.ticker.uppercase() }
        val fired = mutableListOf<D3Signal>()

        resting.values.toList().forEach { bid ->
            val q = quoteBy[bid.ticker.uppercase()]
            val leftBand = q != null && !D3Strategy.stillInBand(q)
            val windowOver = snap.phase != D3Phase.ACTIVE
            if (windowOver || leftBand) {
                store.cancelUnfilled(
                    bid.ticker,
                    if (windowOver) "window closed" else "left 85-97¢ band",
                    now
                )
                resting.remove(bid.ticker.uppercase())
                return@forEach
            }
            val trades = tradesByTicker[bid.ticker.uppercase()].orEmpty()
            if (D3FillMath.filled(bid.side, bid.bidPrice, bid.sizeAhead, trades, bid.placedAtMs)) {
                store.markFilled(bid.ticker, now)
                resting.remove(bid.ticker.uppercase())
            }
        }

        if (snap.phase != D3Phase.ACTIVE) return emptyList()
        for (signal in snap.qualifying) {
            val key = signal.ticker.uppercase()
            if (notified.add("$key:${D3Window.dayKey(now)}")) {
                fired += signal
            }
            if (!paperAutopilot) continue
            if (store.takenToday(signal.ticker, now)) continue
            val logged = store.recordResting(signal) ?: continue
            resting[key] = D3RestingBid(
                ticker = logged.ticker,
                side = logged.side,
                bidPrice = logged.bidPrice,
                sizeAhead = signal.sizeAhead,
                contracts = logged.contracts,
                stakeUsd = logged.stakeUsd,
                feeUsd = logged.feeUsd,
                placedAtMs = logged.createdAtMs
            )
        }
        return fired
    }
}
