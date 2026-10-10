package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperBuy
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.math.floor

/** One open scalp. Never sent to Kalshi. */
@Serializable
data class ScalpPosition(
    val ticker: String,
    val side: String,
    val contracts: Int,
    val entry: Double,
    val stakeUsd: Double,
    val feeUsd: Double,
    val peakBid: Double,
    val lastBid: Double,
    val openedAtMs: Long
)

@Serializable
data class ScalpAccount(
    val id: String,
    val name: String,
    val rule: String,
    val cashUsd: Double = ScalpCatalog.START_CASH,
    val position: ScalpPosition? = null,
    val realizedPnlUsd: Double = 0.0,
    val trades: Int = 0,
    val wins: Int = 0,
    val lastNote: String = "Flat. Waiting for a 15-minute quote."
) {
    /** Cash plus a bid mark of the open scalp. */
    fun equityUsd(): Double {
        val pos = position ?: return cashUsd
        return cashUsd + pos.contracts * (pos.lastBid)
    }

    fun pnlUsd(): Double = equityUsd() - ScalpCatalog.START_CASH
}

@Serializable
data class ScalpLabState(
    val accounts: List<ScalpAccount> = ScalpCatalog.merge(emptyList())
)

/**
 * Fifteen paper scalps on the live Bitcoin 15-minute contract.
 * Each book starts at $100 and sizes itself. No live order path.
 */
class ScalpLab(
    initial: ScalpLabState = ScalpLabState(),
    private val persist: (ScalpLabState) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial.copy(accounts = ScalpCatalog.merge(initial.accounts)))
    val state: StateFlow<ScalpLabState> = _state.asStateFlow()

    private var tapeTicker: String? = null
    private val yesBids = ArrayDeque<Double>()
    private val noBids = ArrayDeque<Double>()
    private var yesHigh = 0.0
    private var yesLow = 1.0
    private var noHigh = 0.0
    private var noLow = 1.0

    fun snapshot(): ScalpLabState = _state.value

    fun openTickers(): Set<String> = synchronized(lock) {
        _state.value.accounts.mapNotNull { it.position?.ticker?.uppercase() }.toSet()
    }

    fun reset() {
        synchronized(lock) {
            clearTape()
            publish(ScalpLabState(accounts = ScalpCatalog.merge(emptyList())))
        }
    }

    fun onMarkets(markets: List<MarketUiModel>, now: Long = nowMs()) {
        val market = btcWindow(markets, now) ?: return
        val tape = rememberTape(market, now) ?: return
        onQuote(tape)
    }

    fun onQuote(tape: ScalpTape) {
        synchronized(lock) {
            val next = ArrayList<ScalpAccount>(_state.value.accounts.size)
            var changed = false
            for (account in _state.value.accounts) {
                val stepped = step(account, tape)
                if (stepped != account) changed = true
                next += stepped
            }
            if (changed) publish(_state.value.copy(accounts = next))
        }
    }

    fun settle(ticker: String, result: String): Int {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return 0
        var n = 0
        synchronized(lock) {
            val next = _state.value.accounts.map { account ->
                val pos = account.position
                if (pos == null || !pos.ticker.equals(ticker, ignoreCase = true)) return@map account
                n += 1
                closeSettlement(account, pos, outcome)
            }
            if (n > 0) publish(_state.value.copy(accounts = next))
        }
        return n
    }

    fun settleFromLog(entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>) {
        val open = openTickers()
        if (open.isEmpty()) return
        entries.forEach { e ->
            val o = e.outcome ?: return@forEach
            if (open.any { it.equals(e.ticker, ignoreCase = true) }) settle(e.ticker, o)
        }
    }

    private fun step(account: ScalpAccount, tape: ScalpTape): ScalpAccount {
        val held = account.position
        if (held != null && !held.ticker.equals(tape.ticker, true)) return account
        val marked = mark(account, tape)
        val pos = marked.position
        val intent = ScalpCatalog.ALL.first { it.id == marked.id }.decide(marked, tape)
        return when (intent) {
            is ScalpIntent.Buy -> if (pos == null) buy(marked, tape, intent) else marked
            is ScalpIntent.Sell -> {
                if (pos == null || !pos.ticker.equals(tape.ticker, true)) marked
                else if (intent.force || profitable(pos, ScalpCatalog.bid(tape, pos.side))) sell(marked, pos, tape)
                else marked
            }
            ScalpIntent.Hold -> marked
        }
    }

    private fun mark(account: ScalpAccount, tape: ScalpTape): ScalpAccount {
        val pos = account.position ?: return account
        if (!pos.ticker.equals(tape.ticker, true)) return account
        val b = ScalpCatalog.bid(tape, pos.side)
        val peak = maxOf(pos.peakBid, b)
        if (peak == pos.peakBid && pos.lastBid == b) return account
        return account.copy(position = pos.copy(peakBid = peak, lastBid = b))
    }

    private fun buy(account: ScalpAccount, tape: ScalpTape, intent: ScalpIntent.Buy): ScalpAccount {
        val side = if (intent.side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(if (side == "NO") tape.noAsk else tape.yesAsk) ?: return account
        val fraction = intent.fraction.coerceIn(0.01, 1.0)
        val budget = account.cashUsd * fraction
        if (budget + 1e-9 < px) return account
        val want = floor(budget / px + 1e-9).toInt()
        val (qty, _) = PaperBuy.capContracts(want, account.cashUsd, px)
        if (qty < 1) return account
        val stake = qty * px
        val fee = KalshiFee.total(qty, px)
        if (stake + fee > account.cashUsd + 1e-6) return account
        val bid = ScalpCatalog.bid(tape, side)
        val pos = ScalpPosition(
            ticker = tape.ticker,
            side = side,
            contracts = qty,
            entry = px,
            stakeUsd = stake,
            feeUsd = fee,
            peakBid = bid,
            lastBid = bid,
            openedAtMs = nowMs()
        )
        return account.copy(
            cashUsd = account.cashUsd - stake - fee,
            position = pos,
            lastNote = "BUY $side $qty ct @ ${cents(px)} · ${pct(fraction)} of cash"
        )
    }

    private fun sell(account: ScalpAccount, pos: ScalpPosition, tape: ScalpTape): ScalpAccount {
        val bid = KalshiPrice.usable(ScalpCatalog.bid(tape, pos.side)) ?: return account
        val sellFee = KalshiFee.total(pos.contracts, bid)
        val proceeds = pos.contracts * bid
        val pnl = proceeds - sellFee - pos.stakeUsd - pos.feeUsd
        val win = pnl > 0.0
        return account.copy(
            cashUsd = account.cashUsd + proceeds - sellFee,
            position = null,
            realizedPnlUsd = account.realizedPnlUsd + pnl,
            trades = account.trades + 1,
            wins = account.wins + if (win) 1 else 0,
            lastNote = "SELL ${pos.side} @ ${cents(bid)} · ${money(pnl)}"
        )
    }

    private fun closeSettlement(account: ScalpAccount, pos: ScalpPosition, outcome: String): ScalpAccount {
        val won = when (outcome) {
            "void" -> null
            "yes" -> pos.side == "YES"
            else -> pos.side == "NO"
        }
        val payout = when {
            outcome == "void" -> pos.stakeUsd + pos.feeUsd
            won == true -> pos.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD
            else -> 0.0
        }
        val pnl = payout - pos.stakeUsd - pos.feeUsd
        val label = when (outcome) {
            "void" -> "VOID"
            "yes" -> if (won == true) "SETTLE WIN" else "SETTLE LOSS"
            else -> if (won == true) "SETTLE WIN" else "SETTLE LOSS"
        }
        return account.copy(
            cashUsd = account.cashUsd + payout,
            position = null,
            realizedPnlUsd = account.realizedPnlUsd + pnl,
            trades = account.trades + 1,
            wins = account.wins + if (pnl > 0.0) 1 else 0,
            lastNote = "$label ${pos.side} · ${money(pnl)}"
        )
    }

    private fun rememberTape(market: MarketUiModel, now: Long): ScalpTape? {
        val yesBid = KalshiPrice.usable(market.yesBid) ?: return null
        val noBid = KalshiPrice.usable(market.noBid) ?: return null
        val yesAsk = KalshiPrice.usable(market.yesAsk) ?: KalshiPrice.impliedAskFromOppositeBid(market.noBid) ?: return null
        val noAsk = KalshiPrice.usable(market.noAsk) ?: KalshiPrice.impliedAskFromOppositeBid(market.yesBid) ?: return null
        val open = MarketLifecycle.openTimeEpochMs(market) ?: return null
        val close = market.closeTimeEpochMs ?: return null
        synchronized(lock) {
            if (tapeTicker != market.ticker) {
                clearTape()
                tapeTicker = market.ticker
                yesHigh = yesBid
                yesLow = yesBid
                noHigh = noBid
                noLow = noBid
            }
            push(yesBids, yesBid)
            push(noBids, noBid)
            yesHigh = maxOf(yesHigh, yesBid)
            yesLow = minOf(yesLow, yesBid)
            noHigh = maxOf(noHigh, noBid)
            noLow = minOf(noLow, noBid)
            return ScalpTape(
                ticker = market.ticker,
                yesBid = yesBid,
                yesAsk = yesAsk,
                noBid = noBid,
                noAsk = noAsk,
                elapsedMs = (now - open).coerceAtLeast(0L),
                tteMs = (close - now).coerceAtLeast(0L),
                yesBids = yesBids.toList(),
                noBids = noBids.toList(),
                yesHigh = yesHigh,
                yesLow = yesLow,
                noHigh = noHigh,
                noLow = noLow
            )
        }
    }

    private fun publish(next: ScalpLabState) {
        _state.value = next
        persist(next)
    }

    private fun clearTape() {
        tapeTicker = null
        yesBids.clear()
        noBids.clear()
        yesHigh = 0.0
        yesLow = 1.0
        noHigh = 0.0
        noLow = 1.0
    }

    private fun push(q: ArrayDeque<Double>, v: Double) {
        val last = q.lastOrNull()
        if (last != null && kotlin.math.abs(last - v) < 0.004) return
        q.addLast(v)
        while (q.size > 12) q.removeFirst()
    }

    companion object {
        fun btcWindow(markets: List<MarketUiModel>, nowMs: Long): MarketUiModel? {
            val live = markets.filter {
                CryptoMarkets.isLiveTicker(it.ticker) && MarketLifecycle.isCurrentWindow(it, nowMs)
            }
            return MarketLifecycle.currentOpenWindow(live, nowMs)
        }

        private fun cents(px: Double): String = String.format(java.util.Locale.US, "%.0f¢", px * 100.0)
        private fun pct(fraction: Double): String = String.format(java.util.Locale.US, "%.0f%%", fraction * 100.0)
        private fun money(pnl: Double): String = String.format(java.util.Locale.US, "%+.2f", pnl)
    }
}
