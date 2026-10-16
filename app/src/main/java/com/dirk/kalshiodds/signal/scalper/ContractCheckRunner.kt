package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.signal.external.CfIndexBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the [ContractChecks] in the background: the series' fee type every
 * [FEE_EVERY_MS], and every [WINDOWS_EVERY_MS] the latest settled Bitcoin
 * windows against Kalshi's own index history ([CfIndexBook]). Read-only
 * calls to Kalshi's public API; never an order.
 */
class ContractCheckRunner(
    private val book: ContractCheckBook,
    private val cf: CfIndexBook,
    private val api: () -> KalshiApi,
    /** Told the maker fee rate the arena must charge on resting fills. */
    private val onMakerFeeRate: (Double) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    fun start(scope: CoroutineScope): Job {
        book.snapshot().fee?.let { onMakerFeeRate(makerRate(it)) }
        return scope.launch {
            var lastFee = 0L
            while (isActive) {
                val now = nowMs()
                if (now - lastFee >= FEE_EVERY_MS) {
                    lastFee = now
                    checkFee(now)
                }
                runCatching { checkWindows(now) }
                delay(WINDOWS_EVERY_MS)
            }
        }
    }

    private suspend fun checkFee(now: Long) {
        val fee = runCatching { api().getSeries(SERIES).series }.fold(
            onSuccess = { s -> FeeCheck(SERIES, s?.feeType, s?.feeMultiplier, now, if (s?.feeType == null) "no fee_type in the reply" else null) },
            onFailure = { e -> FeeCheck(SERIES, null, null, now, e.message ?: e.javaClass.simpleName) }
        )
        book.setFee(fee)
        book.snapshot().fee?.let { onMakerFeeRate(makerRate(it)) }
    }

    private suspend fun checkWindows(now: Long) {
        val settled = api().getMarkets(seriesTicker = SERIES, status = "settled", limit = 8).markets
        for (m in settled) {
            if (book.has(m.ticker)) continue
            val close = m.closeTime?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: continue
            val settle = m.expirationValue?.trim()?.toDoubleOrNull()
            windowCheck(cf, "BTC", m.ticker, close, m.floorStrike, settle, now)?.let { book.add(it) }
        }
    }

    companion object {
        const val SERIES = KalshiApi.SERIES_BTC
        const val FEE_EVERY_MS = 6 * 60 * 60_000L
        const val WINDOWS_EVERY_MS = 5 * 60_000L
        private const val WINDOW_MS = PrintGrid.SECONDS * 1000L
        private const val MINUTE_MS = 60_000L

        /** The rate resting fills pay: 0 unless Kalshi lists the series with maker fees. */
        fun makerRate(fee: FeeCheck): Double =
            if (fee.makerFees == true) KalshiFee.MAKER_COEFFICIENT * (fee.feeMultiplier ?: 1.0) else 0.0

        /**
         * One settled window read against the index history, or null when the
         * history no longer (or never) covered it: nothing is recorded from a
         * window the phone was not watching.
         */
        fun windowCheck(
            cf: CfIndexBook,
            asset: String,
            ticker: String,
            closeMs: Long,
            strike: Double?,
            settleValue: Double?,
            nowMs: Long
        ): WindowCheck? {
            if (closeMs > nowMs) return null
            val open = closeMs - WINDOW_MS
            val before = cf.mean(asset, open - MINUTE_MS, open - 1_000L)?.takeIf { it.second >= 55 }?.first
            val after = cf.mean(asset, open, open + MINUTE_MS - 1_000L)?.takeIf { it.second >= 55 }?.first
            val at = cf.valueAt(asset, open)
            val last = cf.mean(asset, closeMs - MINUTE_MS, closeMs - 1_000L)
            if (before == null && after == null && at == null && last == null) return null
            return WindowCheck(
                ticker = ticker,
                closeMs = closeMs,
                strike = strike?.takeIf { it.isFinite() && it > 0.0 },
                beforeOpen = before,
                afterOpen = after,
                atOpen = at,
                lastMinute = last?.first,
                settleValue = settleValue?.takeIf { it.isFinite() && it > 0.0 },
                lastMinuteSeconds = last?.second ?: 0
            )
        }
    }
}
